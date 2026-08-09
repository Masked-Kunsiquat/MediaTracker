package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.util.LogLevel

/**
 * Maps [LogLevel] to its human-readable display label (ROADMAP Task 15 Phase B2). Mirrors
 * [WeekStartDay.displayLabel]'s exhaustive-`when` pattern, so a future [LogLevel] constant is a
 * compile error here until a label is added.
 *
 * The labels deliberately are not the raw enum names: "Warnings"/"Errors only" say what a user
 * gets, whereas WARN/ERROR say what a developer calls it.
 */
@Composable
internal fun LogLevel.displayLabel(): String = when (this) {
    LogLevel.DEBUG -> stringResource(R.string.log_level_debug)
    LogLevel.INFO -> stringResource(R.string.log_level_info)
    LogLevel.WARN -> stringResource(R.string.log_level_warn)
    LogLevel.ERROR -> stringResource(R.string.log_level_error)
}
