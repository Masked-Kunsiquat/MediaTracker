package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The yearly reading goal setting (#219): stored as "<year>:<books>", anything malformed reads as none. */
class ReadingGoalTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun aGoal_roundTrips() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)

            assertEquals(ReadingGoal(2026, 12), settings.observeReadingGoal(2026).first())
            assertEquals("2026:12", settings.getString("reading_goal"))
        }

    @Test
    fun noGoal_isNull() =
        runTest {
            assertNull(settings.observeReadingGoal(2026).first())
        }

    @Test
    fun aMalformedOrOutOfRangeValue_readsAsNoGoal() =
        runTest {
            for (bad in listOf("twelve", "2026", "2026:0", "2026:1000", ":12", "2026:-3")) {
                settings.setString("reading_goal", bad)
                assertNull(settings.observeReadingGoal(2026).first(), "\"$bad\" must not read as a goal")
            }
        }

    @Test
    fun clearing_removesTheGoal() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)

            settings.clearReadingGoal(2026)

            assertNull(settings.observeReadingGoal(2026).first())
            assertNull(settings.getString("reading_goal"), "the last entry gone clears the key")
        }

    // --- #221: every year's goal is kept --------------------------------------------------------

    @Test
    fun aGoalStoredBeforeTheChange_stillReads() =
        runTest {
            settings.setString("reading_goal", "2026:12")

            assertEquals(mapOf(2026 to 12), settings.observeReadingGoals().first())
        }

    @Test
    fun settingNextYear_keepsThisYear() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)
            settings.setReadingGoal(year = 2027, books = 20)

            assertEquals(mapOf(2026 to 12, 2027 to 20), settings.observeReadingGoals().first())
            assertEquals("2026:12,2027:20", settings.getString("reading_goal"))
            assertEquals(ReadingGoal(2026, 12), settings.observeReadingGoal(2026).first())
        }

    @Test
    fun settingAYearAgain_replacesOnlyThatYear() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)
            settings.setReadingGoal(year = 2025, books = 30)
            settings.setReadingGoal(year = 2026, books = 15)

            assertEquals("2025:30,2026:15", settings.getString("reading_goal"))
        }

    @Test
    fun clearingOneYear_keepsTheOthers() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)
            settings.setReadingGoal(year = 2027, books = 20)

            settings.clearReadingGoal(2026)

            assertEquals(mapOf(2027 to 20), settings.observeReadingGoals().first())
        }

    @Test
    fun aMalformedList_readsAsNoGoals() =
        runTest {
            for (bad in listOf("2026:12,2026:13", "2026:12,x", "2026:12,2027:0", "2026:12,", ",2026:12")) {
                settings.setString("reading_goal", bad)
                assertEquals(emptyMap(), settings.observeReadingGoals().first(), "\"$bad\"")
            }
        }

    /**
     * Each set reads the whole list, changes one year and writes it back, so two at once could each
     * write a list missing the other's year (#228 review). Forty years set concurrently must all land.
     */
    @Test
    fun concurrentSets_loseNoYear() =
        runBlocking {
            withContext(Dispatchers.Default) {
                (2000 until 2040).map { year -> async { settings.setReadingGoal(year, books = 1) } }.awaitAll()
            }

            assertEquals((2000 until 2040).toSet(), settings.observeReadingGoals().first().keys)
        }
}
