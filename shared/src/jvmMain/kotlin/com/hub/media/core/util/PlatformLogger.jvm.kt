package com.hub.media.core.util

/**
 * JVM [platformLogger]: writes to stdout (`DEBUG`/`INFO`) or stderr (`WARN`/`ERROR`), matching
 * [DatabaseFactory.jvm.kt][com.hub.media.core.database.DatabaseFactory]'s treatment of the JVM
 * target as a real (if secondary/dev-facing) deployment target rather than test-only scaffolding --
 * no console-logging dependency needed for a surface this small.
 */
internal actual fun platformLogger(): Logger = JvmLogger

private object JvmLogger : Logger {
    override fun log(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String) {
        val stream = if (level == LogLevel.WARN || level == LogLevel.ERROR) System.err else System.out
        stream.println("${level.name} [$tag] ${message()}")
        throwable?.printStackTrace(stream)
    }
}
