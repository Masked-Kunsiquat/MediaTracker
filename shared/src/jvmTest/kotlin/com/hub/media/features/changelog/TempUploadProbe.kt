package com.hub.media.features.changelog

import kotlin.test.Test
import kotlin.test.assertTrue

// TEMPORARY -- deliberate failure to exercise the `if: failure()` artifact uploads. Removed before merge.
class TempUploadProbe {
    @Test
    fun deliberateFailure_toProveTheTestReportUploadRuns() {
        assertTrue(false, "deliberate: proving the test-reports artifact upload actually runs")
    }
}
