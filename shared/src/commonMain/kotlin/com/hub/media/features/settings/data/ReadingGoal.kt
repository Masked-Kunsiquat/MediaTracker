package com.hub.media.features.settings.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A yearly reading goal (#219): [books] to finish in [year].
 *
 * Every year's goal is kept (#221): the setting holds a list, `"2025:20,2026:12"`, so setting next
 * year's goal no longer erases this year's, and the year in review can show a past year's result.
 * The Stats screen reads only the current year's entry, so January still starts with no goal.
 * Included in archive exports (`EXPORTABLE_SETTING_KEYS`): unlike the remembered stats period, it is
 * something the user entered, not a view preference.
 */
public data class ReadingGoal(
    val year: Int,
    val books: Int,
)

internal const val KEY_READING_GOAL: String = "reading_goal"

/** The largest goal accepted, matching the dialog's three-digit field. */
public const val MAX_READING_GOAL: Int = 999

/**
 * Every year's goal, by year; empty when none is stored, or the stored list is malformed. A value
 * stored before #221 -- a single `"2026:12"` -- is a one-year list, so nothing needs migrating.
 */
public fun SettingsRepository.observeReadingGoals(): Flow<Map<Int, Int>> =
    observeString(KEY_READING_GOAL).map { it?.toReadingGoalsOrNull().orEmpty() }

/** [year]'s goal, or `null` when none was set for it. */
public fun SettingsRepository.observeReadingGoal(year: Int): Flow<ReadingGoal?> =
    observeReadingGoals().map { goals -> goals[year]?.let { ReadingGoal(year, it) } }

/**
 * Serializes every write to the goal list. A per-year change reads the whole list, edits one entry
 * and writes it back, so two overlapping changes -- or one overlapping an archive import -- could each
 * write a list missing the other's year (#228 review). Process-wide, since the setting is.
 */
private val goalWrites = Mutex()

/** Stores [books] as [year]'s goal, keeping every other year's. */
public suspend fun SettingsRepository.setReadingGoal(
    year: Int,
    books: Int,
) {
    goalWrites.withLock { writeGoals(storedGoals() + (year to books)) }
}

/** Removes [year]'s goal, keeping every other year's; the last one gone clears the setting. */
public suspend fun SettingsRepository.clearReadingGoal(year: Int) {
    goalWrites.withLock { writeGoals(storedGoals() - year) }
}

/** Replaces every goal with [goals], in year order -- an archive import restoring the whole list. */
public suspend fun SettingsRepository.setReadingGoals(goals: Map<Int, Int>) {
    goalWrites.withLock { writeGoals(goals) }
}

private suspend fun SettingsRepository.writeGoals(goals: Map<Int, Int>) {
    if (goals.isEmpty()) {
        clear(KEY_READING_GOAL)
    } else {
        setString(KEY_READING_GOAL, goals.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" })
    }
}

private suspend fun SettingsRepository.storedGoals(): Map<Int, Int> =
    getString(KEY_READING_GOAL)?.toReadingGoalsOrNull().orEmpty()

/**
 * Parses `"<year>:<books>,<year>:<books>,…"` (#221), all or nothing: every entry well formed, books
 * in `1..`[MAX_READING_GOAL], and no year twice. Anything else is `null`, read as no goals -- as a
 * malformed single value always was. Shared with the archive import, which must reject exactly what
 * this reader would not accept.
 */
public fun String.toReadingGoalsOrNull(): Map<Int, Int>? {
    val goals = mutableMapOf<Int, Int>()
    for (entry in split(",")) {
        val goal = entry.toReadingGoalOrNull() ?: return null
        if (goals.put(goal.year, goal.books) != null) return null
    }
    return goals
}

/** Parses one `"<year>:<books>"` entry, with books in `1..`[MAX_READING_GOAL]. */
public fun String.toReadingGoalOrNull(): ReadingGoal? {
    val match = Regex("""^(\d{4}):(\d{1,3})$""").matchEntire(this) ?: return null
    val books = match.groupValues[2].toInt()
    return if (books in 1..MAX_READING_GOAL) ReadingGoal(match.groupValues[1].toInt(), books) else null
}
