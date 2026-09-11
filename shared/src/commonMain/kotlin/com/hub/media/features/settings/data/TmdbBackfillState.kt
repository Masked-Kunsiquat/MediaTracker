package com.hub.media.features.settings.data

/**
 * Persisted resume state for the library-wide TMDB backfill (#140 —
 * [com.hub.media.features.media.domain.BulkTmdbBackfillUseCase]), stored in the `app_settings`
 * key-value store exactly as [BulkBackfillState] is, and for the same reason: schema v4 exists so a
 * new setting or piece of state never needs a migration.
 *
 * ### Why this is a sibling of [BulkBackfillState] rather than a generalisation of it
 * The two hold the same *shape* — a work queue of media ids plus four counters — and the obvious move
 * is one parameterised type with a key prefix. It was considered and rejected on two counts:
 *
 * - **The book keys are live on users' devices.** `bulk_backfill_no_isbn_skipped` and
 *   `bulk_backfill_no_provider_data` name what they hold; a generalised store would have to spell
 *   them generically, and a key that changes spelling silently discards whatever in-flight resume
 *   state a user was carrying across the upgrade. Nothing is lost but the "312 of 480" — the next run
 *   re-seeds — which is precisely the kind of quiet regression that is cheaper not to introduce than
 *   to explain.
 * - **The counters do not mean the same thing.** [noTmdbIdSkipped] is a title that was never added
 *   from TMDB; [BulkBackfillState.noIsbnSkipped] is a book with no ISBN. Collapsing them into one
 *   `skipped` field would make the field name true of neither.
 *
 * What genuinely *is* shared between the two passes is shared: [com.hub.media.ui.BackfillViewModel]
 * runs both, [com.hub.media.core.network.RequestPacer] paces both, and neither pass owns a copy of
 * the other's lifecycle handling. #126's "do not fork `StatsRepository` per domain" constraint is
 * about the machinery, and the machinery is not forked; five key strings are not machinery.
 *
 * @property pendingMediaIds Media ids of films and shows this run has not yet resolved — the work
 *   queue [com.hub.media.features.media.domain.BulkTmdbBackfillUseCase.execute] consumes. Order is
 *   stable across a resume (title-ordered, films then shows, from the seed scan).
 * @property totalCandidates Titles that needed something *and* had a TMDB id when this run was first
 *   seeded. Fixed for the life of the run/resume chain, never recomputed — see
 *   [BulkBackfillState.totalCandidates] for why "312 of 480" stops meaning anything otherwise.
 * @property noTmdbIdSkipped Films and shows that were missing something but have no TMDB mapping, so
 *   nothing can be looked up for them at all. Computed once at seed time and never placed in
 *   [pendingMediaIds]: a hand-typed film is not a failure and retrying it forever would not make one
 *   appear in the catalogue. The per-title refresh (#136/#75) is the path for those.
 * @property updated Cumulative count of titles this chain has written something onto — a poster, a
 *   runtime, a synopsis, an episode's title.
 * @property nothingToFill Cumulative count of titles fully resolved with nothing to write: TMDB
 *   answered, and every gap it could have filled was either already filled or genuinely blank at the
 *   source. Kept distinct from [updated] so the summary can say "we looked and there is nothing
 *   there" rather than implying work was done.
 */
public data class TmdbBackfillState(
    public val pendingMediaIds: List<String>,
    public val totalCandidates: Int,
    public val noTmdbIdSkipped: Int,
    public val updated: Int,
    public val nothingToFill: Int,
)

private const val KEY_PENDING_MEDIA_IDS = "tmdb_backfill_pending_media_ids"
private const val KEY_TOTAL_CANDIDATES = "tmdb_backfill_total_candidates"
private const val KEY_NO_TMDB_ID_SKIPPED = "tmdb_backfill_no_tmdb_id_skipped"
private const val KEY_UPDATED = "tmdb_backfill_updated"
private const val KEY_NOTHING_TO_FILL = "tmdb_backfill_nothing_to_fill"
private const val PENDING_ID_SEPARATOR = ","

/**
 * One-shot fetch of the currently-resumable [TmdbBackfillState], or `null` if no TMDB backfill is in
 * progress (never started, or the last one ran to completion and was cleared).
 *
 * Key-presence is the signal, not a separate flag — see [BulkBackfillState]'s KDoc, which settles the
 * same question for the book pass.
 */
public suspend fun SettingsRepository.getTmdbBackfillState(): TmdbBackfillState? {
    val pendingRaw = getString(KEY_PENDING_MEDIA_IDS) ?: return null
    val pendingIds = if (pendingRaw.isBlank()) emptyList() else pendingRaw.split(PENDING_ID_SEPARATOR)
    return TmdbBackfillState(
        pendingMediaIds = pendingIds,
        totalCandidates = getInt(KEY_TOTAL_CANDIDATES) ?: pendingIds.size,
        noTmdbIdSkipped = getInt(KEY_NO_TMDB_ID_SKIPPED) ?: 0,
        updated = getInt(KEY_UPDATED) ?: 0,
        nothingToFill = getInt(KEY_NOTHING_TO_FILL) ?: 0,
    )
}

/**
 * Persists [state], overwriting whatever was there before. Called after **every single title** a run
 * processes, which matters more here than it does for books: a show is one request but hundreds of
 * episode rows, so a run killed mid-library has more to lose and a resume has more to save.
 */
public suspend fun SettingsRepository.saveTmdbBackfillState(state: TmdbBackfillState) {
    setString(KEY_PENDING_MEDIA_IDS, state.pendingMediaIds.joinToString(PENDING_ID_SEPARATOR))
    setInt(KEY_TOTAL_CANDIDATES, state.totalCandidates)
    setInt(KEY_NO_TMDB_ID_SKIPPED, state.noTmdbIdSkipped)
    setInt(KEY_UPDATED, state.updated)
    setInt(KEY_NOTHING_TO_FILL, state.nothingToFill)
}

/**
 * Removes every key this file owns, reverting [getTmdbBackfillState] to `null`. Called once a run
 * empties [TmdbBackfillState.pendingMediaIds] — leaving stale counters behind would right-pad a later
 * fresh run's seed with numbers from a completed, unrelated one.
 */
public suspend fun SettingsRepository.clearTmdbBackfillState() {
    clear(KEY_PENDING_MEDIA_IDS)
    clear(KEY_TOTAL_CANDIDATES)
    clear(KEY_NO_TMDB_ID_SKIPPED)
    clear(KEY_UPDATED)
    clear(KEY_NOTHING_TO_FILL)
}
