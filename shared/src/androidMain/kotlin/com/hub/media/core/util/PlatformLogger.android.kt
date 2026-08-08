package com.hub.media.core.util

import android.util.Log

/**
 * Android [platformLogger]: routes to `android.util.Log` (logcat), the standard on-device sink --
 * no additional dependency, and (per [Logger]'s "no crash-reporting service" rule) purely local.
 * `Log.d`/`Log.i`/`Log.w`/`Log.e` each accept a nullable `Throwable` directly, so Android's own
 * stack-trace formatting is used as-is rather than hand-rolled.
 */
internal actual fun platformLogger(): Logger = AndroidLogger

private object AndroidLogger : Logger {
    override fun log(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String) {
        val text = message()
        // A logging call must never itself become a new source of failure for its caller -- swallow
        // whatever android.util.Log throws rather than propagate it. This matters beyond defensive
        // style: shared/build.gradle.kts's testDebugUnitTest/testReleaseUnitTest variants run on the
        // host JVM against Android's *stub* android.jar (no Robolectric wired into this project --
        // see that file's comment on the pre-existing Context-dependent test exclusions), where every
        // android.util.Log method throws "not mocked" unconditionally. Without this try/catch, simply
        // adding a logger.warn/error call to already-tested production code (as this task's adoption
        // sites do) would newly break that test variant for code that has nothing to do with Context/
        // Room -- silently turning a logging call into a crash is wrong on a real device too.
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, text, throwable)
                LogLevel.INFO -> Log.i(tag, text, throwable)
                LogLevel.WARN -> Log.w(tag, text, throwable)
                LogLevel.ERROR -> Log.e(tag, text, throwable)
            }
        } catch (_: Throwable) {
            // Best-effort only -- see above.
        }
    }
}
