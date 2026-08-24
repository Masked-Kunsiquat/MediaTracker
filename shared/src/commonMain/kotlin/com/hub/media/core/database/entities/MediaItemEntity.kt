package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * Universal metadata shared by every media item regardless of type (book, movie, TV show).
 * Domain-specific metadata lives in child tables (e.g. [BookDetailsEntity]) linked by [id]
 * per AGENTS.md §3.2.
 */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val type: MediaType,
    val title: String,
    val releaseYear: Int?,
    val purchasePrice: Double?,
    val createdAt: Instant,
    /**
     * The `<sha256>.jpg` filename returned by
     * [com.hub.media.core.storage.LocalImageStorageManager.saveImage] for this item's
     * cover/poster, or null if no cover has been downloaded/stored yet (AGENTS.md §4).
     */
    val coverImageHash: String? = null,
    /**
     * The provider's aggregate score for this title, normalised to 0.0-10.0, or `null` if unknown.
     *
     * On `media_items` rather than on each `*_details` table because every media type has one:
     * TMDB rates films and shows, Open Library and Google Books rate books. Per-episode scores live
     * on [EpisodeEntity.communityRating] instead, since an episode is not a media item.
     *
     * Deliberately **not** the user's own rating. That belongs to ROADMAP Task 10 (re-read
     * modelling), where it may attach to a read-through rather than to the title — a book can be
     * rated differently on a second pass — and the Goodreads importer already drops `My Rating`
     * with a documented recovery path that depends on Task 10 choosing that shape freely. This
     * column is the "did other people like it" number, kept so the two can be shown side by side
     * once the other exists.
     *
     * Normalised on write because providers disagree on scale (TMDB is out of 10, Goodreads out of
     * 5); a bare number whose scale is unrecorded cannot be compared with anything.
     */
    val communityRating: Double? = null,
)
