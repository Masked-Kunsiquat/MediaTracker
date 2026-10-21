package com.hub.media.features.media.data

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.features.books.data.TitleRating
import com.hub.media.features.books.data.readsRating
import com.hub.media.features.books.data.titleRating
import kotlin.time.Instant

/**
 * Polymorphic representation of a media item with its type-specific details (ROADMAP Task 13 foundation).
 * Replaces the book-only [com.hub.media.features.books.data.BookWithDetails] per Issue #67.
 */
public sealed class MediaWithDetails {
    public abstract val item: MediaItemEntity

    /**
     * @property reads The book's read-throughs, in any order (#230). Its [status], [finishedAt] and
     *   [rating] come from them -- the latest read (highest number) is the book's state.
     */
    public data class Book(
        override val item: MediaItemEntity,
        val details: BookDetailsEntity?,
        val reads: List<ReadThroughEntity> = emptyList(),
    ) : MediaWithDetails() {
        val latestRead: ReadThroughEntity? get() = reads.maxByOrNull { it.number }

        /** The latest read's status; To read for a book with no read at all. */
        val status: ReadingStatus get() = latestRead?.status ?: ReadingStatus.TO_READ

        val finishedAt: Instant? get() = latestRead?.finishedAt

        val rating: TitleRating? get() = readsRating(reads)
    }

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

    /**
     * @property details Show-level metadata, or `null` in the same data-integrity edge case
     *   [Movie.details] can be null in: the parent row exists without its detail row. Never
     *   produced by [com.hub.media.features.tv.data.TVShowRepository.addShow], which inserts both
     *   atomically.
     */
    public data class TVShow(
        override val item: MediaItemEntity,
        val details: TVDetailsEntity?,
        /** The show's watch-throughs (#231), in any order; the latest (highest number) is the current one. */
        val runs: List<WatchThroughEntity> = emptyList(),
    ) : MediaWithDetails() {
        val latestRun: WatchThroughEntity? get() = runs.maxByOrNull { it.number }

        /** Whether the current watch-through was given up on (#231) -- earlier runs keep their own flag. */
        val isAbandoned: Boolean get() = latestRun?.abandoned == true

        val rating: TitleRating? get() = titleRating(runs.sortedBy { it.number }.map { it.rating })
    }
}
