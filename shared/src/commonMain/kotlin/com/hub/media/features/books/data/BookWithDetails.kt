package com.hub.media.features.books.data

import androidx.room.Embedded
import androidx.room.Relation
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity

/**
 * Joined view combining a [MediaItemEntity] with its [BookDetailsEntity].
 * Used for Flow-based reads that need complete book information per AGENTS.md §5.
 */
public data class BookWithDetails(
    @Embedded val mediaItem: MediaItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "mediaId",
    )
    val details: BookDetailsEntity?,
)
