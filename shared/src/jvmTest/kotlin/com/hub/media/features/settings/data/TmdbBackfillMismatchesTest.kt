package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the hand-rolled encoding behind [ShowSeasonMismatch] storage (#123).
 *
 * The theme is that a malformed or out-of-bounds record must be **skipped, never thrown on**. This
 * is parsing of a value an older build or a truncated write could have put anything in, and the
 * alternative to skipping is a Settings screen that cannot open because one row is wrong.
 */
class TmdbBackfillMismatchesTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() = db.close()

    private val sample =
        listOf(
            ShowSeasonMismatch("show-a", seasonNumber = 1, localEpisodes = 2, providerEpisodes = 5),
            ShowSeasonMismatch("show-a", seasonNumber = 2, localEpisodes = 9, providerEpisodes = 9),
            ShowSeasonMismatch("show-b", seasonNumber = 3, localEpisodes = 12, providerEpisodes = 10),
        )

    @Test
    fun roundTripsEveryFieldInOrder() =
        runTest {
            settings.saveTmdbBackfillMismatches(sample)

            assertEquals(sample, settings.getTmdbBackfillMismatches())
        }

    @Test
    fun anEmptyListClearsTheKeyRatherThanStoringABlank() =
        runTest {
            settings.saveTmdbBackfillMismatches(sample)

            settings.saveTmdbBackfillMismatches(emptyList())

            assertTrue(settings.getTmdbBackfillMismatches().isEmpty())
        }

    @Test
    fun skipsRecordsThatCannotBeParsed() =
        runTest {
            // Two good records either side of three broken ones: too few fields, a non-numeric
            // season, and a blank media id.
            settings.setString(
                "tmdb_backfill_mismatches",
                "show-a:1:2:5,show-b:oops:1:2,show-c:1:2,:1:2:5,show-d:4:1:6",
            )

            val read = settings.getTmdbBackfillMismatches()

            assertEquals(2, read.size, "the readable records survive: $read")
            assertEquals(listOf("show-a", "show-d"), read.map { it.mediaId })
        }

    @Test
    fun skipsRecordsOutsideTheBoundsTheTypeDocuments() =
        runTest {
            // Season 0 is a special, which is never fetched and therefore never compared (#88), so
            // one appearing here can only be corrupt -- and would put a special in front of a user
            // on a screen whose premise is that they are excluded. Negative counts are impossible
            // from any writer and would make missingEpisodes meaningless.
            settings.setString(
                "tmdb_backfill_mismatches",
                "show-a:0:2:5,show-b:-1:2:5,show-c:1:-2:5,show-d:1:2:-5,show-e:1:2:5",
            )

            val read = settings.getTmdbBackfillMismatches()

            assertEquals(listOf("show-e"), read.map { it.mediaId }, "only the in-bounds record: $read")
        }

    @Test
    fun reportsWhatAddingWouldDo() =
        runTest {
            val under = ShowSeasonMismatch("s", seasonNumber = 1, localEpisodes = 2, providerEpisodes = 5)
            val over = ShowSeasonMismatch("s", seasonNumber = 2, localEpisodes = 12, providerEpisodes = 10)

            assertTrue(under.isUnderCount)
            assertEquals(3, under.missingEpisodes)
            assertTrue(!over.isUnderCount)
            assertEquals(0, over.missingEpisodes, "an over-count has nothing to add, never a negative")
        }
}
