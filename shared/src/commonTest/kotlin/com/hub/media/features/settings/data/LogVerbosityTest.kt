package com.hub.media.features.settings.data

import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Covers the log-verbosity preference wrapper (ROADMAP Task 15 Phase B2). The interesting case is
 * the never-set one: [observeLogVerbosityOrNull] must keep it distinguishable from an explicit
 * choice, because the app module wires `persisted ?: buildTypeDefault` and collapsing the two would
 * silence every debug build shortly after startup. See that function's KDoc.
 *
 * Room-backed, so it lives in the already-excluded `com.hub.media.features.settings.*` package (see
 * `shared/build.gradle.kts`) and runs on `:shared:jvmTest`, exactly like [SettingsRepositoryTest].
 */
class LogVerbosityTest {

    @Test
    fun getLogVerbosity_neverSet_fallsBackToTheLifecycleTracingDefault() = runTest {
        val db = testAppDatabase()
        val repo = SettingsRepository(db.appSettingsDao())

        assertEquals(DEFAULT_LOG_VERBOSITY, repo.getLogVerbosity())
        assertEquals(DEFAULT_LOG_VERBOSITY, repo.observeLogVerbosity().first())
        // INFO since ROADMAP Task 15 Phase C. WARN was right while nothing emitted below it -- but
        // it meant a healthy app wrote nothing at all, and an empty log viewer was read as broken
        // rather than as good news. Pinned rather than merely derived, because widening this
        // default is exactly the kind of change that should require saying so out loud: it decides
        // what the app writes to disk unprompted.
        assertEquals(LogLevel.INFO, DEFAULT_LOG_VERBOSITY)
        db.close()
    }

    @Test
    fun observeLogVerbosityOrNull_neverSet_isNullSoTheBuildTypeDefaultSurvives() = runTest {
        val db = testAppDatabase()
        val repo = SettingsRepository(db.appSettingsDao())

        assertNull(
            repo.observeLogVerbosityOrNull().first(),
            "never-set must stay distinguishable from an explicit WARN, or a debug build falls " +
                "silent the moment this preference loads",
        )
        // Positive control: the same repository DOES report a value once one is set, so the null
        // above is genuinely "unset" rather than the accessor being broken.
        repo.setLogVerbosity(LogLevel.ERROR)
        assertEquals(LogLevel.ERROR, repo.observeLogVerbosityOrNull().first())
        db.close()
    }

    @Test
    fun setLogVerbosity_everyLevel_roundTripsThroughTheStore() = runTest {
        val db = testAppDatabase()
        val repo = SettingsRepository(db.appSettingsDao())

        for (level in LogLevel.entries) {
            repo.setLogVerbosity(level)
            assertEquals(level, repo.getLogVerbosity(), "round trip failed for $level")
            assertEquals(level, repo.observeLogVerbosity().first())
        }
        db.close()
    }

    @Test
    fun getLogVerbosity_storedValueNoLongerAValidLevel_isTreatedAsNeverSet() = runTest {
        val db = testAppDatabase()
        val repo = SettingsRepository(db.appSettingsDao())

        // Written by a hypothetical future version, or corrupted. Must not throw, and must not
        // leave the app running at an unpredictable level.
        repo.setString("log_verbosity", "TRACE")

        assertEquals(DEFAULT_LOG_VERBOSITY, repo.getLogVerbosity())
        // Both accessors must agree that malformed means unset: the nullable one reports it as
        // such, the non-null one substitutes the default rather than propagating garbage.
        assertEquals(DEFAULT_LOG_VERBOSITY, repo.observeLogVerbosity().first())
        assertNull(repo.observeLogVerbosityOrNull().first(), "malformed is unset, not a level")
        // Positive control: the malformed value really is in the store, so the assertions above
        // are about parsing it rather than about nothing having been written.
        assertNotNull(repo.getString("log_verbosity"))
        db.close()
    }
}
