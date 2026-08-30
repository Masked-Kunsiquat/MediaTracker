@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.TrackingMode
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Formats a seconds count as `H:MM:SS` (hours unpadded, minutes/seconds zero-padded). */
internal fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
}

/**
 * Formats [currentProgress] for display, keyed off the book's explicit
 * [com.hub.media.core.database.entities.TrackingMode] (ROADMAP Task 7 Phase A) rather than
 * inferring it from [totalPages] being non-null (the pre-Phase-A behavior) -- see that enum's KDoc
 * for why that inference was replaced: [totalPages] is now used purely as an optional denominator,
 * never as the mode signal itself.
 *
 * - [TrackingMode.PAGES][com.hub.media.core.database.entities.TrackingMode.PAGES]: page-style
 *   ("Page 142 / 350") when [totalPages] is also known, or a bare page number ("Page 142") when a
 *   book is explicitly tracked by page but has no recorded total (an edge case the old
 *   totalPages-only inference could never produce, since a null total always meant percent mode
 *   then -- now that the two fields are independent, this dialog must not mislabel a raw page
 *   number as a percentage).
 * - [TrackingMode.PERCENT][com.hub.media.core.database.entities.TrackingMode.PERCENT] or `null`
 *   (this book has no [BookDetailsEntity] row): percent-style ("37%").
 *
 * Returns null (nothing to show) when [currentProgress] itself is null, i.e. no session has ever
 * been logged.
 *
 * Note: This is a @Composable function that calls [stringResource] to fetch localized strings
 * from resources rather than using hardcoded string literals.
 */
@Composable
internal fun formatProgress(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
): String? {
    if (currentProgress == null) return null
    return when {
        trackingMode == TrackingMode.PAGES && totalPages != null ->
            stringResource(R.string.progress_page_format, currentProgress.roundToInt(), totalPages)
        trackingMode == TrackingMode.PAGES ->
            stringResource(R.string.progress_page_only_format, currentProgress.roundToInt())
        else -> stringResource(R.string.progress_percent_format, currentProgress.roundToInt())
    }
}

/**
 * Derives a `0f..1f` completion fraction for [ProgressSection]'s [LinearProgressIndicator], or
 * `null` when no fraction can be derived -- mirrors [formatProgress]'s own mode precedence so the
 * bar and its text label never disagree about what mode the book is in:
 * - [TrackingMode.PERCENT] (or `null`, no [BookDetailsEntity] row): [currentProgress] is already a
 *   0-100 percentage, so the fraction is simply `currentProgress / 100`.
 * - [TrackingMode.PAGES] with a known [totalPages]: `currentProgress / totalPages`.
 * - [TrackingMode.PAGES] with no known [totalPages]: `null` -- a bare page number has no
 *   denominator to visualize a fraction of (degrades to text-only, per [ProgressSection]'s KDoc).
 *
 * Clamped to `0f..1f` since a session's `endUnit` is user-entered and not validated against
 * `totalPages` at save time (a typo could otherwise overshoot the bar past 100%).
 */
internal fun progressFraction(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
): Float? {
    if (currentProgress == null) return null
    val fraction =
        when {
            trackingMode == TrackingMode.PAGES && totalPages != null && totalPages > 0 ->
                currentProgress / totalPages
            trackingMode == TrackingMode.PAGES -> return null
            else -> currentProgress / 100.0
        }
    return fraction.toFloat().coerceIn(0f, 1f)
}

/**
 * Formats [hour]/[minute] (a [androidx.compose.material3.TimePickerState]'s always-24h `hour` and
 * `minute`, Task4 Phase E) as a time-of-day string using [android.text.format.DateFormat], which
 * renders in the device's locale and respects its 12h/24h display preference -- the same
 * preference [android.text.format.DateFormat.is24HourFormat] supplies to the `TimePicker`'s
 * `is24Hour` at the call site. The wrapping date value is irrelevant; only the time-of-day is
 * ever read back out via the formatter.
 */
internal fun formatTimeOfDay(
    context: android.content.Context,
    hour: Int,
    minute: Int,
): String {
    val calendar =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
        }
    return android.text.format.DateFormat
        .getTimeFormat(context)
        .format(calendar.time)
}

/**
 * Converts [instant] to a local [java.time.LocalDateTime] (device timezone) -- this screen's one
 * Instant-to-local conversion, used for seeding [ManualSessionDialog]'s date/time pickers in edit
 * mode and for deriving the calendar day a session belongs to.
 *
 * Through `java.time` rather than `kotlinx-datetime`, which is a `shared`-module dependency not
 * exposed to this Android-only app module, and `java.time` is available unconditionally on this
 * project's `minSdk 28`.
 */
internal fun instantToLocalDateTime(instant: Instant): java.time.LocalDateTime =
    java.time.Instant
        .ofEpochMilli(instant.toEpochMilliseconds())
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()

internal val DATE_ONLY_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("MMM d, yyyy")

/**
 * Converts a [java.time.LocalDate] to the UTC-midnight epoch millis that Material 3's
 * `DatePickerState` represents dates as (a date picker selection is timezone-agnostic, always
 * anchored to midnight UTC, regardless of the device's local timezone).
 */
internal fun localDateToUtcMidnightMillis(date: java.time.LocalDate): Long =
    date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/** Formats a `DatePickerState.selectedDateMillis`-style UTC-midnight millis value for display. */
internal fun formatUtcMidnightMillis(utcMidnightMillis: Long?): String {
    if (utcMidnightMillis == null) return "-"
    val localDate =
        java.time.Instant
            .ofEpochMilli(utcMidnightMillis)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
    return DATE_ONLY_FORMATTER.format(localDate)
}

/**
 * Derives a [kotlin.time.Instant] end-of-session timestamp from a `DatePickerState`-style
 * UTC-midnight date millis plus an hour/minute of day, combined in the device's local timezone
 * (the inverse of [instantToLocalDateTime], through the same `java.time` conversion).
 * [hour]/[minute] are clamped to valid ranges so out-of-range typed input (e.g. "99") can't throw.
 */
internal fun deriveTimestampEnd(
    dateUtcMidnightMillis: Long,
    hour: Int,
    minute: Int,
): Instant {
    val localDate =
        java.time.Instant
            .ofEpochMilli(dateUtcMidnightMillis)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
    val zonedDateTime =
        localDate
            .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(java.time.ZoneId.systemDefault())
    return Instant.fromEpochMilliseconds(zonedDateTime.toInstant().toEpochMilli())
}
