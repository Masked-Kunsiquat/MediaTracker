package com.hub.media.features.settings.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A yearly reading goal (#219): [books] to finish in [year].
 *
 * Stored with the year it was set for, so a goal quietly stops applying when the year turns -- the
 * Stats screen shows only a goal for the current year, and January starts fresh with nothing to
 * clear. Included in archive exports (`EXPORTABLE_SETTING_KEYS`): unlike the remembered stats
 * period, it is something the user entered, not a view preference.
 */
public data class ReadingGoal(
    val year: Int,
    val books: Int,
)

internal const val KEY_READING_GOAL: String = "reading_goal"

/** The largest goal accepted, matching the dialog's three-digit field. */
public const val MAX_READING_GOAL: Int = 999

/** The stored goal, or `null` when none is stored or the stored value is malformed or out of range. */
public fun SettingsRepository.observeReadingGoal(): Flow<ReadingGoal?> =
    observeString(KEY_READING_GOAL).map {
        it?.toReadingGoalOrNull()
    }

/** Stores [books] as the goal for [year]. */
public suspend fun SettingsRepository.setReadingGoal(
    year: Int,
    books: Int,
) {
    setString(KEY_READING_GOAL, "$year:$books")
}

/** Removes the goal. */
public suspend fun SettingsRepository.clearReadingGoal() {
    clear(KEY_READING_GOAL)
}

/**
 * Parses `"<year>:<books>"`, with books in `1..`[MAX_READING_GOAL]. Shared with the archive import,
 * which must reject exactly what this reader would not accept.
 */
public fun String.toReadingGoalOrNull(): ReadingGoal? {
    val match = Regex("""^(\d{4}):(\d{1,3})$""").matchEntire(this) ?: return null
    val books = match.groupValues[2].toInt()
    return if (books in 1..MAX_READING_GOAL) ReadingGoal(match.groupValues[1].toInt(), books) else null
}
