package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * A single reading session for a book, decoupled from [MediaItemEntity] so re-reads and
 * DNF states are representable cleanly (AGENTS.md §3.4).
 *
 * [startUnit]/[endUnit] use a normalized [Double] rather than [Int] page numbers so both
 * physical page counts and e-reader percentage progress fit the same column (AGENTS.md §3.5).
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId"])],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val timestampStart: Instant,
    val timestampEnd: Instant,
    val durationSeconds: Long,
    val startUnit: Double,
    val endUnit: Double,
    val deltaPages: Int?,
    val notes: String?,
)
