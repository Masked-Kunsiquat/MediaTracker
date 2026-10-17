package com.hub.media.core.database.dao

import kotlin.math.roundToInt

/**
 * How many books were finished and how many were not (#219), for the did-not-finish rate. All time
 * only: a DNF has no date, so it cannot be split by period.
 */
data class FinishOutcomes(
    val finished: Int,
    val dnf: Int,
)

/**
 * The did-not-finish rate as a whole percent: DNF over finished plus DNF, rounded. `null` when there
 * is neither, which the screen shows as unknown rather than a 0% nobody earned.
 */
public fun FinishOutcomes.dnfPercent(): Int? {
    val decided = finished + dnf
    return if (decided == 0) null else (dnf * 100.0 / decided).roundToInt()
}
