package com.hub.media.features.settings.data

/**
 * Persisted resume state for a bulk cover/author backfill pass
 * (ROADMAP Task 14 Phase A — [com.hub.media.features.books.domain.BulkBackfillUseCase]), stored in
 * the `app_settings` key-value store via [SettingsRepository] -- the same "no schema change needed"
 * shape [WeekStartDay] already established, chosen for exactly the reason [SettingsRepository]'s
 * own class KDoc documents (schema v4 exists so a *new* setting/state never needs a migration).
 *
 * ### Why a handful of scalar keys instead of one serialized blob
 * [SettingsRepository] only ever stores flat `String`/`Int`/`Boolean` values (no JSON/blob support,
 * and AGENTS.md §5 rules out adding a serialization dependency just for this). [pendingMediaIds] is
 * therefore stored as a single comma-joined string (UUIDs from [com.hub.media.core.util.newId]
 * never contain a comma, so no escaping is needed), and the counters are stored as separate `Int`
 * keys via [SettingsRepository.setInt]/[SettingsRepository.getInt] -- five small upserts per
 * checkpoint rather than one, but each is a plain typed accessor this repository already exposes.
 *
 * ### What "in progress" means
 * The *presence* of [KEY_PENDING_MEDIA_IDS] in the store (i.e. [getBulkBackfillState] returning
 * non-null) is the sole signal that a backfill is resumable -- not a separate boolean flag. A fresh
 * "no backfill has ever run, or the last one ran to completion" state has no keys under this
 * prefix at all (see [clearBulkBackfillState]), so [getBulkBackfillState] returning `null` and "a
 * backfill finished, nothing left to do" are the same observable state, which is the correct
 * behavior: there is nothing left for a resumed run to do either way.
 *
 * @property pendingMediaIds Media ids of books not yet resolved by this backfill run (or a prior
 *   interrupted one) -- the actual work queue [com.hub.media.features.books.domain.BulkBackfillUseCase.execute]
 *   consumes from. Order matters only in that it's stable across a resume (originally
 *   title-ordered, from [com.hub.media.features.books.data.BookRepository.getAllBooksWithDetails]).
 * @property totalCandidates The number of books that needed backfilling *and* had an ISBN when this
 *   backfill was first seeded (fixed for the life of this run/resume chain -- never recomputed on
 *   resume, so "312 of 480 done" stays meaningful across multiple paused/resumed runs even as
 *   [pendingMediaIds] shrinks).
 * @property noIsbnSkipped Books that needed backfilling but had no ISBN on record, computed once at
 *   seed time (ROADMAP Task 14 Phase A: "books with no ISBN can never be backfilled from a
 *   provider... report them rather than retrying forever"). Never part of [pendingMediaIds] --
 *   there is nothing a retry could ever resolve for them.
 * @property updated Cumulative count of books this backfill (across every run/resume in this
 *   chain) has written a new cover and/or new authors for.
 * @property noProviderData Cumulative count of books this backfill has fully resolved without
 *   writing anything -- every provider confirmed there was nothing to fill in (no cover anywhere,
 *   no authors on record). Distinct from [updated] so the final summary can honestly report "we
 *   looked, there's genuinely nothing there" separately from "we found and wrote something."
 */
public data class BulkBackfillState(
    public val pendingMediaIds: List<String>,
    public val totalCandidates: Int,
    public val noIsbnSkipped: Int,
    public val updated: Int,
    public val noProviderData: Int,
)

private const val KEY_PENDING_MEDIA_IDS = "bulk_backfill_pending_media_ids"
private const val KEY_TOTAL_CANDIDATES = "bulk_backfill_total_candidates"
private const val KEY_NO_ISBN_SKIPPED = "bulk_backfill_no_isbn_skipped"
private const val KEY_UPDATED = "bulk_backfill_updated"
private const val KEY_NO_PROVIDER_DATA = "bulk_backfill_no_provider_data"
private const val PENDING_ID_SEPARATOR = ","

/**
 * One-shot fetch of the currently-resumable [BulkBackfillState], or `null` if no backfill is in
 * progress (never started, or the last one ran to completion and was cleared) -- see
 * [BulkBackfillState]'s KDoc for why key-presence, not a separate flag, is the signal.
 */
public suspend fun SettingsRepository.getBulkBackfillState(): BulkBackfillState? {
    val pendingRaw = getString(KEY_PENDING_MEDIA_IDS) ?: return null
    val pendingIds = if (pendingRaw.isBlank()) emptyList() else pendingRaw.split(PENDING_ID_SEPARATOR)
    return BulkBackfillState(
        pendingMediaIds = pendingIds,
        totalCandidates = getInt(KEY_TOTAL_CANDIDATES) ?: pendingIds.size,
        noIsbnSkipped = getInt(KEY_NO_ISBN_SKIPPED) ?: 0,
        updated = getInt(KEY_UPDATED) ?: 0,
        noProviderData = getInt(KEY_NO_PROVIDER_DATA) ?: 0,
    )
}

/**
 * Persists [state], overwriting whatever resume state (if any) was there before. Called after
 * every single book a backfill run processes (not just at the end) -- see
 * [com.hub.media.features.books.domain.BulkBackfillUseCase.execute]'s KDoc for why per-book
 * checkpointing, not a single end-of-run write, is what makes this survive process death.
 */
public suspend fun SettingsRepository.saveBulkBackfillState(state: BulkBackfillState) {
    setString(KEY_PENDING_MEDIA_IDS, state.pendingMediaIds.joinToString(PENDING_ID_SEPARATOR))
    setInt(KEY_TOTAL_CANDIDATES, state.totalCandidates)
    setInt(KEY_NO_ISBN_SKIPPED, state.noIsbnSkipped)
    setInt(KEY_UPDATED, state.updated)
    setInt(KEY_NO_PROVIDER_DATA, state.noProviderData)
}

/**
 * Removes every key this file owns, reverting [getBulkBackfillState] to `null`. Called once a
 * backfill run empties [BulkBackfillState.pendingMediaIds] -- there is nothing left to resume, so
 * leaving stale counters behind would only right-pad a future fresh backfill's seed with numbers
 * from a completed, unrelated run.
 */
public suspend fun SettingsRepository.clearBulkBackfillState() {
    clear(KEY_PENDING_MEDIA_IDS)
    clear(KEY_TOTAL_CANDIDATES)
    clear(KEY_NO_ISBN_SKIPPED)
    clear(KEY_UPDATED)
    clear(KEY_NO_PROVIDER_DATA)
}
