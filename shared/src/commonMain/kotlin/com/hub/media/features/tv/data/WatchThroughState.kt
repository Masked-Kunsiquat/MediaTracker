package com.hub.media.features.tv.data

import com.hub.media.core.database.dao.RunSeasonRow
import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlin.time.Instant

/** Every counted episode ticked in the latest run (#231); a show with none is never complete. */
public fun isComplete(counted: TVProgressRow): Boolean =
    counted.totalEpisodes > 0 && counted.watchedEpisodes >= counted.totalEpisodes

/** What a watch-through reads as (#231), derived from its ticks like a show's state always was. */
public enum class RunState { NOT_STARTED, WATCHING, FINISHED, UP_TO_DATE, ABANDONED }

/** [run]'s state from the latest run's [counted] ticks; a still-airing show that is caught up is Up to date. */
public fun runState(
    run: WatchThroughEntity,
    counted: TVProgressRow,
    airingStatus: AiringStatus?,
): RunState =
    when {
        run.abandoned -> RunState.ABANDONED
        isComplete(counted) -> if (airingStatus == AiringStatus.CONTINUING) RunState.UP_TO_DATE else RunState.FINISHED
        counted.watchedEpisodes == 0 -> RunState.NOT_STARTED
        else -> RunState.WATCHING
    }

/** "Rate this watch-through?" opens when an action completes the latest run and it is unrated -- once. */
public fun asksToRateRun(
    wasComplete: Boolean,
    nowComplete: Boolean,
    rating: Int?,
): Boolean = !wasComplete && nowComplete && rating == null

/**
 * What one watch-through's viewings add up to (#231): its first and last viewing (its date line), how many
 * distinct episodes it covers, and that count per season (a folded run's summary).
 */
public data class RunSummary(
    val runId: String,
    val firstAt: Instant,
    val lastAt: Instant,
    val episodes: Int,
    val perSeason: Map<Int, Int>,
)

/** Folds [rows] into one [RunSummary] per run. */
public fun runSummaries(rows: List<RunSeasonRow>): Map<String, RunSummary> =
    rows.groupBy { it.runId }.mapValues { (runId, seasons) ->
        RunSummary(
            runId = runId,
            firstAt = seasons.minOf { it.firstAt },
            lastAt = seasons.maxOf { it.lastAt },
            episodes = seasons.sumOf { it.episodes },
            perSeason = seasons.associate { it.seasonNumber to it.episodes }.toSortedMap(),
        )
    }

/** Watch again is offered once a run is done with: finished, caught up, or given up on (#231). */
public fun RunState.offersWatchAgain(): Boolean =
    this == RunState.FINISHED || this == RunState.UP_TO_DATE || this == RunState.ABANDONED
