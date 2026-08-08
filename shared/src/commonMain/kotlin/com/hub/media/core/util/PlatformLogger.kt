package com.hub.media.core.util

/**
 * Platform entry point for the real (non-test) [Logger] sink -- the `expect`/`actual` seam
 * [Logger]'s KDoc describes, following the same free-function `expect`/`actual` shape
 * [DatabaseFileOps.kt][com.hub.media.core.database] already uses for its platform primitives
 * (`internal expect suspend fun ...`), rather than [DatabaseFactory][com.hub.media.core.database.DatabaseFactory]'s
 * `expect class` shape: no platform handle (e.g. `Context`) is needed to construct either
 * implementation, so a plain factory function is enough. `internal` (module-wide, not
 * package-private): only [AppLogger] in this same `shared` module ever calls it directly.
 *
 * @return A fresh [Logger] writing to `android.util.Log` on Android, stdout/stderr on JVM. Neither
 *   implementation retains platform state worth caching, so returning a plain object each call is
 *   fine -- see each actual's KDoc.
 */
internal expect fun platformLogger(): Logger
