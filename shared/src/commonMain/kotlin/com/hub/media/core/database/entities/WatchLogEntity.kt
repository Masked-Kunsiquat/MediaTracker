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
 * ### Why [episodeId]'s foreign key is composite
 * [mediaId] is denormalized here — it is derivable from [episodeId] for a TV watch, but a film has
 * no episode, so the column has to exist regardless. Denormalization invites disagreement: with two
 * *independent* foreign keys, a row pairing show A's [mediaId] with an episode belonging to show B
 * satisfies both constraints individually and is still nonsense.
 *
 * So [episodeId] points at `episodes (id, mediaId)` as a **pair**, which makes that row
 * unrepresentable rather than merely discouraged. [EpisodeEntity] carries a unique index on those
 * two columns for exactly this purpose.
 *
 * The separate [mediaId] → `media_items` key is kept alongside it, and is not redundant: SQLite's
 * default `MATCH SIMPLE` semantics skip a composite foreign key entirely when any of its child
 * columns is `NULL`, so a film's row (with [episodeId] `null`) is checked *only* by that key. The
 * two together mean a film's media reference is validated and a TV row's pair is validated —
 * neither alone covers both cases.
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
            parentColumns = ["id", "mediaId"],
            childColumns = ["episodeId", "mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["mediaId"]),
        // Covers the composite foreign key's child columns. Without it Room warns of a full table
        // scan of `watch_logs` every time `episodes` is modified -- which is the cascade path, so
        // it would be hit on exactly the operation that touches the most rows. Indexing the pair
        // rather than `episodeId` alone loses nothing: a leftmost-prefix lookup on `episodeId`
        // still uses this index.
        Index(value = ["episodeId", "mediaId"]),
    ],
)
public data class WatchLogEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val episodeId: String? = null,
    val watchedAt: Instant,
    val durationSeconds: Long? = null,
)
