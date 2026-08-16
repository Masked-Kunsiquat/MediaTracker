package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.settings.data.WeekStartDay

/**
 * Maps [WeekStartDay] to its human-readable display label (ROADMAP Task 7 Phase B). Mirrors
 * [TrackingMode.displayLabel][com.github.maskedkunisquat.mediatracker.ui.screens.displayLabel]'s
 * exhaustive-`when` pattern exactly, so a future [WeekStartDay] constant is a compile error here
 * until a label is added.
 */
@Composable
internal fun WeekStartDay.displayLabel(): String =
    when (this) {
        WeekStartDay.MONDAY -> stringResource(R.string.settings_week_start_day_monday)
        WeekStartDay.SUNDAY -> stringResource(R.string.settings_week_start_day_sunday)
    }
