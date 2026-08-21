package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * Movie-specific metadata for a [MediaItemEntity] of type [MediaType.MOVIE].
 * Per AGENTS.md §3.2, universal metadata lives in media_items; domain-specific
 * metadata lives here, linked by [mediaId].
 */
@Entity(
    tableName = "movie_details",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MovieDetailsEntity(
    /** 
     * The unique identifier for this movie, shared with the parent [MediaItemEntity].
     * MUST be a generated UUID string (AGENTS.md §3.1).
     */
    @PrimaryKey val mediaId: String,
    
    val director: String? = null,
    val runtimeMinutes: Int? = null,
    
    /**
     * Watch lifecycle status.
     */
    val status: WatchStatus = WatchStatus.TO_WATCH,
    
    /**
     * When this movie was last finished, or null.
     */
    val finishedAt: Instant? = null,
)

/**
 * Lifecycle states for watching a movie.
 */
enum class WatchStatus {
    TO_WATCH,
    WATCHING,
    FINISHED,
    ABANDONED
}
