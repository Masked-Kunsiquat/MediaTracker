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
)
