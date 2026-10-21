package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * One read of a book (#230, ROADMAP Task 10). A book's reading sessions are grouped into numbered
 * reads, and each read has its own [status], dates and [rating], so a re-read is its own history
 * rather than a status flip that overwrites the first finish. A book's status is its latest read's
 * (highest [number]). Every book has at least one read; only read #1 may be [ReadingStatus.TO_READ].
 *
 * The foreign key targets [MediaItemEntity], like [ReadingSessionEntity]'s: deleting a book deletes its
 * reads, and a book whose `book_details` row is missing (the edge case `MediaWithDetails.Book.details`
 * allows) can still own reads, so its sessions always have one.
 *
 * @property number 1, 2, 3… per book, unique per [mediaId].
 * @property finishedAt When this read became [ReadingStatus.FINISHED]. It follows the rules
 *   `BookRepository.resolveFinishedAt` applies: set on the transition into Finished, kept when saved
 *   again while Finished, and cleared on the way out.
 * @property rating Half-steps 1..10 (7 = 3.5 stars), or `null` when unrated. Validated in code,
 *   since Room cannot express a `CHECK`.
 */
@Entity(
    tableName = "read_throughs",
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
data class ReadThroughEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val number: Int,
    val status: ReadingStatus,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val rating: Int? = null,
)
