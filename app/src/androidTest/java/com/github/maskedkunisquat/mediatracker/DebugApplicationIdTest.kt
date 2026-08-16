package com.github.maskedkunisquat.mediatracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the debug `applicationIdSuffix` (see `app/build.gradle.kts`).
 *
 * ### Why this is worth a test at all
 * It began as the Android Studio scaffold's `useAppContext`, which asserted the package name and
 * otherwise proved nothing. That assertion started failing the moment the suffix was added — which
 * made it, accidentally, the only thing in the repository watching a property that genuinely
 * matters. So it is kept and pointed at the real invariant instead of being deleted.
 *
 * The suffix is what keeps debug and release as **separate installed apps**. Without it they share
 * an `applicationId`, are signed with different keys, and installing either over the other forces
 * an uninstall — which wipes the database and every downloaded cover. That is not theoretical: it
 * happened during development and took a real library with it. Losing the suffix would restore that
 * hazard silently, and nothing else would notice.
 *
 * It also means these instrumented tests run against the debug app, so they cannot touch the data
 * of a release build installed as someone's daily driver.
 */
@RunWith(AndroidJUnit4::class)
class DebugApplicationIdTest {
    @Test
    fun debugBuild_installsUnderItsOwnApplicationId() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        assertEquals(
            "the debug build must stay a separate app from release, or installing one wipes the other",
            "com.github.maskedkunisquat.mediatracker.debug",
            packageName,
        )
    }
}
