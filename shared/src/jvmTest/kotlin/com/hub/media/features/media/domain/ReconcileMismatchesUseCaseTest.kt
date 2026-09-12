package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.ShowSeasonMismatch
import com.hub.media.features.settings.data.getTmdbBackfillMismatches
import com.hub.media.features.settings.data.saveTmdbBackfillMismatches
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers [ReconcileMismatchesUseCase] (#123).
 *
 * The tests that matter are the ones about what acting on a **stale** finding must not do. A stored
 * disagreement describes the library as it was when a backfill ran, and the user may have changed it
 * since — so the dangerous case is not a wrong number on screen, it is a write that deletes episodes
 * on the strength of one.
 */
class ReconcileMismatchesUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var shows: TVShowRepository
    private lateinit var useCase: ReconcileMismatchesUseCase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        settings = SettingsRepository(db.appSettingsDao())
        shows = TVShowRepository(db)
        useCase = ReconcileMismatchesUseCase(db, shows, settings)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun showWith(episodeCount: Int): String {
        val result =
            shows.addShow(
                title = "Chernobyl",
                seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = episodeCount)),
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun record(
        mediaId: String,
        local: Int,
        provider: Int,
    ) = settings.saveTmdbBackfillMismatches(
        listOf(ShowSeasonMismatch(mediaId, seasonNumber = 1, localEpisodes = local, providerEpisodes = provider)),
    )

    // ---- reading -------------------------------------------------------------------------------

    @Test
    fun attachesTheShowsCurrentTitleAndDescribesTheShape() =
        runTest {
            val mediaId = showWith(2)
            record(mediaId, local = 2, provider = 5)

            val row = useCase.review().single()

            assertEquals("Chernobyl", row.showTitle)
            assertEquals(3, row.missingEpisodes)
            assertTrue(row.isUnderCount)
            assertTrue(!row.isEmptySeason, "two episodes is partial, not empty")
        }

    @Test
    fun dropsFindingsWhoseShowHasBeenDeleted() =
        runTest {
            record("a-show-that-is-gone", local = 2, provider = 5)

            assertTrue(useCase.review().isEmpty(), "a row naming nothing is worse than no row")
            assertTrue(settings.getTmdbBackfillMismatches().isEmpty(), "and it is not left on disk")
        }

    @Test
    fun theFindingsCanBeObservedSoTwoScreensCannotDisagree() =
        runTest {
            // Settings shows "N shows disagree" and the review screen shows the rows; each holds its
            // own ViewModel, and neither is recomposed by the other's writes. Reading once meant
            // reconciling a season left the Settings sentence claiming a disagreement that was gone.
            val mediaId = showWith(2)
            record(mediaId, local = 2, provider = 5)
            assertEquals(1, useCase.observeFindings().first().size)

            useCase.addMissingEpisodes(useCase.review().single())

            assertTrue(
                useCase.observeFindings().first().isEmpty(),
                "acting on a finding has to reach anything watching the store",
            )
        }

    // ---- acting, and what acting must never do -------------------------------------------------

    @Test
    fun createsOnlyTheMissingEpisodesAndForgetsTheFinding() =
        runTest {
            val mediaId = showWith(2)
            record(mediaId, local = 2, provider = 5)
            val row = useCase.review().single()

            val result = useCase.addMissingEpisodes(row)

            assertIs<Resource.Success<Int>>(result)
            assertEquals(3, result.data)
            assertEquals(
                listOf(1, 2, 3, 4, 5),
                db
                    .episodeDao()
                    .getByMediaId(mediaId)
                    .map { it.episodeNumber }
                    .sorted(),
            )
            assertTrue(settings.getTmdbBackfillMismatches().isEmpty(), "the finding is answered")
        }

    @Test
    fun aStaleFindingCanNeverDeleteEpisodesTheUserHasSinceAdded() =
        runTest {
            // The reason this does not route through setSeasonLength. The finding says "you have 2,
            // TMDB has 5", but the user has since grown the season to 8 by hand and watched two of
            // the new ones. Making the season *exactly* 5 long would delete 6, 7 and 8 and every
            // date on them -- while the button says "add".
            val mediaId = showWith(2)
            record(mediaId, local = 2, provider = 5)
            val row = useCase.review().single()
            assertIs<Resource.Success<*>>(shows.setSeasonLength(mediaId, seasonNumber = 1, episodeCount = 8))
            val watched = db.episodeDao().getByMediaId(mediaId).first { it.episodeNumber == 7 }
            db.tvWriteDao().markEpisodeWatched(watched.id, Instant.parse("2020-01-01T00:00:00Z"))

            val result = useCase.addMissingEpisodes(row)

            assertIs<Resource.Success<Int>>(result)
            assertEquals(0, result.data, "nothing was missing")
            assertEquals(8, db.episodeDao().getByMediaId(mediaId).size, "and nothing was removed")
            assertEquals(
                Instant.parse("2020-01-01T00:00:00Z"),
                db.episodeDao().getById(watched.id)?.watchedAt,
                "a watch date on an episode beyond the stale count must survive",
            )
        }

    @Test
    fun neverDisturbsEpisodesThatAlreadyExist() =
        runTest {
            val mediaId = showWith(2)
            val before = db.episodeDao().getByMediaId(mediaId).associate { it.episodeNumber to it.id }
            db.tvWriteDao().markEpisodeWatched(before.getValue(1), Instant.parse("2020-01-01T00:00:00Z"))
            record(mediaId, local = 2, provider = 5)

            useCase.addMissingEpisodes(useCase.review().single())

            val after = db.episodeDao().getByMediaId(mediaId).associate { it.episodeNumber to it.id }
            assertEquals(before.getValue(1), after.getValue(1), "the existing rows are the same rows")
            assertEquals(before.getValue(2), after.getValue(2))
            assertEquals(
                Instant.parse("2020-01-01T00:00:00Z"),
                db.episodeDao().getById(before.getValue(1))?.watchedAt,
            )
        }

    @Test
    fun refusesAnOverCountRatherThanActingOnIt() =
        runTest {
            // TMDB is not reliably right about counts (#88: Judy Justice reports 446 against 458
            // real episodes), so "correcting" a library down to its figure would destroy history to
            // match a wrong number. Nothing should reach this, and if it does it is a bug.
            val mediaId = showWith(8)
            record(mediaId, local = 8, provider = 5)
            val row = useCase.review().single()

            val result = useCase.addMissingEpisodes(row)

            assertIs<Resource.Error>(result)
            assertEquals(8, db.episodeDao().getByMediaId(mediaId).size, "nothing was removed")
        }

    @Test
    fun anEmptySeasonReadsAsEmptyRatherThanPartial() =
        runTest {
            // Fleabag's real shape: a season recorded with no episodes in it at all. The screen says
            // "add all 6" for this and "add the 3 missing" for a partial one.
            val result =
                shows.addShow(
                    title = "Fleabag",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 1)),
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "67070"),
                )
            assertIs<Resource.Success<String>>(result)
            record(result.data, local = 0, provider = 6)

            val row = useCase.review().single()

            assertTrue(row.isEmptySeason)
            assertEquals(6, row.missingEpisodes)
        }
}
