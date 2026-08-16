package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.BookFormat

/**
 * Maps [BookFormat] to its human-readable display label (e.g. [BookFormat.PHYSICAL] -> "Physical").
 * Shared by [BookDetailScreen] (read-only display) and `EditBookScreen` (the format picker,
 * ROADMAP Task 6 Phase A) so the two never drift out of sync — this `when` is exhaustive over
 * [BookFormat], so adding a future enum constant is a compile error here until a label is added.
 */
@Composable
internal fun BookFormat.displayLabel(): String =
    when (this) {
        BookFormat.PHYSICAL -> stringResource(R.string.book_format_physical)
        BookFormat.EBOOK -> stringResource(R.string.book_format_ebook)
        BookFormat.AUDIOBOOK -> stringResource(R.string.book_format_audiobook)
        BookFormat.PAPERBACK -> stringResource(R.string.book_format_paperback)
        BookFormat.HARDCOVER -> stringResource(R.string.book_format_hardcover)
    }
