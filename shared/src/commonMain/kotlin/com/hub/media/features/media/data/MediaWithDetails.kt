package com.hub.media.features.media.data

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MovieDetailsEntity

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

    /**
     * @property details Movie-specific metadata, or `null` in the same data-integrity edge case
     *   [Book.details] can be null in: the parent row exists without its detail row. Never produced
     *   by [com.hub.media.features.movies.data.MovieRepository.addMovie], which inserts both
     *   atomically.
     */
    public data class Movie(
        override val item: MediaItemEntity,
        val details: MovieDetailsEntity?,
    ) : MediaWithDetails()

    public data class TVShow(
        override val item: MediaItemEntity,
    ) : MediaWithDetails()
}
