package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * One episode of a [MediaType.TV_SHOW] (schema v6, ROADMAP Task 13 Phase A). The unit TV progress
 * is tracked in — see [TVDetailsEntity] for why no progress counter is stored alongside these
 * rows.
 *
 * ### Rows exist before their titles do
 * Phase C creates these by **quick-fill**: the user says "Season 1: 10 episodes" and ten rows are
 * generated with [title] `null`, to be ticked off individually. TMDB (Phase D) later backfills
 * [title] and [airDate] onto rows that already exist. A quick-filled episode is therefore a
 * perfectly normal row with unknown metadata, **not** a placeholder to be replaced — nothing here
 * marks it provisional, and Phase D must fill in place rather than delete and re-create, or it
 * would destroy the [watchedAt] state that is the entire point of the row.
 *
 * @property id UUID string, per AGENTS.md §3.1. A composite (mediaId, season, episode) key was
 *   rejected: episode numbering is user-supplied at quick-fill time and therefore correctable
 *   later, and a corrected number under a composite key would mean deleting and re-inserting the
 *   row — losing [watchedAt] exactly as above. A surrogate key makes renumbering an `UPDATE`.
 * @property seasonNumber 1-based. Specials/season 0 are representable; nothing here forbids it.
 * @property episodeNumber 1-based within [seasonNumber].
 * @property title Episode title, or `null` for "unknown" (the quick-fill default).
 * @property airDate Original air date, or `null` if unknown.
 * @property watchedAt When this episode was watched, or `null` if unwatched. **Watched state and
 *   its timestamp are one column deliberately**: a separate boolean could disagree with the
 *   timestamp, and there is no meaningful state where an episode is watched at no time.
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Unique so quick-fill cannot generate the same episode twice (e.g. a user correcting a
        // season's count from 10 to 12 must add two rows, never re-create all twelve).
        Index(value = ["mediaId", "seasonNumber", "episodeNumber"], unique = true),
        // Redundant on its own -- `id` is already the primary key, so this pair cannot repeat --
        // but SQLite requires a composite FK's parent columns to carry a unique index, and
        // [WatchLogEntity] points (episodeId, mediaId) here to guarantee a watch log cannot pair
        // one show's id with another show's episode. See that entity's KDoc.
        Index(value = ["id", "mediaId"], unique = true),
    ],
)
public data class EpisodeEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val airDate: Instant? = null,
    val watchedAt: Instant? = null,
    /**
     * This episode's length in minutes, or `null` for "not known yet" — which is every
     * quick-filled row, since the user is asked for a count and not for runtimes.
     *
     * Per episode rather than per show: a show's episode lengths are not uniform (pilots and
     * finales routinely run long, and TMDB reports `episode_run_time` as an *array* on the show
     * for exactly that reason). Any "hours watched" figure has to sum real episode lengths to be
     * worth showing, and this project already ships reading-time statistics, so the same question
     * will be asked of TV.
     */
    val runtimeMinutes: Int? = null,
    /** This episode's synopsis from a provider, or `null` if none has been fetched. */
    val overview: String? = null,
    /**
     * Content hash of this episode's still image as stored by
     * [com.hub.media.core.storage.LocalImageStorageManager.saveImage], or `null` if none has been
     * downloaded.
     *
     * A hash of a locally-stored file rather than a provider URL, matching
     * [MediaItemEntity.coverImageHash] and AGENTS.md §4: this app is offline-first, and a remote
     * path would make every episode row render blank the moment the device loses signal. Whether
     * to download stills at all, and how aggressively to evict them, is a Phase D decision — a
     * show can hold hundreds of episodes — but the column shape does not depend on that answer.
     */
    val stillImageHash: String? = null,
    /**
     * The provider's aggregate viewer score, normalised to 0.0-10.0, or `null` if unknown.
     *
     * Deliberately *not* the user's own rating, which belongs to Task 10 (re-read/re-watch
     * modelling) and may well attach to a viewing rather than to the episode. This is the "did
     * other people like it" number, kept so it can be shown against the user's own once that
     * exists. Normalised on write because providers disagree on scale — TMDB is out of 10,
     * Goodreads out of 5 — and a bare number whose scale is unrecorded is a trap.
     */
    val communityRating: Double? = null,
)
