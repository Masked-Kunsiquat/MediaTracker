package com.hub.media.ui

import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails

/**
 * The library's status filter, media-neutral (ROADMAP Task 13 Phase B).
 *
 * ### Why this exists rather than filtering on [ReadingStatus] directly
 * Books store [ReadingStatus] and movies store [WatchStatus] — two enums, deliberately, because
 * unifying them would have meant rewriting every existing book's stored status (see [WatchStatus]'s
 * KDoc). The library list mixes both types, so the *filter* is where they have to be reconciled.
 *
 * Before this, [LibraryUiState.filteredMedia] filtered on [ReadingStatus] and returned `false` for
 * every non-book, meaning the first movie added would have been **invisible to the status filter
 * entirely** — present in the library, absent from every chip except "All". That was recorded as
 * this phase's inherited blocker.
 *
 * This enum is a presentation concern only. Nothing persists it, so adding or renaming a value here
 * costs no migration — which is the point of keeping it out of the entity layer.
 */
public enum class LibraryStatusFilter {
    /** Tracked but not started: [ReadingStatus.TO_READ] / [WatchStatus.WATCHLIST]. */
    NOT_STARTED,

    /** Started, not finished: [ReadingStatus.READING] / [WatchStatus.WATCHING]. */
    IN_PROGRESS,

    /** Completed: [ReadingStatus.FINISHED] / [WatchStatus.WATCHED]. */
    FINISHED,

    /** Deliberately given up on: [ReadingStatus.DNF] / [WatchStatus.ABANDONED]. */
    ABANDONED,
    ;

    /**
     * Whether [media] currently sits at this status.
     *
     * An item whose detail row is missing matches nothing — it has no status to compare, and
     * claiming otherwise would put it under a chip it does not belong to. It still appears under
     * "All", which is the filter's `null` case and never reaches this function.
     *
     * @param tvProgress This show's episode counts, or `null` when it has none. Ignored for books
     *   and movies, which carry their status directly — see [ofShow] for why TV cannot.
     */
    public fun matches(
        media: MediaWithDetails,
        tvProgress: TVProgressRow? = null,
    ): Boolean =
        when (media) {
            is MediaWithDetails.Book -> media.details?.status?.let(::of) == this
            is MediaWithDetails.Movie -> media.details?.status?.let(::of) == this
            is MediaWithDetails.TVShow -> media.details?.let { ofShow(it.status, tvProgress, it.airingStatus) } == this
        }

    public companion object {
        /** Maps a book's stored status onto the shared filter vocabulary. */
        public fun of(status: ReadingStatus): LibraryStatusFilter =
            when (status) {
                ReadingStatus.TO_READ -> NOT_STARTED
                ReadingStatus.READING -> IN_PROGRESS
                ReadingStatus.FINISHED -> FINISHED
                ReadingStatus.DNF -> ABANDONED
            }

        /**
         * Places a show, which — unlike a book or a movie — is **derived from its episodes**
         * rather than read from a stored status (ROADMAP Task 13 Phase C).
         *
         * ### Why TV diverges from the other two
         * Nothing advances `tv_details.status` as episodes are ticked off, so filtering on it
         * would file a show you had watched to the end under "Not started" until you corrected it
         * by hand. The case that decides it: finish a show, then quick-fill a newly aired season.
         * Derived, it returns to [IN_PROGRESS] on its own; stored, it sits on [FINISHED] while
         * unwatched episodes exist beneath it — the same stored-vs-derived drift
         * [com.hub.media.core.database.entities.TVDetailsEntity]'s KDoc refuses for progress
         * counters, in a different disguise.
         *
         * [WatchStatus.ABANDONED] is the one state episodes cannot express — "I gave up" is a
         * decision, not a count — so it is taken from [storedStatus] and wins over any derivation.
         * Every other stored value is ignored here on purpose; see that entity's KDoc for what the
         * column therefore does and does not mean.
         *
         * ### Watching everything is not the same as the show being over
         * "Every episode row is watched" answers a question about the *viewer*. Whether the show
         * has more episodes coming is a question about the *show*, and [airingStatus] is the only
         * thing that knows it. A running series whose aired episodes are all watched is **up to
         * date**, not completed — filing it under [FINISHED] tells the user they are done with
         * something that will hand them another season in three months, and it silently stops
         * being true the moment someone quick-fills that season.
         *
         * So a fully-watched show still in production is [IN_PROGRESS]: there is more of it, and
         * the viewer has not finished it. [AiringStatus.ENDED] and [AiringStatus.CANCELLED] both
         * mean no more is coming, so both keep [FINISHED] — a cancellation is still an end, even
         * an unsatisfying one.
         *
         * `null` means nobody has told us, which is **every row today** — nothing writes
         * [airingStatus] until Phase D — and it deliberately keeps the old behaviour rather than
         * guessing. A show is far more often finished than abandoned mid-watch, and moving every
         * completed show to [IN_PROGRESS] on the strength of an unknown would be a worse lie than
         * the one being fixed.
         *
         * @param tvProgress `null` for a show with no episode rows at all, which
         *   [com.hub.media.core.database.dao.EpisodeDao.observeProgress] omits entirely because it
         *   groups by `mediaId`. That absence means [NOT_STARTED] — a show nobody has quick-filled
         *   yet. Read instead as "0 of 0, therefore complete", it would file every empty show
         *   under [FINISHED].
         * @param airingStatus Whether the show is still running, or `null` for unknown. Defaulted
         *   so that callers which genuinely have no show row — and the tests written before this
         *   distinction existed — keep the pre-existing behaviour rather than being forced to
         *   assert an answer they do not have.
         */
        public fun ofShow(
            storedStatus: WatchStatus,
            tvProgress: TVProgressRow?,
            airingStatus: AiringStatus? = null,
        ): LibraryStatusFilter =
            when {
                storedStatus == WatchStatus.ABANDONED -> ABANDONED
                tvProgress == null || tvProgress.totalEpisodes == 0 -> NOT_STARTED
                tvProgress.watchedEpisodes == 0 -> NOT_STARTED
                tvProgress.watchedEpisodes >= tvProgress.totalEpisodes ->
                    if (airingStatus == AiringStatus.CONTINUING) IN_PROGRESS else FINISHED
                else -> IN_PROGRESS
            }

        /** Maps a movie's stored status onto the shared filter vocabulary. */
        public fun of(status: WatchStatus): LibraryStatusFilter =
            when (status) {
                WatchStatus.WATCHLIST -> NOT_STARTED
                WatchStatus.WATCHING -> IN_PROGRESS
                WatchStatus.WATCHED -> FINISHED
                WatchStatus.ABANDONED -> ABANDONED
            }
    }
}
