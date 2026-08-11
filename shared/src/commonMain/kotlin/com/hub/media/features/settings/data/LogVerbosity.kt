package com.hub.media.features.settings.data

import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Key the user-adjustable log verbosity preference is persisted under via [SettingsRepository]
 * (schema v4 `app_settings`, no new migration needed -- ROADMAP Task 15 Phase B2). Stored as the
 * [LogLevel] constant's [Enum.name] (`"DEBUG"`/`"INFO"`/`"WARN"`/`"ERROR"`), not its ordinal --
 * exactly [WeekStartDay]'s convention (see that file's KDoc for why: a name survives [LogLevel]
 * being reordered or gaining a new constant inserted between two existing ones, an ordinal would
 * not).
 */
private const val KEY_LOG_VERBOSITY = "log_verbosity"

/**
 * Reactive current log-verbosity preference (ROADMAP Task 15 Phase B2: "User-adjustable verbosity
 * ... with a sane (not chatty) default").
 *
 * ### Default: [LogLevel.WARN]
 * Deliberately the same release-safe level [AppLogger] itself defaults to before it is configured
 * (see that object's KDoc) -- a chattier default (`DEBUG`/`INFO`) would let a user who never opens
 * Settings silently blow through [com.hub.media.core.storage.MAX_LOG_FILE_SIZE_BYTES]'s cap and
 * capture far more than intended, on *every* build type, not just release. Never emits `null`:
 * like [observeWeekStartDay], a verbosity preference always has a meaningful value to fall back to,
 * so this wraps [SettingsRepository.observeString] rather than exposing its raw nullable form. A
 * stored value that no longer maps to a [LogLevel] constant (e.g. written by a future app version,
 * or corrupted) is treated identically to "never set" -- the same malformed-value rule
 * [SettingsRepository.observeInt]/[SettingsRepository.observeBoolean] already document, applied
 * here the same way [WeekStartDay]'s wrapper applies it.
 *
 * ### This value alone does not change what gets logged
 * Reading (or observing) this preference has no effect on [AppLogger] by itself -- [AppLogger]
 * applies its own [LogLevel] threshold *before* a call ever reaches a sink, so this setting has to
 * be actively pushed into [AppLogger.setMinLevel] to matter at all. See that function's KDoc for
 * the exact call the app module is expected to make (collect this `Flow` from a process-scoped
 * coroutine and call [AppLogger.setMinLevel] on every emission) and for which of the persisted
 * setting vs. the build-type bootstrap default wins once that wiring is in place.
 */
public fun SettingsRepository.observeLogVerbosity(): Flow<LogLevel> =
    observeLogVerbosityOrNull().map { it ?: DEFAULT_LOG_VERBOSITY }

/**
 * Same preference as [observeLogVerbosity], but preserving the distinction between "the user
 * explicitly chose a level" and "no choice has ever been made" (`null`, also the result of a
 * malformed stored value).
 *
 * ### Why that distinction has to survive
 * Collapsing it to a plain default is what the UI wants, but it is wrong for the [AppLogger]
 * wiring, and subtly so. `MediaTrackerApplication.onCreate` bootstraps the threshold from
 * `BuildConfig.DEBUG` -- [LogLevel.DEBUG] on a debug build. If the never-set case arrived here as
 * [DEFAULT_LOG_VERBOSITY] ([LogLevel.WARN]) rather than `null`, that value would immediately
 * overwrite the bootstrap, and **every debug build would fall silent a moment after startup** --
 * dropping `DEBUG`/`INFO` that the developer never asked to lose, purely because they had not
 * visited a Settings screen. The build-type default would be dead code in the only build type it
 * was written for.
 *
 * So the two sources compose rather than one blindly winning: an explicit choice always wins (that
 * is the entire point of Phase B2 -- picking [LogLevel.DEBUG] on a *release* build to diagnose
 * something is exactly what a sink-side filter could never deliver, since [AppLogger] drops the
 * call before any sink sees it), and `null` leaves the build-type bootstrap in place. Concretely,
 * the app module wires `persisted ?: buildTypeDefault`, not `persisted`.
 */
public fun SettingsRepository.observeLogVerbosityOrNull(): Flow<LogLevel?> =
    observeString(KEY_LOG_VERBOSITY).map { it.toLogLevelOrNull() }

/** One-shot fetch of the current log-verbosity preference; see [observeLogVerbosity] for the default rule. */
public suspend fun SettingsRepository.getLogVerbosity(): LogLevel =
    getString(KEY_LOG_VERBOSITY).toLogLevelOrNull() ?: DEFAULT_LOG_VERBOSITY

/** Persists [value] as the new log-verbosity preference, under [KEY_LOG_VERBOSITY]. */
public suspend fun SettingsRepository.setLogVerbosity(value: LogLevel) {
    setString(KEY_LOG_VERBOSITY, value.name)
}

/**
 * The level used when the user has never chosen one.
 *
 * [LogLevel.INFO] since ROADMAP Task 15 Phase C. It was [LogLevel.WARN], chosen when nothing in the
 * codebase emitted below WARN -- which meant a healthy app wrote nothing at all, and a log viewer
 * showing an empty list was read as a broken feature rather than as good news. Now that lifecycle
 * tracing exists, INFO is what makes the log answer "what was happening before this went wrong?"
 * instead of only "what fell over". The entries are counts and identifiers, never library content
 * (see [com.hub.media.core.util.Logger]'s identifier rule), and the file is capped and rotated, so
 * the cost of the extra level is bounded.
 *
 * Note this is the default for *the setting*, which is not the same thing as the level an
 * unconfigured process runs at: see [observeLogVerbosityOrNull] for how the never-set case defers
 * to the build-type bootstrap instead of overwriting it.
 */
public val DEFAULT_LOG_VERBOSITY: LogLevel = LogLevel.INFO

private fun String?.toLogLevelOrNull(): LogLevel? =
    this?.let { stored -> LogLevel.entries.firstOrNull { entry -> entry.name == stored } }
