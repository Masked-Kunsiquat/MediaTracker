package com.hub.media.features.books.domain

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode

/**
 * What the book form holds (#214): the values Edit book saves, and the values a looked-up book is
 * added with once its review is saved. One type for both, so the form, the edit and the add cannot
 * disagree about what a book's editable fields are.
 *
 * In this package rather than `ui` because [AddBookByIsbnUseCase] saves a reviewed book from it, and
 * the domain does not reach up into the UI layer.
 *
 * Numbers are already parsed; `null` means "unknown". Range rules stay in [BookMetadataValidation].
 */
public data class BookFormValues(
    val title: String,
    val releaseYear: Int?,
    val purchasePrice: Double?,
    val totalPages: Int?,
    val format: BookFormat,
    val status: ReadingStatus,
    val trackingMode: TrackingMode,
)
