package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.ui.LibraryStatusFilter

/**
 * User-facing label for a library filter chip.
 *
 * Separate from `ReadingStatus.displayLabel()` on purpose: those strings ("To read", "Reading")
 * are book-specific and still correct on the book detail screen, but a chip that also matches
 * films cannot call a watchlisted movie "To read". These read correctly for both.
 */
@Composable
internal fun LibraryStatusFilter.filterLabel(): String =
    when (this) {
        LibraryStatusFilter.NOT_STARTED -> stringResource(R.string.library_filter_not_started)
        LibraryStatusFilter.IN_PROGRESS -> stringResource(R.string.library_filter_in_progress)
        LibraryStatusFilter.FINISHED -> stringResource(R.string.library_filter_finished)
        LibraryStatusFilter.ABANDONED -> stringResource(R.string.library_filter_abandoned)
    }
