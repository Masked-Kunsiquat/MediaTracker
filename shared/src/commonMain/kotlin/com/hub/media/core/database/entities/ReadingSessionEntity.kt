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
 *
 * ### [durationSeconds] nullability (schema v2, ROADMAP Task 5 pre-phase)
 * Schema v1 (frozen as of `v0.1.0`, AGENTS.md §8) shipped this column `NOT NULL`. Backlogged
 * manual sessions don't always have a known duration, but storing `0` as a stand-in for
 * "unknown" would collide with the legitimate 0-second-session edge case (a real, valid input —
 * see `ReadingSessionRepositoryTest.logSession_zeroSeconds_succeeds`) and silently corrupt any
 * future time-read stat that sums durations. `null` unambiguously means "duration unknown, not
 * counted"; `0` still unambiguously means "a real zero-length session." Timer-backed sessions
 * ([com.hub.media.features.books.timer.ReadingTimer]) always produce a duration and never pass
 * `null` here — only the manual-entry path may omit it. See `Migration_1_2` (`Migrations.kt`)
 * for the schema-v1-to-v2 table rebuild this rename required.
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
    val durationSeconds: Long?,
    val startUnit: Double,
    val endUnit: Double,
    val deltaPages: Int?,
    val notes: String?,
)
