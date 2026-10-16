package com.hub.media.core.database.dao

/**
 * How many books were finished and how many were not (#219), for the did-not-finish rate. All time
 * only: a DNF has no date, so it cannot be split by period.
 */
data class FinishOutcomes(
    val finished: Int,
    val dnf: Int,
)
