package com.hub.media.features.media.data

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity

/**
 * Polymorphic representation of a media item with its type-specific details (ROADMAP Task 13 foundation).
 * Replaces the book-only [com.hub.media.features.books.data.BookWithDetails] per Issue #67.
 */
public sealed class MediaWithDetails {
    public abstract val item: MediaItemEntity

    public data class Book(
        override val item: MediaItemEntity,
        val details: BookDetailsEntity?,
    ) : MediaWithDetails()

    // Planned: Movie, TVShow
}
