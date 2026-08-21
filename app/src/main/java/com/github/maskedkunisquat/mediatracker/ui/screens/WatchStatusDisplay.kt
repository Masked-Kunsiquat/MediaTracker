package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.WatchStatus

/**
 * User-facing label for a movie's status.
 *
 * Type-specific on purpose, unlike the library filter chips: a detail screen shows one item of
 * known type, so it can use that type's own vocabulary. A film is on a "Watchlist", not "To read".
 */
@Composable
internal fun WatchStatus.displayLabel(): String =
    when (this) {
        WatchStatus.WATCHLIST -> stringResource(R.string.watch_status_watchlist)
        WatchStatus.WATCHING -> stringResource(R.string.watch_status_watching)
        WatchStatus.WATCHED -> stringResource(R.string.watch_status_watched)
        WatchStatus.ABANDONED -> stringResource(R.string.watch_status_abandoned)
    }
