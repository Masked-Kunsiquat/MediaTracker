package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Book-specific metadata for a [MediaItemEntity] of type [MediaType.BOOK].
 * [mediaId] is both the primary key (one-to-one with the parent) and the FK, so it is
 * already covered by a unique index — no extra index is required for the cascade delete.
 */
@Entity(
    tableName = "book_details",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookDetailsEntity(
    @PrimaryKey val mediaId: String,
    val isbn: String?,
    val format: BookFormat,
    val totalPages: Int?,
)
