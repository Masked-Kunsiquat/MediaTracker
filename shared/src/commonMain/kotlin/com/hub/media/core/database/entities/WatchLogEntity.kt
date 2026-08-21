package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * A single viewing event (schema v6, ROADMAP Task 13 Phase A) — the counterpart of
 * [ReadingSessionEntity], and decoupled from the media item for the same reason (AGENTS.md §3.4):
 * so re-watches are representable cleanly rather than collapsing into one "watched" flag.
 *
 * ### Relationship to [EpisodeEntity.watchedAt]
 * These are not redundant. [EpisodeEntity.watchedAt] answers "is this episode watched, and when
 * was it last watched" — the state the checklist renders and progress is counted from. A watch log
 * is an *event*: watching the same episode three times is three rows here and one unchanged
 * [EpisodeEntity.watchedAt]. Stats that count viewing activity read this table; the checklist does
 * not.
 *
 * @property mediaId The movie or show watched. Always set, including for an episode watch, so
 *   show-level queries need no join through [EpisodeEntity].
 * @property episodeId The specific episode, or `null` for a film. Cascades from [EpisodeEntity] so
 *   correcting a show's episode list cannot strand logs pointing at rows that no longer exist.
 * @property durationSeconds Watch duration, or `null` for "unknown" — never `0` as a stand-in.
 *   This is [ReadingSessionEntity.durationSeconds]'s hard-won rule (schema v2 existed to make that
 *   column nullable): `0` is a legitimate value and using it for "unknown" silently corrupts any
 *   stat that sums durations.
 */
@Entity(
    tableName = "watch_logs",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId"]), Index(value = ["episodeId"])],
)
public data class WatchLogEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val episodeId: String? = null,
    val watchedAt: Instant,
    val durationSeconds: Long? = null,
)
