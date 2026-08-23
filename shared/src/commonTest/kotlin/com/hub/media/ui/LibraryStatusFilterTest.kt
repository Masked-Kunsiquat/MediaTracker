package com.hub.media.ui

import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [LibraryStatusFilter]'s placement rules (ROADMAP Task 13 Phase B/C).
 *
 * Pure -- no database, no Room -- so unlike the repository tests this runs on every variant. That
 * is worth keeping: the TV rules below are the ones a future change is most likely to break, and
 * they are expressible without a single row.
 */
class LibraryStatusFilterTest {
    private fun progress(
        total: Int,
        watched: Int,
    ) = TVProgressRow(mediaId = "show-1", totalEpisodes = total, watchedEpisodes = watched)

    // ---- books and movies: read straight from the stored status ------------------------------

    @Test
    fun of_bookStatuses_mapOntoTheSharedVocabulary() {
        assertEquals(LibraryStatusFilter.NOT_STARTED, LibraryStatusFilter.of(ReadingStatus.TO_READ))
        assertEquals(LibraryStatusFilter.IN_PROGRESS, LibraryStatusFilter.of(ReadingStatus.READING))
        assertEquals(LibraryStatusFilter.FINISHED, LibraryStatusFilter.of(ReadingStatus.FINISHED))
        assertEquals(LibraryStatusFilter.ABANDONED, LibraryStatusFilter.of(ReadingStatus.DNF))
    }

    @Test
    fun of_movieStatuses_mapOntoTheSharedVocabulary() {
        assertEquals(LibraryStatusFilter.NOT_STARTED, LibraryStatusFilter.of(WatchStatus.WATCHLIST))
        assertEquals(LibraryStatusFilter.IN_PROGRESS, LibraryStatusFilter.of(WatchStatus.WATCHING))
        assertEquals(LibraryStatusFilter.FINISHED, LibraryStatusFilter.of(WatchStatus.WATCHED))
        assertEquals(LibraryStatusFilter.ABANDONED, LibraryStatusFilter.of(WatchStatus.ABANDONED))
    }

    // ---- shows: derived from episodes, not from the stored status ----------------------------

    @Test
    fun ofShow_noEpisodesWatched_isNotStarted() {
        assertEquals(
            LibraryStatusFilter.NOT_STARTED,
            LibraryStatusFilter.ofShow(WatchStatus.WATCHLIST, progress(total = 10, watched = 0)),
        )
    }

    @Test
    fun ofShow_someEpisodesWatched_isInProgress() {
        assertEquals(
            LibraryStatusFilter.IN_PROGRESS,
            LibraryStatusFilter.ofShow(WatchStatus.WATCHLIST, progress(total = 10, watched = 4)),
        )
    }

    @Test
    fun ofShow_everyEpisodeWatched_isFinished() {
        assertEquals(
            LibraryStatusFilter.FINISHED,
            LibraryStatusFilter.ofShow(WatchStatus.WATCHLIST, progress(total = 10, watched = 10)),
        )
    }

    @Test
    fun ofShow_showWithNoEpisodeRowsAtAll_isNotStartedNotFinished() {
        // observeProgress groups by mediaId, so a show nobody has quick-filled has no row and
        // arrives here as null. Read as "0 of 0, therefore complete" it would file every empty
        // show under Finished -- which is why the null case is spelled out rather than computed.
        assertEquals(
            LibraryStatusFilter.NOT_STARTED,
            LibraryStatusFilter.ofShow(WatchStatus.WATCHLIST, tvProgress = null),
        )
    }

    @Test
    fun ofShow_newSeasonQuickFilledAfterFinishing_returnsToInProgress() {
        // The case that decided derivation over the stored status: a show watched to the end, then
        // a new season added. A stored "finished" would survive here; the episodes cannot.
        val finished = LibraryStatusFilter.ofShow(WatchStatus.WATCHED, progress(total = 10, watched = 10))
        assertEquals(LibraryStatusFilter.FINISHED, finished)

        val afterNewSeason = LibraryStatusFilter.ofShow(WatchStatus.WATCHED, progress(total = 18, watched = 10))
        assertEquals(
            LibraryStatusFilter.IN_PROGRESS,
            afterNewSeason,
            "quick-filling an unwatched season must move the show off Finished on its own",
        )
    }

    @Test
    fun ofShow_abandoned_winsOverEveryDerivation() {
        // Giving up is a decision no episode count can express, so it is the one value read from
        // the column -- and it must hold whatever the episodes say, including none at all.
        for (tvProgress in listOf(null, progress(total = 10, watched = 0), progress(total = 10, watched = 10))) {
            assertEquals(
                LibraryStatusFilter.ABANDONED,
                LibraryStatusFilter.ofShow(WatchStatus.ABANDONED, tvProgress),
                "an abandoned show stays abandoned regardless of progress ($tvProgress)",
            )
        }
    }

    @Test
    fun ofShow_storedStatusOtherThanAbandoned_isIgnored() {
        // WATCHING/WATCHED are storable but meaningless for placement: nothing writes them, and a
        // future reader must not be able to make one matter by setting it by hand.
        for (stored in listOf(WatchStatus.WATCHLIST, WatchStatus.WATCHING, WatchStatus.WATCHED)) {
            assertEquals(
                LibraryStatusFilter.IN_PROGRESS,
                LibraryStatusFilter.ofShow(stored, progress(total = 10, watched = 4)),
                "stored status $stored must not override what the episodes say",
            )
        }
    }

    @Test
    fun ofShow_watchedCountExceedingTotal_isStillFinished() {
        // Not reachable through the repository, but arithmetic that reads `==` would drop such a
        // show out of every chip rather than merely being wrong about which one.
        assertEquals(
            LibraryStatusFilter.FINISHED,
            LibraryStatusFilter.ofShow(WatchStatus.WATCHLIST, progress(total = 10, watched = 11)),
        )
    }
}
