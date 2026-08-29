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
 * [SettingsRepository.observeGoogleBooksApiKey]/[SettingsRepository.getGoogleBooksApiKey]/
 * [SettingsRepository.setGoogleBooksApiKey]/[SettingsRepository.clearGoogleBooksApiKey] tests
 * against a real in-memory [AppDatabase], mirroring [WeekStartDayTest]'s style. Room-backed, so
 * excluded from the android unit-test variant by the `com.hub.media.features.settings.*` package
 * filter in `shared/build.gradle.kts` -- `:shared:jvmTest` is the authoritative gate.
 *
 * None of these assertions print the key values they exercise -- only equality checks against
 * literals already present in this file's source, consistent with the credential handling rule
 * documented on [SettingsRepository.observeGoogleBooksApiKey].
 */
class ProviderApiKeysTest {
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
    fun getGoogleBooksApiKey_neverSet_returnsNull() =
        runTest {
            assertNull(repo.getGoogleBooksApiKey())
        }

    @Test
    fun observeGoogleBooksApiKey_neverSet_returnsNull() =
        runTest {
            assertNull(repo.observeGoogleBooksApiKey().first())
        }

    @Test
    fun setGoogleBooksApiKey_thenGetGoogleBooksApiKey_roundTrips() =
        runTest {
            repo.setGoogleBooksApiKey("test-key-123")
            assertEquals("test-key-123", repo.getGoogleBooksApiKey())
        }

    @Test
    fun setGoogleBooksApiKey_blankValue_clearsKey() =
        runTest {
            repo.setGoogleBooksApiKey("test-key-123")
            repo.setGoogleBooksApiKey("")
            assertNull(repo.getGoogleBooksApiKey())
        }

    @Test
    fun setGoogleBooksApiKey_whitespaceOnlyValue_clearsKey() =
        runTest {
            repo.setGoogleBooksApiKey("test-key-123")
            repo.setGoogleBooksApiKey("   \t  ")
            assertNull(repo.getGoogleBooksApiKey())
        }

    @Test
    fun setGoogleBooksApiKey_surroundingWhitespace_isTrimmed() =
        runTest {
            repo.setGoogleBooksApiKey("  test-key-123  ")
            assertEquals("test-key-123", repo.getGoogleBooksApiKey())
        }

    @Test
    fun clearGoogleBooksApiKey_removesStoredKey() =
        runTest {
            repo.setGoogleBooksApiKey("test-key-123")
            repo.clearGoogleBooksApiKey()
            assertNull(repo.getGoogleBooksApiKey())
        }

    @Test
    fun setGoogleBooksApiKey_thenObserveGoogleBooksApiKey_emitsNewValue() =
        runTest {
            repo.setGoogleBooksApiKey("test-key-123")
            assertEquals("test-key-123", repo.observeGoogleBooksApiKey().first())
        }
}
