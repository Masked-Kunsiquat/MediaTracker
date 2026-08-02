package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * [WeekStartDay]/[SettingsRepository.observeWeekStartDay]/[SettingsRepository.getWeekStartDay]/
 * [SettingsRepository.setWeekStartDay] tests against a real in-memory [AppDatabase] (ROADMAP Task 7
 * Phase B), mirroring [SettingsRepositoryTest]'s style. Room-backed, so excluded from the android
 * unit-test variant by the `com.hub.media.features.settings.*` package filter in
 * `shared/build.gradle.kts` — `:shared:jvmTest` is the authoritative gate.
 */
class WeekStartDayTest {

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
    fun getWeekStartDay_neverSet_defaultsToMonday() = runTest {
        assertEquals(WeekStartDay.MONDAY, repo.getWeekStartDay())
    }

    @Test
    fun observeWeekStartDay_neverSet_defaultsToMonday() = runTest {
        assertEquals(WeekStartDay.MONDAY, repo.observeWeekStartDay().first())
    }

    @Test
    fun setWeekStartDay_thenGetWeekStartDay_roundTripsSunday() = runTest {
        repo.setWeekStartDay(WeekStartDay.SUNDAY)
        assertEquals(WeekStartDay.SUNDAY, repo.getWeekStartDay())
    }

    @Test
    fun setWeekStartDay_thenGetWeekStartDay_roundTripsMonday() = runTest {
        // Explicitly setting MONDAY (rather than relying on the unset default) must still read
        // back as MONDAY -- proves the value is genuinely persisted, not just defaulted.
        repo.setWeekStartDay(WeekStartDay.SUNDAY)
        repo.setWeekStartDay(WeekStartDay.MONDAY)
        assertEquals(WeekStartDay.MONDAY, repo.getWeekStartDay())
    }

    @Test
    fun setWeekStartDay_thenObserveWeekStartDay_emitsNewValue() = runTest {
        repo.setWeekStartDay(WeekStartDay.SUNDAY)
        assertEquals(WeekStartDay.SUNDAY, repo.observeWeekStartDay().first())
    }

    @Test
    fun getWeekStartDay_malformedStoredValue_defaultsToMonday() = runTest {
        // A value that no longer maps to a WeekStartDay constant (e.g. written by a future
        // version, or corrupted) must be treated the same as unset -- never thrown, never crashing
        // the Settings/Stats screens.
        repo.setString("week_start_day", "TUESDAY")
        assertEquals(WeekStartDay.MONDAY, repo.getWeekStartDay())
    }

    @Test
    fun weekStartDay_isoDayNumbers_matchIsoConvention() {
        assertEquals(1, WeekStartDay.MONDAY.isoDayNumber)
        assertEquals(7, WeekStartDay.SUNDAY.isoDayNumber)
    }
}
