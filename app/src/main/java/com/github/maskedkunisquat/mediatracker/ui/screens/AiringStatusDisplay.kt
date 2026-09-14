package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.AiringStatus

/**
 * User-facing label for [AiringStatus] (#141 step 2's TV facts grid) -- the first place this column
 * is rendered anywhere in the app, so there was no existing mapping to reuse. Mirrors
 * [WatchStatusDisplay.kt]'s `displayLabel()` pattern exactly.
 */
@Composable
internal fun AiringStatus.displayLabel(): String =
    when (this) {
        AiringStatus.CONTINUING -> stringResource(R.string.airing_status_continuing)
        AiringStatus.ENDED -> stringResource(R.string.airing_status_ended)
        AiringStatus.CANCELLED -> stringResource(R.string.airing_status_cancelled)
    }
