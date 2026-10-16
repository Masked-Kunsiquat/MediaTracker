package com.github.maskedkunisquat.mediatracker.ui.text

/**
 * A duration as "22h 48m", or "45m" under an hour (#219). Stats used to show "22:48", which reads
 * as a time of day rather than an amount of time. Seconds are dropped, not rounded: a total is
 * "at least this much", and 59 seconds is "0m".
 */
fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
