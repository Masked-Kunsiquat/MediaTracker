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
)
