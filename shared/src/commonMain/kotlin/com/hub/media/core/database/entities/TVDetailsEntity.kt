package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Show-level metadata for a [MediaItemEntity] of type [MediaType.TV_SHOW] (schema v6, ROADMAP
 * Task 13 Phase A). Same one-to-one shape as [BookDetailsEntity]/[MovieDetailsEntity].
 *
 * ### What is deliberately NOT here: progress
 * There is no "current season", "current episode", or "episodes watched" column, and adding one
 * later would be a mistake rather than an optimization. Task 13's hard requirement is that TV is
 * tracked **per episode**, so watched state lives on [EpisodeEntity.watchedAt] and progress is
 * *derived* by counting those rows.
 *
 * A stored progress counter would be a second source of truth for something already recorded, and
 * the two would drift the first time an episode is ticked off out of order, un-ticked, or added
 * after the fact. This project has already paid for that shape of bug once — see the selection/
 * derived-state note in ROADMAP Task 14 — so it is worth naming the rule rather than rediscovering
 * it.
 *
 * @property totalSeasons Season count, or `null` for "unknown". Advisory only: it is what the user
 *   typed (or what a provider reported), not a constraint — [EpisodeEntity] rows are the truth
 *   about what actually exists, and a show can legitimately hold episodes for a season beyond this
 *   number if the count was wrong.
 * @property status **Settled in Phase C: this is not a lifecycle, it is an abandonment flag.**
 *   Where a show sits — not started / in progress / finished — is derived from its episodes by
 *   [com.hub.media.ui.LibraryStatusFilter.ofShow], for the reason this KDoc gives above about
 *   progress: a stored value cannot see the rows that change beneath it, so a show would sit on
 *   "finished" while a newly quick-filled season went unwatched. Only [WatchStatus.ABANDONED] is
 *   read from this column, because giving up is a decision no episode count can express. The other
 *   [WatchStatus] values remain storable and are deliberately ignored by the filter; nothing in
 *   the app writes them, and a future reader should not infer meaning from one that appears here.
 * @property airingStatus Whether the show itself is still running — **not** the user's progress
 *   through it, which is what [status] and the derived filter above are about. The name collision
 *   between the two is the reason this one is not called `status`.
 *
 *   Nothing writes it yet; TMDB supplies it in Phase D as `status`/`in_production`. It exists now
 *   because "finished" currently conflates two states a viewer keeps apart: *up to date* (every
 *   aired episode watched, show still running) and *completed* (every episode there will ever be).
 *   Without this column those are indistinguishable, so a running show sits under Finished until
 *   its next season is quick-filled. `null` means unknown, which is every row today.
 * @property overview The show's synopsis from a provider, or `null` if none has been fetched.
 * @property firstAirDate Epoch milliseconds of the first episode's air date, or `null` if unknown.
 *   Stored rather than derived from [EpisodeEntity.airDate] because a quick-filled show has no air
 *   dates at all, and because the earliest *known* episode is not necessarily the first one.
 * @property lastAirDate Epoch milliseconds of the most recent aired episode, or `null`. Together
 *   with [airingStatus] this is what tells a viewer whether a gap means "on hiatus" or "over".
 */
@Entity(
    tableName = "tv_details",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
public data class TVDetailsEntity(
    @PrimaryKey val mediaId: String,
    val totalSeasons: Int? = null,
    val status: WatchStatus = WatchStatus.WATCHLIST,
    val airingStatus: AiringStatus? = null,
    val overview: String? = null,
    val firstAirDate: Long? = null,
    val lastAirDate: Long? = null,
)
