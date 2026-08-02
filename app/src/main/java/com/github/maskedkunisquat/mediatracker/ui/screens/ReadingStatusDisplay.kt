package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.ReadingStatus

/**
 * Maps [ReadingStatus] to its human-readable display label (ROADMAP Task 6 Phase C). Shared by
 * [BookDetailScreen] (the quick status chip/dropdown), `EditBookScreen` (the status selector), and
 * `LibraryScreen` (the filter row) so all three never drift out of sync — mirrors
 * [BookFormat.displayLabel][com.github.maskedkunisquat.mediatracker.ui.screens.displayLabel]'s
 * exhaustive-`when` pattern exactly.
 */
@Composable
internal fun ReadingStatus.displayLabel(): String = when (this) {
    ReadingStatus.TO_READ -> stringResource(R.string.reading_status_to_read)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.FINISHED -> stringResource(R.string.reading_status_finished)
    ReadingStatus.DNF -> stringResource(R.string.reading_status_dnf)
}
