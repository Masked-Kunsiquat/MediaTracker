package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

            assertEquals(ReadingGoal(2026, 12), settings.observeReadingGoal().first())
            assertEquals("2026:12", settings.getString("reading_goal"))
        }

    @Test
    fun noGoal_isNull() =
        runTest {
            assertNull(settings.observeReadingGoal().first())
        }

    @Test
    fun aMalformedOrOutOfRangeValue_readsAsNoGoal() =
        runTest {
            for (bad in listOf("twelve", "2026", "2026:0", "2026:1000", ":12", "2026:-3")) {
                settings.setString("reading_goal", bad)
                assertNull(settings.observeReadingGoal().first(), "\"$bad\" must not read as a goal")
            }
        }

    @Test
    fun clearing_removesTheGoal() =
        runTest {
            settings.setReadingGoal(year = 2026, books = 12)

            settings.clearReadingGoal()

            assertNull(settings.observeReadingGoal().first())
        }
}
