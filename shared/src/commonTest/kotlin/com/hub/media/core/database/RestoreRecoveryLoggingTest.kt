package com.hub.media.core.database

import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #115: the launch after a restore is the first moment the persistent log store is alive again. */
class RestoreRecoveryLoggingTest {
    @Test
    fun ordinaryLaunch_logsNothing() {
        val logger = RecordingLogger()
        logRestoreRecovery(heal = SelfHealResult.NotNeeded, marker = null, logger = logger)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun heal_logsOneWarning() {
        val logger = RecordingLogger()
        logRestoreRecovery(heal = SelfHealResult.Healed, marker = null, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertTrue("pre-restore backup was moved back" in entry.message, entry.message)
    }

    @Test
    fun successMarker_logsOneInfo() {
        val logger = RecordingLogger()
        logRestoreRecovery(heal = SelfHealResult.NotNeeded, marker = RestoreMarker.Success, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertTrue("restore completed before this launch" in entry.message, entry.message)
    }

    @Test
    fun failureMarker_logsOneErrorCarryingTheMessage() {
        val logger = RecordingLogger()
        logRestoreRecovery(
            heal = SelfHealResult.NotNeeded,
            marker = RestoreMarker.Failure("Restore aborted: disk full."),
            logger = logger,
        )
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertTrue("Restore aborted: disk full." in entry.message, entry.message)
    }

    @Test
    fun healAndFailure_logWarnThenError() {
        val logger = RecordingLogger()
        logRestoreRecovery(heal = SelfHealResult.Healed, marker = RestoreMarker.Failure("x"), logger = logger)
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), logger.entries.map { it.level })
    }

    @Test
    fun failedHeal_logsOneErrorNamingWhereTheBackupWasLeft() {
        // The one restore incident that loses the user's library from view: the backup could not be
        // moved back, and Room will create an empty database at the live path next (#115 review).
        val logger = RecordingLogger()
        logRestoreRecovery(heal = SelfHealResult.Failed("/data/app/db.pre-restore-bak"), marker = null, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertTrue("/data/app/db.pre-restore-bak" in entry.message, entry.message)
    }
}
