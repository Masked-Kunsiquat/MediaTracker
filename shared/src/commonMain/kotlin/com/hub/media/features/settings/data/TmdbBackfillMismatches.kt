package com.hub.media.features.settings.data

/**
 * One season where the library and TMDB disagree about how many episodes exist (#123), found by a
 * library-wide backfill run.
 *
 * The cross-show form of [com.hub.media.features.tv.domain.SeasonCountMismatch], which describes a
 * season without saying which show it belongs to because its own caller only ever looks at one.
 *
 * @property mediaId The show this season belongs to.
 * @property seasonNumber Always `>= 1`. Specials are never fetched, so they are never compared —
 *   #88's rule, inherited from the pass that produces these.
 * @property localEpisodes Episode rows the library holds for this season.
 * @property providerEpisodes Episodes TMDB listed for it.
 */
public data class ShowSeasonMismatch(
    public val mediaId: String,
    public val seasonNumber: Int,
    public val localEpisodes: Int,
    public val providerEpisodes: Int,
) {
    /** `true` when TMDB lists more than the library holds — the only direction #123 offers to act on. */
    public val isUnderCount: Boolean get() = providerEpisodes > localEpisodes

    /** How many rows "add the missing episodes" would create. Zero unless [isUnderCount]. */
    public val missingEpisodes: Int get() = (providerEpisodes - localEpisodes).coerceAtLeast(0)
}

/**
 * Mismatches found by the most recent backfill run, stored in `app_settings` beside
 * [TmdbBackfillState].
 *
 * ### Why these are persisted rather than held on the progress object
 * Settled on #123. A pass over a large library is exactly the case where someone starts it and
 * navigates away, and findings that lived only in [com.hub.media.features.media.domain.TmdbBackfillProgress]
 * would be discarded on screen close — losing them precisely when the run was long enough for it to
 * matter.
 *
 * ### They outlive the resume state deliberately
 * [clearTmdbBackfillState] runs the moment a pass finishes, because there is nothing left to resume.
 * These are **not** cleared with it: a completed run is exactly when its findings become worth
 * reading. They are cleared when the *next* run seeds instead, since a fresh scan re-derives them
 * and stale entries would otherwise accumulate against shows that have since been fixed.
 *
 * That means a reader must not go through
 * [com.hub.media.features.media.domain.BulkTmdbBackfillUseCase.peekProgress] to find them — that
 * returns `null` once a run completes. Read them directly.
 *
 * ### Encoding
 * [SettingsRepository] stores flat `String`/`Int`/`Boolean` values and AGENTS.md §5 rules out adding
 * a serialization dependency for this, so each record is `mediaId:season:local:provider` and records
 * are joined with commas — the same reasoning
 * [TmdbBackfillState.pendingMediaIds] uses for its comma-joined ids, extended by one delimiter
 * because a record here has four fields rather than one. Neither character can occur in the data:
 * ids come from [com.hub.media.core.util.newId] and the other three are integers.
 *
 * Size is not capped. A record is roughly 45 characters, so even a library with a few hundred
 * disagreeing seasons stays well inside what a SQLite `TEXT` column holds comfortably, and capping
 * would mean silently dropping findings — the failure mode this whole feature exists to remove.
 */
private const val KEY_MISMATCHES = "tmdb_backfill_mismatches"
private const val RECORD_SEPARATOR = ","
private const val FIELD_SEPARATOR = ":"
private const val FIELD_COUNT = 4

/**
 * Every mismatch the most recent run recorded, or an empty list if there are none (or none has ever
 * run).
 *
 * **A record that does not parse is skipped rather than failing the read.** This is hand-rolled
 * parsing of a value that could have been written by an older build or truncated by something going
 * wrong, and the alternative is a Settings screen that cannot open because one row is malformed.
 * Losing one finding is recoverable — the next run re-derives it.
 */
public suspend fun SettingsRepository.getTmdbBackfillMismatches(): List<ShowSeasonMismatch> {
    val raw = getString(KEY_MISMATCHES)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return raw.split(RECORD_SEPARATOR).mapNotNull { record ->
        val parts = record.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return@mapNotNull null
        val seasonNumber = parts[1].toIntOrNull() ?: return@mapNotNull null
        val local = parts[2].toIntOrNull() ?: return@mapNotNull null
        val provider = parts[3].toIntOrNull() ?: return@mapNotNull null
        if (parts[0].isBlank()) return@mapNotNull null
        ShowSeasonMismatch(
            mediaId = parts[0],
            seasonNumber = seasonNumber,
            localEpisodes = local,
            providerEpisodes = provider,
        )
    }
}

/** Replaces the stored set with [mismatches]. An empty list clears the key rather than storing "". */
public suspend fun SettingsRepository.saveTmdbBackfillMismatches(mismatches: List<ShowSeasonMismatch>) {
    if (mismatches.isEmpty()) {
        clearTmdbBackfillMismatches()
        return
    }
    setString(
        KEY_MISMATCHES,
        mismatches.joinToString(RECORD_SEPARATOR) {
            listOf(it.mediaId, it.seasonNumber, it.localEpisodes, it.providerEpisodes)
                .joinToString(FIELD_SEPARATOR)
        },
    )
}

/** Forgets every recorded mismatch. Called when a fresh run seeds, never when one finishes. */
public suspend fun SettingsRepository.clearTmdbBackfillMismatches() {
    clear(KEY_MISMATCHES)
}
