package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.TrackingMode

/**
 * Maps [TrackingMode] to its human-readable display label (ROADMAP Task 7 Phase A). Mirrors
 * [BookFormat.displayLabel][com.github.maskedkunisquat.mediatracker.ui.screens.displayLabel]'s
 * exhaustive-`when` pattern exactly, so a future [TrackingMode] constant is a compile error here
 * until a label is added.
 */
@Composable
internal fun TrackingMode.displayLabel(): String =
    when (this) {
        TrackingMode.PAGES -> stringResource(R.string.tracking_mode_pages)
        TrackingMode.PERCENT -> stringResource(R.string.tracking_mode_percent)
    }
