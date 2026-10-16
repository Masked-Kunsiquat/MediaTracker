package com.hub.media.features.stats.data

import kotlinx.datetime.LocalDate

/** How a yearly reading goal is doing (#219). */
public sealed interface GoalStatus {
    /** At least the whole books expected by today are finished. */
    public data object OnPace : GoalStatus

    /** [books] short of what today's share of the year expects. */
    public data class Behind(
        val books: Int,
    ) : GoalStatus

    /** The goal itself is met. */
    public data object Reached : GoalStatus
}

/**
 * @property goal Books the user set out to finish this year.
 * @property finished Books finished so far this year -- the same number the Year view shows.
 * @property expected Whole books expected by today if the goal were spread evenly over the year.
 */
public data class GoalProgress(
    val goal: Int,
    val finished: Int,
    val expected: Int,
    val status: GoalStatus,
)

/**
 * Where [finished] stands against [goal] on [today] (#219). The goal is spread evenly over the year's
 * days -- 366 in a leap year -- and "expected" rounds DOWN, so on 1 January nothing is expected yet
 * and nobody is "behind" on day one. "Behind" counts against the rounded-UP share, so being one book
 * short is reported as soon as that book is due rather than a day later. Whole-number arithmetic
 * throughout: no floating-point rounding can move someone from on pace to behind.
 */
public fun goalProgress(
    goal: Int,
    finished: Int,
    today: LocalDate,
): GoalProgress {
    val daysInYear = if (isLeapYear(today.year)) 366 else 365
    val dayOfYear = today.dayOfYear
    val expected = goal * dayOfYear / daysInYear
    val expectedRoundedUp = (goal * dayOfYear + daysInYear - 1) / daysInYear
    val status =
        when {
            finished >= goal -> GoalStatus.Reached
            finished >= expected -> GoalStatus.OnPace
            else -> GoalStatus.Behind(expectedRoundedUp - finished)
        }
    return GoalProgress(goal = goal, finished = finished, expected = expected, status = status)
}

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
