package com.hub.media.features.changelog

import kotlin.test.*

// TEMPORARY -- deliberate failure to exercise the `if: failure()` artifact uploads, which no green run has ever executed. Removed before merge.
class TempUploadProbe {
    @Test
    fun deliberateFailure_toProveTheTestReportUploadRuns() {
        assertTrue(false, "deliberate: proving the test-reports artifact upload actually runs")
    }
}
