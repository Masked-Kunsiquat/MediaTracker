package com.hub.media.features.settings.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.portability.csv.EXPORTABLE_SETTING_KEYS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The Stats screen's remembered tab (#217): Books unless something else was picked. */
class StatsDomainSettingTest {
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
    fun aPickedTab_roundTrips() =
        runTest {
            settings.setStatsDomain(MediaType.TV_SHOW)

            assertEquals(MediaType.TV_SHOW, settings.observeStatsDomain().first())
        }

    @Test
    fun nothingStored_isBooks() =
        runTest {
            assertEquals(MediaType.BOOK, settings.observeStatsDomain().first())
        }

    @Test
    fun anUnknownValue_isBooks() =
        runTest {
            settings.setString("stats_domain", "PODCAST")

            assertEquals(MediaType.BOOK, settings.observeStatsDomain().first())
        }

    @Test
    fun theTab_isAViewPreference_notExported() {
        assertFalse("stats_domain" in EXPORTABLE_SETTING_KEYS)
    }
}
