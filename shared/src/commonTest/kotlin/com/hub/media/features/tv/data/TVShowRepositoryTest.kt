package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.movies.data.MovieRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Covers [TVShowRepository] -- the TV counterpart of
 * [com.hub.media.features.movies.data.MovieRepositoryTest], deliberately mirroring its setup,
 * naming, and level of KDoc, per [TVShowRepository]'s own note that it is written to behave
 * identically to [com.hub.media.features.movies.data.MovieRepository] where the two overlap. The
 * one structural difference this file covers that the movie test does not is per-episode
 * progress: quick-fill, [TVShowRepository.setSeasonLength]'s "add only what's missing" rule, and the
 * per-episode/per-season watched ticks.
 */
class TVShowRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /**
     * A [Clock] whose [now] advances by [stepMillis] on every call, starting from [start]. Used
     * wherever a test must prove a timestamp came from a *fresh* [Clock.now] read rather than a
     * stale/reused value -- a fixed clock cannot distinguish "read once and reused" from "read
     * again and got the same wall-clock instant by coincidence", but an advancing one can: two
     * calls always produce two different [Instant]s, so reusing the first is directly observable.
     */
    private class AdvancingClock(
        start: Instant,
        private val stepMillis: Long = 1_000,
    ) : Clock {
        private var current = start

        override fun now(): Instant {
            val value = current
            current = current + stepMillis.milliseconds
            return value
        }
    }

    /**
     * Asserts a rejected write left **all three** tables untouched, not merely the one a given test
     * was about. A validation failure must not persist a `media_items` row without its details, or
     * episode rows for a show that was never created -- and checking one table would not notice.
     */
    private suspend fun assertNothingPersisted() {
        assertTrue(
            db
                .mediaItemDao()
                .observeAll()
                .first()
                .isEmpty(),
            "a rejected addShow must leave no media_items row",
        )
        assertTrue(db.tvDetailsDao().getAll().isEmpty(), "a rejected addShow must leave no tv_details row")
        assertTrue(
            db
                .episodeDao()
                .observeAll()
                .first()
                .isEmpty(),
            "a rejected addShow must leave no episode rows",
        )
    }

    // ---- addShow: happy path ----------------------------------------------------------------

    @Test
    fun addShow_happyPath_insertsMediaItemDetailsAndEpisodeRows() =
        runTest {
            val result =
                repo.addShow(
                    title = "Breaking Bad",
                    releaseYear = 2008,
                    purchasePrice = 19.99,
                    totalSeasons = 1,
                    coverImageHash = "bb.jpg",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3)),
                )

            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("Breaking Bad", mediaItem?.title)
            assertEquals(MediaType.TV_SHOW, mediaItem?.type)
            assertEquals(2008, mediaItem?.releaseYear)
            assertEquals(19.99, mediaItem?.purchasePrice)
            assertEquals("bb.jpg", mediaItem?.coverImageHash)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(mediaId, details?.mediaId)
            assertEquals(1, details?.totalSeasons)
            assertEquals(WatchStatus.WATCHLIST, details?.status)

            val episodes = db.episodeDao().getByMediaId(mediaId)
            assertEquals(3, episodes.size, "quick-fill must generate exactly one row per episode")
            for (episode in episodes) {
                assertNull(episode.title, "quick-filled episodes must start with an unknown title")
                assertNull(episode.airDate, "quick-filled episodes must start with an unknown air date")
                assertNull(episode.watchedAt, "a freshly quick-filled episode must be unwatched")
                assertEquals(1, episode.seasonNumber)
            }
            assertEquals(listOf(1, 2, 3), episodes.map { it.episodeNumber }.sorted())
        }

    @Test
    fun addShow_emptySeasons_showExistsWithZeroEpisodesAndDoesNotCrash() =
        runTest {
            val result = repo.addShow(title = "Announced, Not Yet Filled", seasons = emptyList())

            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            assertTrue(db.mediaItemDao().getById(mediaId) != null)
            assertTrue(db.tvDetailsDao().getByMediaId(mediaId) != null)
            assertTrue(db.episodeDao().getByMediaId(mediaId).isEmpty())
        }

    @Test
    fun addShow_multipleSeasons_correctPerSeasonCountsAndNumberingStartsAtOneEach() =
        runTest {
            val result =
                repo.addShow(
                    title = "Multi-Season Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 3),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 5),
                        ),
                )
            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            val season1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            val season2 = db.episodeDao().getByMediaIdAndSeason(mediaId, 2)
            assertEquals(listOf(1, 2, 3), season1.map { it.episodeNumber })
            assertEquals(listOf(1, 2, 3, 4, 5), season2.map { it.episodeNumber })
        }

    @Test
    fun addShow_seasonZeroForSpecials_acceptedNotRejected() =
        runTest {
            // EpisodeEntity's KDoc: season 0 ("specials") is representable and nothing forbids it.
            val result =
                repo.addShow(
                    title = "Has Specials",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 0, episodeCount = 2)),
                )
            assertIs<Resource.Success<String>>(result)

            val episodes = db.episodeDao().getByMediaIdAndSeason(result.data, 0)
            assertEquals(listOf(1, 2), episodes.map { it.episodeNumber })
        }

    // ---- addShow: validation rejects, each proving NOTHING was written -----------------------

    @Test
    fun addShow_blankTitle_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "   ")
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_releaseYearTooLow_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "Show", releaseYear = TVMetadataValidation.MIN_RELEASE_YEAR - 1)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_releaseYearTooHigh_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "Show", releaseYear = TVMetadataValidation.MAX_RELEASE_YEAR + 1)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_negativePurchasePrice_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "Show", purchasePrice = -0.01)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_nonFinitePurchasePriceNaN_rejectedAndPersistsNothing() =
        runTest {
            // Double.NaN satisfies `purchasePrice < 0.0 == false` under IEEE 754, so a plain
            // negativity check alone would silently let it through -- see TVMetadataValidation's
            // KDoc on why validatePurchasePrice is delegated to BookMetadataValidation rather than
            // reimplemented.
            val result = repo.addShow(title = "Show", purchasePrice = Double.NaN)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_nonFinitePurchasePriceInfinity_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "Show", purchasePrice = Double.POSITIVE_INFINITY)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_episodeCountZero_rejectedAndPersistsNothing() =
        runTest {
            val result =
                repo.addShow(title = "Show", seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 0)))
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_episodeCountAboveMax_rejectedAndPersistsNothing() =
        runTest {
            val result =
                repo.addShow(
                    title = "Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(
                                seasonNumber = 1,
                                episodeCount = TVMetadataValidation.MAX_EPISODE_COUNT + 1,
                            ),
                        ),
                )
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_negativeSeasonNumber_rejectedAndPersistsNothing() =
        runTest {
            val result =
                repo.addShow(title = "Show", seasons = listOf(SeasonQuickFill(seasonNumber = -1, episodeCount = 5)))
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    @Test
    fun addShow_totalSeasonsZero_rejectedAndPersistsNothing() =
        runTest {
            val result = repo.addShow(title = "Show", totalSeasons = 0)
            assertIs<Resource.Error>(result)

            assertNothingPersisted()
        }

    // ---- addShow: valid boundary values are ACCEPTED (positive controls for the rejects above) --

    @Test
    fun addShow_releaseYearAtMinBoundary_accepted() =
        runTest {
            val result =
                repo.addShow(title = "First Broadcasts", releaseYear = TVMetadataValidation.MIN_RELEASE_YEAR)
            assertIs<Resource.Success<String>>(result)

            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals(TVMetadataValidation.MIN_RELEASE_YEAR, mediaItem?.releaseYear)
        }

    @Test
    fun addShow_releaseYearAtMaxBoundary_accepted() =
        runTest {
            val result =
                repo.addShow(title = "Announced Future Show", releaseYear = TVMetadataValidation.MAX_RELEASE_YEAR)
            assertIs<Resource.Success<String>>(result)

            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals(TVMetadataValidation.MAX_RELEASE_YEAR, mediaItem?.releaseYear)
        }

    @Test
    fun addShow_episodeCountExactlyOne_accepted() =
        runTest {
            val result =
                repo.addShow(title = "One Episode Season", seasons = listOf(SeasonQuickFill(1, episodeCount = 1)))
            assertIs<Resource.Success<String>>(result)

            val episodes = db.episodeDao().getByMediaIdAndSeason(result.data, 1)
            assertEquals(listOf(1), episodes.map { it.episodeNumber })
        }

    @Test
    fun addShow_episodeCountExactlyMax_acceptedWithCorrectRowCount() =
        runTest {
            val result =
                repo.addShow(
                    title = "Maximally Long Season",
                    seasons = listOf(SeasonQuickFill(1, episodeCount = TVMetadataValidation.MAX_EPISODE_COUNT)),
                )
            assertIs<Resource.Success<String>>(result)

            // Row count only, per the boundary test's intent -- inspecting all 500 individually
            // would just be addShow_happyPath repeated 500 times.
            val episodes = db.episodeDao().getByMediaIdAndSeason(result.data, 1)
            assertEquals(TVMetadataValidation.MAX_EPISODE_COUNT, episodes.size)
        }

    // ---- addShow: duplicate season number -----------------------------------------------------

    @Test
    fun addShow_sameSeasonNumberListedTwice_rejectedNamingTheSeasonAndPersistsNothing() =
        runTest {
            val result =
                repo.addShow(
                    title = "Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 5),
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 3),
                        ),
                )
            assertIs<Resource.Error>(result)
            assertTrue(
                result.message.contains("Season 1"),
                "the rejection must name the offending season rather than surface a raw UNIQUE-constraint message",
            )

            assertNothingPersisted()
        }

    // ---- setSeasonLength: adds only the missing episode numbers -------------------------------------

    @Test
    fun setSeasonLength_correctingSeasonSize_addsOnlyMissingNumbersAndPreservesExistingIdsAndWatchedState() =
        runTest {
            // The single most important test in this file. Re-creating rows instead of adding the
            // missing ones would destroy watched state -- EpisodeEntity's KDoc explicitly forbids it.
            val addResult =
                repo.addShow(
                    title = "Growing Show",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 10)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val original = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            assertEquals(10, original.size)
            val firstEpisode = original.first { it.episodeNumber == 1 }
            val watchResult = repo.setEpisodeWatched(firstEpisode.id, watched = true)
            assertIs<Resource.Success<Unit>>(watchResult)
            val watchedAtBeforeChange = db.episodeDao().getById(firstEpisode.id)?.watchedAt
            assertTrue(watchedAtBeforeChange != null, "precondition: episode 1 is watched before the season grows")

            val result = repo.setSeasonLength(mediaId, seasonNumber = 1, episodeCount = 12)
            assertEquals(
                0,
                assertIs<Resource.Success<SeasonLengthChange>>(result).data.episodesRemoved,
                "growing a season removes nothing",
            )

            val after = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            assertEquals(
                12,
                after.size,
                "setSeasonLength(12) on a season of 10 must add exactly the 2 missing episodes",
            )
            assertEquals((1..12).toList(), after.map { it.episodeNumber })

            // The original ten rows must be the SAME rows, not ten new ones with matching numbers.
            val afterById = after.associateBy { it.id }
            for (originalEpisode in original) {
                val stillThere = afterById[originalEpisode.id]
                assertTrue(
                    stillThere != null,
                    "episode id=${originalEpisode.id} (episode #${originalEpisode.episodeNumber}) must survive " +
                        "a season change unchanged -- recreating it would be indistinguishable from data loss",
                )
            }
            val episode1After = afterById[firstEpisode.id]
            assertEquals(
                watchedAtBeforeChange,
                episode1After?.watchedAt,
                "growing a season must not touch watchedAt on episodes that already existed",
            )
        }

    @Test
    fun setSeasonLength_shrinkingASeason_removesOnlyTheEpisodesAboveTheNewCount() =
        runTest {
            // The bug this exists for: a season could only ever grow, so a mistyped 20 left ten rows
            // that could not be removed and a show that could never read as finished.
            val addResult =
                repo.addShow(
                    title = "Mistyped",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 20)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val original = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            val keptEpisode = original.first { it.episodeNumber == 3 }
            assertIs<Resource.Success<Unit>>(repo.setEpisodeWatched(keptEpisode.id, watched = true))
            val keptWatchedAt = db.episodeDao().getById(keptEpisode.id)?.watchedAt

            val result = repo.setSeasonLength(mediaId, seasonNumber = 1, episodeCount = 10)

            val change = assertIs<Resource.Success<SeasonLengthChange>>(result).data
            assertEquals(10, change.episodesRemoved, "episodes 11..20 are the ones that had to go")

            val after = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            assertEquals((1..10).toList(), after.map { it.episodeNumber })
            assertEquals(
                keptWatchedAt,
                after.first { it.id == keptEpisode.id }.watchedAt,
                "an episode below the new count keeps its identity and its watched date",
            )
        }

    @Test
    fun setSeasonLength_shrinkingPastAWatchedEpisode_removesItAndSaysHowMany() =
        runTest {
            // Shrinking is destructive on purpose -- the point is that the count reported back is
            // what the screen puts in front of the user before they confirm.
            val addResult =
                repo.addShow(
                    title = "Shrinking",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 5)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val lastEpisode = db.episodeDao().getByMediaIdAndSeason(mediaId, 1).first { it.episodeNumber == 5 }
            assertIs<Resource.Success<Unit>>(repo.setEpisodeWatched(lastEpisode.id, watched = true))

            val result = repo.setSeasonLength(mediaId, seasonNumber = 1, episodeCount = 4)

            assertEquals(1, assertIs<Resource.Success<SeasonLengthChange>>(result).data.episodesRemoved)
            assertNull(db.episodeDao().getById(lastEpisode.id), "a watched episode above the count is still removed")
        }

    @Test
    fun setSeasonLength_unchangedCount_removesNothing() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Unchanged",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 6)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val result = repo.setSeasonLength(mediaId, seasonNumber = 1, episodeCount = 6)

            assertEquals(0, assertIs<Resource.Success<SeasonLengthChange>>(result).data.episodesRemoved)
            assertEquals(6, db.episodeDao().getByMediaIdAndSeason(mediaId, 1).size)
        }

    @Test
    fun setSeasonLength_shrinkingOneSeason_leavesEveryOtherSeasonAlone() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Two Seasons",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 8),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 8),
                        ),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            assertIs<Resource.Success<SeasonLengthChange>>(repo.setSeasonLength(mediaId, 1, episodeCount = 3))

            assertEquals(3, db.episodeDao().getByMediaIdAndSeason(mediaId, 1).size)
            assertEquals(8, db.episodeDao().getByMediaIdAndSeason(mediaId, 2).size, "season 2 must be untouched")
        }

    // ---- removeSeason -----------------------------------------------------------------------

    @Test
    fun removeSeason_deletesThatSeasonAndReportsTheCount() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Wrong Season",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 4),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 3),
                        ),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val result = repo.removeSeason(mediaId, seasonNumber = 2)

            assertEquals(3, assertIs<Resource.Success<SeasonLengthChange>>(result).data.episodesRemoved)
            assertTrue(db.episodeDao().getByMediaIdAndSeason(mediaId, 2).isEmpty())
            assertEquals(4, db.episodeDao().getByMediaIdAndSeason(mediaId, 1).size, "season 1 must be untouched")
        }

    @Test
    fun removeSeason_seasonWithNoEpisodes_returnsErrorRatherThanSilentSuccess() =
        runTest {
            val addResult = repo.addShow(title = "No Seasons")
            assertIs<Resource.Success<String>>(addResult)

            val result = repo.removeSeason(addResult.data, seasonNumber = 1)

            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("Season 1"), "the rejection must name the season")
            // This message reaches the user as a snackbar verbatim, so the media id stays in the
            // log where it is useful and out of the sentence the user reads.
            assertFalse(
                result.message.contains(addResult.data),
                "a user-facing message must not quote the internal media id",
            )
        }

    @Test
    fun removeSeason_negativeSeasonNumber_rejectedByValidationLikeSetSeasonLength() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Guarded Show",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3)),
                )
            assertIs<Resource.Success<String>>(addResult)

            val result = repo.removeSeason(addResult.data, seasonNumber = -1)

            assertIs<Resource.Error>(result)
            // Must be the validation rejection, not the "that season is already gone" one -- a
            // season that never existed would produce the latter whether or not validation ran, so
            // asserting merely that this errored would pass with the guard removed.
            assertEquals(
                TVMetadataValidation.validateSeasonNumber(-1),
                result.message,
                "removeSeason must reject a bad season number the way setSeasonLength does",
            )
            assertEquals(
                3,
                db.episodeDao().getByMediaIdAndSeason(addResult.data, 1).size,
                "a rejected season number must not have deleted anything",
            )
        }

    @Test
    fun setSeasonLength_mediaIdBelongsToBook_rejectedAndCreatesNoEpisodes() =
        runTest {
            val bookRepo = BookRepository(db)
            val bookResult = bookRepo.addBook(title = "Not A Show", format = BookFormat.PHYSICAL)
            assertIs<Resource.Success<String>>(bookResult)
            val bookId = bookResult.data

            val result = repo.setSeasonLength(bookId, seasonNumber = 1, episodeCount = 5)
            assertIs<Resource.Error>(result)
            assertTrue(db.episodeDao().getByMediaId(bookId).isEmpty())
        }

    @Test
    fun setSeasonLength_mediaIdBelongsToMovie_rejectedAndCreatesNoEpisodes() =
        runTest {
            val movieRepo = MovieRepository(db)
            val movieResult = movieRepo.addMovie(title = "Not A Show")
            assertIs<Resource.Success<String>>(movieResult)
            val movieId = movieResult.data

            val result = repo.setSeasonLength(movieId, seasonNumber = 1, episodeCount = 5)
            assertIs<Resource.Error>(result)
            assertTrue(db.episodeDao().getByMediaId(movieId).isEmpty())
        }

    @Test
    fun setSeasonLength_unknownMediaId_rejected() =
        runTest {
            val result = repo.setSeasonLength(newId(), seasonNumber = 1, episodeCount = 5)
            assertIs<Resource.Error>(result)
        }

    // ---- setEpisodeWatched ---------------------------------------------------------------------

    @Test
    fun setEpisodeWatched_true_stampsClockNow() =
        runTest {
            val fixedInstant = Instant.fromEpochMilliseconds(1_000_000_000_000) // 2001-09-09, not "now"
            val fixedClock =
                object : Clock {
                    override fun now(): Instant = fixedInstant
                }
            val repoWithFixedClock = TVShowRepository(db, fixedClock)

            val addResult = repo.addShow(title = "Show", seasons = listOf(SeasonQuickFill(1, episodeCount = 1)))
            assertIs<Resource.Success<String>>(addResult)
            val episodeId =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(addResult.data, 1)
                    .single()
                    .id

            val result = repoWithFixedClock.setEpisodeWatched(episodeId, watched = true)
            assertIs<Resource.Success<Unit>>(result)

            val episode = db.episodeDao().getById(episodeId)
            assertEquals(fixedInstant, episode?.watchedAt, "watchedAt must come from the injected Clock")
        }

    @Test
    fun setEpisodeWatched_trueOnAlreadyWatchedEpisode_preservesOriginalTimestamp() =
        runTest {
            // The clock keeps advancing on every now() call, so if re-ticking bumped watchedAt to a
            // new read, this would be directly detectable rather than coincidentally passing.
            val clock = AdvancingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
            val repoWithClock = TVShowRepository(db, clock)

            val addResult =
                repoWithClock.addShow(
                    title = "Show",
                    seasons = listOf(SeasonQuickFill(1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val episodeId =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(addResult.data, 1)
                    .single()
                    .id

            val firstResult = repoWithClock.setEpisodeWatched(episodeId, watched = true)
            assertIs<Resource.Success<Unit>>(firstResult)
            val firstWatchedAt = db.episodeDao().getById(episodeId)?.watchedAt
            assertTrue(firstWatchedAt != null)

            val secondResult = repoWithClock.setEpisodeWatched(episodeId, watched = true)
            assertIs<Resource.Success<Unit>>(secondResult)

            assertEquals(
                firstWatchedAt,
                db.episodeDao().getById(episodeId)?.watchedAt,
                "re-ticking an already-watched episode must not bump its timestamp",
            )
        }

    @Test
    fun setEpisodeWatched_false_clearsToNull() =
        runTest {
            val addResult = repo.addShow(title = "Show", seasons = listOf(SeasonQuickFill(1, episodeCount = 1)))
            assertIs<Resource.Success<String>>(addResult)
            val episodeId =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(addResult.data, 1)
                    .single()
                    .id
            repo.setEpisodeWatched(episodeId, watched = true)
            assertTrue(db.episodeDao().getById(episodeId)?.watchedAt != null)

            val result = repo.setEpisodeWatched(episodeId, watched = false)
            assertIs<Resource.Success<Unit>>(result)
            assertNull(db.episodeDao().getById(episodeId)?.watchedAt)
        }

    @Test
    fun setEpisodeWatched_unknownEpisodeId_returnsError() =
        runTest {
            val result = repo.setEpisodeWatched(newId(), watched = true)
            assertIs<Resource.Error>(result)
        }

    // ---- setSeasonWatched -----------------------------------------------------------------------

    @Test
    fun setSeasonWatched_true_marksUnwatchedPreservesAlreadyWatchedAndLeavesOtherSeasonsUntouched() =
        runTest {
            val clock = AdvancingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
            val repoWithClock = TVShowRepository(db, clock)

            val addResult =
                repoWithClock.addShow(
                    title = "Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 3),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 2),
                        ),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val season1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            val preWatched = season1.first { it.episodeNumber == 1 }

            val preWatchResult = repoWithClock.setEpisodeWatched(preWatched.id, watched = true)
            assertIs<Resource.Success<Unit>>(preWatchResult)
            val preWatchedAt = db.episodeDao().getById(preWatched.id)?.watchedAt
            assertTrue(preWatchedAt != null)

            val result = repoWithClock.setSeasonWatched(mediaId, seasonNumber = 1, watched = true)
            assertIs<Resource.Success<Unit>>(result)

            val afterSeason1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            val afterPreWatched = afterSeason1.first { it.id == preWatched.id }
            assertEquals(
                preWatchedAt,
                afterPreWatched.watchedAt,
                "marking a season watched must not restamp an episode that was already watched",
            )
            val newlyWatched = afterSeason1.filter { it.id != preWatched.id }
            for (episode in newlyWatched) {
                assertTrue(
                    episode.watchedAt != null,
                    "every previously-unwatched episode of the season must be watched",
                )
                assertTrue(
                    episode.watchedAt != preWatchedAt,
                    "newly-watched episodes must get a fresh timestamp, not the pre-watched episode's",
                )
            }

            val season2 = db.episodeDao().getByMediaIdAndSeason(mediaId, 2)
            assertTrue(season2.all { it.watchedAt == null }, "other seasons of the same show must be untouched")
        }

    @Test
    fun setSeasonWatched_false_clearsWholeSeasonIncludingDifferentTimestampsAndLeavesOtherSeasonsUntouched() =
        runTest {
            val clock = AdvancingClock(start = Instant.fromEpochMilliseconds(1_700_000_000_000))
            val repoWithClock = TVShowRepository(db, clock)

            val addResult =
                repoWithClock.addShow(
                    title = "Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 3),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 2),
                        ),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val season1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)

            // Watch episode 1 individually (one timestamp), then the rest of the season in bulk
            // (a different, later timestamp) -- so clearing has to clear both timestamps, not just one.
            repoWithClock.setEpisodeWatched(season1.first { it.episodeNumber == 1 }.id, watched = true)
            repoWithClock.setSeasonWatched(mediaId, seasonNumber = 1, watched = true)
            val fullyWatchedSeason1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            assertTrue(fullyWatchedSeason1.all { it.watchedAt != null }, "precondition: season 1 fully watched")
            // Two distinct timestamps: one from the individual tick, one shared by the two episodes
            // markSeasonWatched stamped in bulk with a single clock.now() read.
            assertEquals(
                2,
                fullyWatchedSeason1.map { it.watchedAt }.distinct().size,
                "precondition: two distinct times",
            )

            val result = repoWithClock.setSeasonWatched(mediaId, seasonNumber = 1, watched = false)
            assertIs<Resource.Success<Unit>>(result)

            val clearedSeason1 = db.episodeDao().getByMediaIdAndSeason(mediaId, 1)
            assertTrue(
                clearedSeason1.all {
                    it.watchedAt == null
                },
                "clearing must clear every episode regardless of when it was watched",
            )

            val season2 = db.episodeDao().getByMediaIdAndSeason(mediaId, 2)
            assertTrue(season2.all { it.watchedAt == null }, "other seasons of the same show must be untouched")
        }

    @Test
    fun setSeasonWatched_seasonWithNoEpisodes_returnsError() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Show",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            // Season 5 was never quick-filled, so its affected-row count from a bulk UPDATE would
            // also be 0 -- the same count a fully-watched season produces. This is why the
            // repository reads the season first rather than inferring existence from the count.
            val result = repo.setSeasonWatched(mediaId, seasonNumber = 5, watched = true)
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("no episodes"))
        }

    @Test
    fun setSeasonWatched_trueOnAlreadyFullyWatchedSeason_succeedsWithoutChangingTimestamps() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Show",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val firstMark = repo.setSeasonWatched(mediaId, seasonNumber = 1, watched = true)
            assertIs<Resource.Success<Unit>>(firstMark)
            val timestampsBefore =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(
                        mediaId,
                        1,
                    ).map { it.id to it.watchedAt }
                    .toMap()

            // The case the affected-row count alone would have got wrong: 0 rows changed here, but
            // that means "already fully watched" (success), not "no such season" (error).
            val result = repo.setSeasonWatched(mediaId, seasonNumber = 1, watched = true)
            assertIs<Resource.Success<Unit>>(result)

            val timestampsAfter =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(
                        mediaId,
                        1,
                    ).map { it.id to it.watchedAt }
                    .toMap()
            assertEquals(
                timestampsBefore,
                timestampsAfter,
                "re-marking an already fully-watched season must change nothing",
            )
        }

    // ---- updateShowMetadata ----------------------------------------------------------------------

    @Test
    fun updateShowMetadata_happyPath_updatesBothTables() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Original Title",
                    releaseYear = 2008,
                    purchasePrice = 19.99,
                    totalSeasons = 1,
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val result =
                repo.updateShowMetadata(
                    mediaId = mediaId,
                    title = "Corrected Title",
                    releaseYear = 2009,
                    purchasePrice = 9.99,
                    totalSeasons = 2,
                    status = WatchStatus.WATCHING,
                )
            assertIs<Resource.Success<Unit>>(result)

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("Corrected Title", mediaItem?.title)
            assertEquals(2009, mediaItem?.releaseYear)
            assertEquals(9.99, mediaItem?.purchasePrice)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(2, details?.totalSeasons)
            assertEquals(WatchStatus.WATCHING, details?.status)
        }

    @Test
    fun updateShowMetadata_unknownMediaId_returnsErrorAndWritesNothing() =
        runTest {
            val unknownId = newId()

            val result =
                repo.updateShowMetadata(mediaId = unknownId, title = "Any Title", status = WatchStatus.WATCHLIST)
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("not found"))

            assertNull(db.mediaItemDao().getById(unknownId))
            assertNull(db.tvDetailsDao().getByMediaId(unknownId))
        }

    @Test
    fun updateShowMetadata_mediaIdBelongsToBook_returnsErrorAndLeavesTheBookUnchanged() =
        runTest {
            // The dangerous half of the type gate. A book's id shares the media_items table with
            // every show, so an UPDATE keyed on id alone would rewrite that book's title/year/price
            // with TV-form values -- and report success, because only the tv_details half would
            // miss and that half's row count is not the one the repository checks.
            val bookRepo = BookRepository(db)
            val bookResult =
                bookRepo.addBook(
                    title = "Not A Show",
                    releaseYear = 1925,
                    purchasePrice = 9.99,
                    format = BookFormat.PHYSICAL,
                )
            assertIs<Resource.Success<String>>(bookResult)
            val bookId = bookResult.data

            val result =
                repo.updateShowMetadata(
                    mediaId = bookId,
                    title = "Clobbered By A TV Edit",
                    releaseYear = 2000,
                    purchasePrice = 1.0,
                    totalSeasons = 3,
                    status = WatchStatus.WATCHED,
                )
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("not found"))

            val book = db.mediaItemDao().getById(bookId)
            assertEquals("Not A Show", book?.title, "a show edit must never rewrite a book's title")
            assertEquals(1925, book?.releaseYear)
            assertEquals(9.99, book?.purchasePrice, "a show edit must never rewrite a book's price")
            assertNull(db.tvDetailsDao().getByMediaId(bookId))
        }

    @Test
    fun updateShowMetadata_detailsRowMissing_selfHealsRatherThanReportingAHalfWrite() =
        runTest {
            // The data-integrity edge MediaWithDetails.TVShow.details documents: a media_items row
            // with no tv_details half. The UPDATE there matches nothing, so totalSeasons/status used
            // to go nowhere while the media_items row count still said "updated".
            val mediaId = newId()
            db.tvWriteDao().insertMediaItem(
                MediaItemEntity(
                    id = mediaId,
                    type = MediaType.TV_SHOW,
                    title = "Orphaned",
                    releaseYear = null,
                    purchasePrice = null,
                    createdAt = Clock.System.now(),
                ),
            )
            assertNull(db.tvDetailsDao().getByMediaId(mediaId), "precondition: no details row")

            val result =
                repo.updateShowMetadata(
                    mediaId = mediaId,
                    title = "Orphaned",
                    totalSeasons = 4,
                    status = WatchStatus.WATCHING,
                )
            assertIs<Resource.Success<Unit>>(result)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(4, details?.totalSeasons, "the missing details row must be created, not skipped")
            assertEquals(WatchStatus.WATCHING, details?.status)
        }

    // ---- updateWatchStatus -----------------------------------------------------------------------

    @Test
    fun updateWatchStatus_changesStatusOnlyLeavesRestAlone() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Heat: The Series",
                    releaseYear = 1995,
                    purchasePrice = 7.99,
                    totalSeasons = 3,
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val result = repo.updateWatchStatus(mediaId = mediaId, status = WatchStatus.WATCHED)
            assertIs<Resource.Success<Unit>>(result)

            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("Heat: The Series", item?.title, "a status change must not rewrite the title")
            assertEquals(1995, item?.releaseYear)
            assertEquals(7.99, item?.purchasePrice)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(WatchStatus.WATCHED, details?.status)
            assertEquals(3, details?.totalSeasons, "a status change must not rewrite totalSeasons")
        }

    @Test
    fun updateWatchStatus_unknownMediaId_returnsError() =
        runTest {
            val result = repo.updateWatchStatus(mediaId = newId(), status = WatchStatus.WATCHED)
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("not found"))
        }

    // ---- observeShowDetail / observeAllShowsWithDetails: type gate against books and movies ----

    @Test
    fun observeShowDetail_emitsShowForInsertedShow() =
        runTest {
            val addResult = repo.addShow(title = "The Wire", releaseYear = 2002, totalSeasons = 5)
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val detail = repo.observeShowDetail(mediaId).first { it != null }
            assertEquals("The Wire", detail?.item?.title)
            assertEquals(2002, detail?.item?.releaseYear)
            assertEquals(mediaId, detail?.details?.mediaId)
            assertEquals(5, detail?.details?.totalSeasons)
        }

    @Test
    fun observeShowDetail_emitsNullWhenIdBelongsToBook() =
        runTest {
            val bookRepo = BookRepository(db)
            val bookResult = bookRepo.addBook(title = "Not A Show", format = BookFormat.PHYSICAL)
            assertIs<Resource.Success<String>>(bookResult)

            val detail = repo.observeShowDetail(bookResult.data).first()
            assertNull(detail)
        }

    @Test
    fun observeShowDetail_emitsNullWhenIdBelongsToMovie() =
        runTest {
            val movieRepo = MovieRepository(db)
            val movieResult = movieRepo.addMovie(title = "Not A Show")
            assertIs<Resource.Success<String>>(movieResult)

            val detail = repo.observeShowDetail(movieResult.data).first()
            assertNull(detail)
        }

    @Test
    fun observeAllShowsWithDetails_returnsOnlyTVShowsNotBooksOrMovies() =
        runTest {
            val showResult = repo.addShow(title = "A Real Show")
            assertIs<Resource.Success<String>>(showResult)

            val bookRepo = BookRepository(db)
            val bookResult = bookRepo.addBook(title = "A Real Book", format = BookFormat.PHYSICAL)
            assertIs<Resource.Success<String>>(bookResult)

            val movieRepo = MovieRepository(db)
            val movieResult = movieRepo.addMovie(title = "A Real Movie")
            assertIs<Resource.Success<String>>(movieResult)

            val allShows = repo.observeAllShowsWithDetails().first { it.isNotEmpty() }
            assertEquals(1, allShows.size)
            assertEquals("A Real Show", allShows.single().item.title)
            assertTrue(allShows.none { it.item.id == bookResult.data })
            assertTrue(allShows.none { it.item.id == movieResult.data })
        }

    // ---- EpisodeDao.observeProgress ----------------------------------------------------------

    @Test
    fun episodeDaoObserveProgress_watchedAndTotalCountsCorrectAndUpdateWhenEpisodeTicked() =
        runTest {
            val addResult =
                repo.addShow(
                    title = "Show",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val initial = db.episodeDao().observeProgress().first { rows -> rows.any { it.mediaId == mediaId } }
            val initialRow = initial.first { it.mediaId == mediaId }
            assertEquals(3, initialRow.totalEpisodes)
            assertEquals(0, initialRow.watchedEpisodes)

            val episodeId =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(mediaId, 1)
                    .first()
                    .id
            repo.setEpisodeWatched(episodeId, watched = true)

            val updated =
                db.episodeDao().observeProgress().first { rows ->
                    rows.any { it.mediaId == mediaId && it.watchedEpisodes == 1 }
                }
            val updatedRow = updated.first { it.mediaId == mediaId }
            assertEquals(3, updatedRow.totalEpisodes, "ticking an episode must not change the total")
            assertEquals(1, updatedRow.watchedEpisodes)
        }

    @Test
    fun episodeDaoObserveProgress_showWithNoEpisodes_hasNoRowAtAll() =
        runTest {
            // A GROUP BY over `episodes` produces no row for a show with zero episode rows -- there
            // is no "0 / 0" row to find, so a caller assuming one row per show would see nothing
            // rather than a zeroed progress row. Assert that explicitly.
            val addResult = repo.addShow(title = "No Episodes Yet", seasons = emptyList())
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            // Force at least one other show into the table so observeProgress emits a non-empty
            // list to inspect, then assert the empty show is absent from it.
            val otherResult =
                repo.addShow(
                    title = "Has Episodes",
                    seasons = listOf(SeasonQuickFill(1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(otherResult)

            val progress =
                db.episodeDao().observeProgress().first { rows ->
                    rows.any { it.mediaId == otherResult.data }
                }
            assertTrue(
                progress.none { it.mediaId == mediaId },
                "a show with zero episodes must produce no progress row",
            )
        }
}
