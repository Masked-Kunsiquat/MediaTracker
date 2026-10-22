package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pass through a show (#231): watch-through #1, #2... Its episodes' ticks are the viewings that
 * carry its id. Watching, Finished and Up to date are derived from those ticks, never stored, as a
 * show's state always was; only [abandoned] is stored, because giving up is a decision no tick count
 * expresses. A show always has #1.
 *
 * @property rating Half-steps 1..10 (`isValidRating`), or `null` for unrated.
 */
@Entity(
    tableName = "watch_throughs",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId", "number"], unique = true)],
)
public data class WatchThroughEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val number: Int,
    val abandoned: Boolean = false,
    val rating: Int? = null,
)
