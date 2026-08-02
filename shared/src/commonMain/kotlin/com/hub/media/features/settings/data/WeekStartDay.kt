package com.hub.media.features.settings.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * Which day of the calendar week "this week" starts on, driving
 * [com.hub.media.features.stats.data.StatsRepository.thisWeekBounds] (ROADMAP Task 7 Phase B).
 *
 * [MONDAY] is ISO-8601's convention and matches the app's pre-Phase-B hardcoded behavior exactly —
 * a user who never opens Settings sees no change. [SUNDAY] is the US-conventional alternative. Only
 * these two values are modeled deliberately: every locale's week start in practice is one of these
 * two days, and the ROADMAP's decided semantics are explicit that *only* the week's start day
 * becomes configurable (periods otherwise stay calendar periods) — so a two-way enum, not an
 * arbitrary [kotlinx.datetime.DayOfWeek], is the whole surface here. The Settings screen presents
 * exactly these two choices, never a seven-way picker.
 *
 * @property isoDayNumber The [kotlinx.datetime.DayOfWeek.isoDayNumber] (1=Monday..7=Sunday) this
 *   value corresponds to. [com.hub.media.features.stats.data.StatsRepository.thisWeekBounds] uses
 *   it to compute how many days to walk backward from "today" to reach the start of its containing
 *   week, for either start-day convention uniformly.
 */
public enum class WeekStartDay(public val isoDayNumber: Int) {
    MONDAY(DayOfWeek.MONDAY.isoDayNumber),
    SUNDAY(DayOfWeek.SUNDAY.isoDayNumber),
}

/**
 * Key [WeekStartDay] is persisted under via [SettingsRepository] (schema v4 `app_settings`, no new
 * migration needed — see [com.hub.media.core.database.entities.AppSettingEntity]'s KDoc). Stored as
 * the enum constant's [Enum.name] (`"MONDAY"`/`"SUNDAY"`), not its ordinal: a name survives the enum
 * being reordered or having a third value inserted between the two existing ones later, whereas an
 * ordinal would silently shift meaning. This is a deliberate refinement over the ordinal-`Int`
 * approach [SettingsRepository]'s own Phase A KDoc speculated a future setting like this one might
 * use — [SettingsRepository.observeString]/[SettingsRepository.setString] already exist and cost
 * nothing extra to use here instead.
 */
private const val KEY_WEEK_START_DAY = "week_start_day"

/**
 * Reactive current [WeekStartDay]. Defaults to [WeekStartDay.MONDAY] — ISO-8601, the app's
 * pre-Phase-B hardcoded behavior — whenever [KEY_WEEK_START_DAY] was never set (nothing changes for
 * a user who never opens Settings) or holds a value that no longer maps to a [WeekStartDay]
 * constant (treated identically to unset, mirroring [SettingsRepository.observeInt]'s/
 * [SettingsRepository.observeBoolean]'s malformed-value rule — this accessor can only ever have
 * been written by [setWeekStartDay] below, but the same defensive rule is applied for consistency).
 * Never emits `null`: unlike the generic accessors this wraps, a week-start-day preference always
 * has a meaningful value to fall back to.
 */
public fun SettingsRepository.observeWeekStartDay(): Flow<WeekStartDay> =
    observeString(KEY_WEEK_START_DAY).map { it.toWeekStartDayOrDefault() }

/** One-shot fetch of the current [WeekStartDay]; see [observeWeekStartDay] for the default rule. */
public suspend fun SettingsRepository.getWeekStartDay(): WeekStartDay =
    getString(KEY_WEEK_START_DAY).toWeekStartDayOrDefault()

/** Persists [value] as the week-start-day preference, under [KEY_WEEK_START_DAY]. */
public suspend fun SettingsRepository.setWeekStartDay(value: WeekStartDay) {
    setString(KEY_WEEK_START_DAY, value.name)
}

private fun String?.toWeekStartDayOrDefault(): WeekStartDay =
    this?.let { stored -> WeekStartDay.entries.firstOrNull { entry -> entry.name == stored } } ?: WeekStartDay.MONDAY
