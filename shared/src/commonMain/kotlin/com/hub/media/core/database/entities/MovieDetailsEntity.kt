package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * Movie-specific metadata for a [MediaItemEntity] of type [MediaType.MOVIE] (schema v6, ROADMAP
 * Task 13 Phase A). Deliberately the same shape as [BookDetailsEntity]: [mediaId] is both the
 * primary key (one-to-one with the parent) and the FK, so it is already covered by a unique index
 * and needs no extra one for the cascade delete.
 *
 * Title, release year, purchase price and poster hash all live on [MediaItemEntity] and are
 * **not** duplicated here — that split is the whole point of the Issue #67 polymorphic model.
 *
 * @property runtimeMinutes Length in minutes, or `null` for "unknown". Never `0` as a stand-in:
 *   the same nulls-vs-zeros rule [ReadingSessionEntity.durationSeconds] was rebuilt in schema v2
 *   to honour, for the same reason — a `0` here would silently corrupt any future total-watch-time
 *   stat that sums runtimes.
 * @property status Viewing lifecycle. See [WatchStatus]'s KDoc for why this is a separate enum
 *   from [ReadingStatus] rather than a generalization of it.
 * @property watchedAt When [status] most recently became [WatchStatus.WATCHED], or `null`.
 *   Mirrors [BookDetailsEntity.finishedAt], including its rule that re-saving an already-watched
 *   film must not bump the timestamp.
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
public data class MovieDetailsEntity(
    @PrimaryKey val mediaId: String,
    val runtimeMinutes: Int? = null,
    val status: WatchStatus = WatchStatus.WATCHLIST,
    val watchedAt: Instant? = null,
)
