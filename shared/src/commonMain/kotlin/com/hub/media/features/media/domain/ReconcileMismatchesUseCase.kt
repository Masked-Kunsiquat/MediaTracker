package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getTmdbBackfillMismatches
import com.hub.media.features.settings.data.saveTmdbBackfillMismatches
import com.hub.media.features.tv.data.TVShowRepository
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ReconcileMismatches"

/**
 * One season's disagreement, with enough about the show attached to render a row (#123).
 *
 * @property showTitle The show's current title, read now rather than stored with the finding — a
 *   title the user has since corrected should display corrected.
 * @property coverImageHash The show's artwork, read now for the same reason [showTitle] is: a poster
 *   the backfill fetched *after* recording this finding should still appear. `null` for a show that
 *   has none, which the row renders as nothing rather than as a placeholder.
 * @property missingEpisodes How many rows "add the missing episodes" would create. `0` when the
 *   library holds more than TMDB lists, which is reported and never acted on.
 */
public data class MismatchReviewRow(
    public val mediaId: String,
    public val showTitle: String,
    public val seasonNumber: Int,
    public val localEpisodes: Int,
    public val providerEpisodes: Int,
    public val coverImageHash: String? = null,
) {
    public val missingEpisodes: Int get() = (providerEpisodes - localEpisodes).coerceAtLeast(0)

    /** `true` when TMDB lists more than the library holds — the only direction #123 offers to act on. */
    public val isUnderCount: Boolean get() = providerEpisodes > localEpisodes

    /**
     * `true` when the library holds no episodes at all for this season.
     *
     * Worth distinguishing because it reads differently: "add all 6" for a season recorded but never
     * filled in, against "add the 8 missing" for a partial one. Both real shapes — a library was
     * found holding one of each.
     */
    public val isEmptySeason: Boolean get() = localEpisodes == 0
}

/**
 * Reads back the season disagreements a backfill recorded, and acts on the safe half of them (#123).
 *
 * ### Adding only. Removal is not offered here and must not be added.
 * Settled on #123 before any of this was written, on two grounds:
 *
 * - Deleting an episode row takes its `watchedAt` with it, irrecoverably, and AGENTS.md §1 puts user
 *   data safety above shortcuts.
 * - **TMDB is not reliably right about counts.** #88 found Judy Justice reporting 446 episodes while
 *   its four seasons sum to 458. A screen offering to "correct" a local count *down* to a stale
 *   provider figure would be destroying real history to match a wrong number.
 *
 * An over-count is therefore surfaced and left alone. The show screen's own season dialog already
 * shrinks a season, already warns before it does, and is the right place for a decision the user is
 * making deliberately rather than accepting from a list.
 *
 * The guarantee is not this class's care: [TVShowRepository.addMissingEpisodes] routes to a DAO
 * method with no `DELETE` in it. That matters because the stored count can be stale — see that
 * function's KDoc for the deletion this would otherwise perform on a season grown since the finding
 * was recorded.
 */
public class ReconcileMismatchesUseCase(
    private val db: AppDatabase,
    private val tvShowRepository: TVShowRepository,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = AppLogger,
) {
    /**
     * Every recorded disagreement, newest scan first, with its show's current title.
     *
     * **Rows whose show no longer exists are dropped rather than rendered.** A finding outlives the
     * run that made it by design, so the show can have been deleted in between, and a row naming
     * nothing is worse than no row. They are also removed from storage as they are found, so the
     * list does not have to re-explain itself on every read.
     */
    public suspend fun review(): List<MismatchReviewRow> {
        val stored = settingsRepository.getTmdbBackfillMismatches()
        if (stored.isEmpty()) return emptyList()

        val shows = db.mediaItemDao().getAllByType(MediaType.TV_SHOW).associateBy { it.id }

        val (live, orphaned) = stored.partition { it.mediaId in shows }
        if (orphaned.isNotEmpty()) {
            logger.info(TAG) { "Dropping ${orphaned.size} finding(s) whose show no longer exists" }
            settingsRepository.saveTmdbBackfillMismatches(live)
        }

        return live.map {
            val show = shows.getValue(it.mediaId)
            MismatchReviewRow(
                mediaId = it.mediaId,
                showTitle = show.title,
                seasonNumber = it.seasonNumber,
                localEpisodes = it.localEpisodes,
                providerEpisodes = it.providerEpisodes,
                coverImageHash = show.coverImageHash,
            )
        }
    }

    /**
     * Creates the episode rows [row] says are missing, then forgets the finding.
     *
     * ### The count is re-derived, never trusted
     * [row] carries what was true when the backfill ran, which may be several actions ago. The
     * season is grown to [MismatchReviewRow.providerEpisodes] — the provider's figure, which does not
     * go stale the way the local count does — and
     * [TVShowRepository.addMissingEpisodes] fills only the gaps in it. A season the user has already
     * reconciled by hand therefore gains nothing and still succeeds, which is the honest outcome
     * rather than an error.
     *
     * ### The finding is dropped whether or not anything was created
     * Either the season now matches, or the user has changed it to something they meant. Both are
     * answers; neither is a reason to keep telling them. A later run re-derives the row if the
     * disagreement is somehow still real, so nothing is lost by forgetting it here — and the
     * alternative is a list that only ever grows.
     *
     * @return the number of episode rows created, which is `0` for an already-reconciled season.
     */
    public suspend fun addMissingEpisodes(row: MismatchReviewRow): Resource<Int> {
        if (!row.isUnderCount) {
            // Refused rather than silently no-oped: nothing should be able to reach this with an
            // over-count, and if something does, that is a bug worth surfacing rather than a write
            // worth attempting.
            return Resource.Error("This season already holds at least as many episodes as TMDB lists.")
        }

        return try {
            when (
                val result =
                    tvShowRepository.addMissingEpisodes(
                        mediaId = row.mediaId,
                        seasonNumber = row.seasonNumber,
                        episodeCount = row.providerEpisodes,
                    )
            ) {
                is Resource.Error -> result
                is Resource.Success -> {
                    forget(row)
                    logger.info(TAG) {
                        "Reconciled season ${row.seasonNumber}: ${result.data} episode(s) created"
                    }
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to reconcile season ${row.seasonNumber}" }
            Resource.Error("Failed to add the missing episodes: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /** Removes [row]'s finding from storage, keyed the way the backfill records them. */
    private suspend fun forget(row: MismatchReviewRow) {
        val remaining =
            settingsRepository.getTmdbBackfillMismatches().filterNot {
                it.mediaId == row.mediaId && it.seasonNumber == row.seasonNumber
            }
        settingsRepository.saveTmdbBackfillMismatches(remaining)
    }
}
