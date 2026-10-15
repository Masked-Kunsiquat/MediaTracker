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
        logRestoreRecovery(healed = false, marker = null, logger = logger)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun heal_logsOneWarning() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = true, marker = null, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertTrue("pre-restore backup was moved back" in entry.message, entry.message)
    }

    @Test
    fun successMarker_logsOneInfo() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = false, marker = RestoreMarker.Success, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertTrue("restore completed before this launch" in entry.message, entry.message)
    }

    @Test
    fun failureMarker_logsOneErrorCarryingTheMessage() {
        val logger = RecordingLogger()
        logRestoreRecovery(
            healed = false,
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
        logRestoreRecovery(healed = true, marker = RestoreMarker.Failure("x"), logger = logger)
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), logger.entries.map { it.level })
    }
}
