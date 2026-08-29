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

/**
 * [SettingsRepository] tests against a real in-memory [AppDatabase] (schema v4's new `app_settings`
 * table, ROADMAP Task 7 Phase A), mirroring [com.hub.media.features.books.data.BookRepositoryTest]'s
 * style. Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81) —
 * `:shared:jvmTest` is the authoritative gate, same as every other Room-touching test.
 */
class SettingsRepositoryTest {
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
    fun getString_neverSetKey_isNull() =
        runTest {
            assertNull(repo.getString("never_set"))
        }

    @Test
    fun observeString_neverSetKey_emitsNull() =
        runTest {
            assertNull(repo.observeString("never_set").first())
        }

    @Test
    fun setString_thenGetString_roundTripsExactly() =
        runTest {
            repo.setString("week_start_day", "MONDAY")
            assertEquals("MONDAY", repo.getString("week_start_day"))
        }

    @Test
    fun setString_thenObserveString_emitsNewValue() =
        runTest {
            repo.setString("week_start_day", "SUNDAY")
            assertEquals("SUNDAY", repo.observeString("week_start_day").first())
        }

    @Test
    fun setString_overwritesPreviousValueForSameKey() =
        runTest {
            repo.setString("theme", "dark")
            repo.setString("theme", "light")
            assertEquals("light", repo.getString("theme"))
        }

    @Test
    fun setString_differentKeys_doNotCollide() =
        runTest {
            repo.setString("key_a", "value_a")
            repo.setString("key_b", "value_b")
            assertEquals("value_a", repo.getString("key_a"))
            assertEquals("value_b", repo.getString("key_b"))
        }

    @Test
    fun setInt_thenGetInt_roundTripsExactly() =
        runTest {
            repo.setInt("week_start_day_ordinal", 1)
            assertEquals(1, repo.getInt("week_start_day_ordinal"))
        }

    @Test
    fun getInt_malformedStoredValue_isNullNotThrow() =
        runTest {
            repo.setString("not_a_number", "abc")
            assertNull(repo.getInt("not_a_number"))
        }

    @Test
    fun setBoolean_thenGetBoolean_roundTripsExactly() =
        runTest {
            repo.setBoolean("feature_flag", true)
            assertEquals(true, repo.getBoolean("feature_flag"))
            repo.setBoolean("feature_flag", false)
            assertEquals(false, repo.getBoolean("feature_flag"))
        }

    @Test
    fun getBoolean_malformedStoredValue_isNullNotThrow() =
        runTest {
            repo.setString("not_a_boolean", "maybe")
            assertNull(repo.getBoolean("not_a_boolean"))
        }

    @Test
    fun clear_removesKey_revertsToNull() =
        runTest {
            repo.setString("temp_setting", "value")
            assertEquals("value", repo.getString("temp_setting"))

            repo.clear("temp_setting")

            assertNull(repo.getString("temp_setting"))
        }
}
