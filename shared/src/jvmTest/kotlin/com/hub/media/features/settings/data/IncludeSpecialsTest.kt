package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The include-specials accessors (#88), against a real in-memory database like [WeekStartDayTest]. */
class IncludeSpecialsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SettingsRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun neverSet_defaultsToTrue_soExistingLibrariesAreUnchanged() =
        runTest {
            assertTrue(repo.observeIncludeSpecials().first())
            assertTrue(repo.getIncludeSpecials())
        }

    @Test
    fun setFalse_roundTrips_andTrueRestoresIt() =
        runTest {
            repo.setIncludeSpecials(false)
            assertFalse(repo.observeIncludeSpecials().first())
            assertFalse(repo.getIncludeSpecials())

            repo.setIncludeSpecials(true)
            assertTrue(repo.observeIncludeSpecials().first())
        }

    @Test
    fun malformedStoredValue_readsAsTheDefault() =
        runTest {
            repo.setString("include_specials", "nope")
            assertTrue(repo.observeIncludeSpecials().first())
        }
}
