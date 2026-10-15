package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.guardResource
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single path every tick takes (#131): one episode, a catch-up, or a whole season. The rules it
 * enforces are in `docs/superpowers/specs/2026-10-12-tick-with-date-design.md`; the date arithmetic
 * is [resolveWatchedAt].
 *
 * - When [execute]'s `targetIsExplicit` is true, the **first** id is the episode the user ticked by
 *   hand and is written even if it has not aired; every other id (the catch-up, or a whole season
 *   when `targetIsExplicit` is false) is written only if it has aired, so a bulk mark can never stamp
 *   an episode that does not exist yet.
 * - Episodes already watched are skipped and keep their date.
 * - **Nothing is written unless every time resolves**: an `OnAirDate` over an episode with no past
 *   air date is an error before any row changes. The write itself is one transaction.
 *
 * @param zone The device's zone, read per call so a zone change while the app runs is honoured.
 */
public class MarkEpisodesWatchedUseCase(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val logger: Logger = AppLogger,
) {
    /** This use case's "now" -- view models use it for [isAired] so the whole prompt shares one clock. */
    public fun now(): Instant = clock.now()

    /** @return How many episodes were written. */
    public suspend fun execute(
        episodeIds: List<String>,
        watchedWhen: WatchedWhen,
        targetIsExplicit: Boolean = true,
    ): Resource<Int> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to mark watched",
            logMessage = { "Failed to mark ${episodeIds.size} episode(s) watched" },
        ) {
            val now = clock.now()
            // Chunked under SQLite's historical 999-variable limit: a catch-up across a long-running
            // daily show can name hundreds of episodes in one request.
            val byId =
                episodeIds
                    .chunked(MAX_IDS_PER_QUERY)
                    .flatMap { db.tvWriteDao().getByIds(it) }
                    .associateBy { it.id }
            val wanted =
                episodeIds.withIndex().mapNotNull { (index, id) ->
                    byId[id]?.takeIf { e ->
                        e.watchedAt == null && ((targetIsExplicit && index == 0) || isAired(e.airDate, now))
                    }
                }
            val stamps = wanted.associate { it.id to resolveWatchedAt(watchedWhen, it.airDate, now, zone()) }
            val resolved = stamps.mapNotNull { (id, at) -> at?.let { id to it } }.toMap()
            if (resolved.size != stamps.size) {
                Resource.Error("An episode has no past air date to mark it watched on")
            } else {
                Resource.Success(db.tvWriteDao().markEpisodesWatchedAt(resolved))
            }
        }

    private companion object {
        const val TAG = "MarkEpisodesWatched"
        const val MAX_IDS_PER_QUERY = 900
    }
}
