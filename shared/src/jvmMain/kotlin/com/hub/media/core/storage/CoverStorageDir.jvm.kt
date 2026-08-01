package com.hub.media.core.storage

import java.io.File

/**
 * The on-disk directory cover images are stored in for the JVM/desktop target:
 * `~/.mediatracker/covers`, mirroring the Android `filesDir/covers` convention under the same
 * `~/.mediatracker` app-data root used by `DatabaseFactory` for the database file.
 */
public fun coverStorageDirectory(): String =
    File(File(System.getProperty("user.home"), ".mediatracker"), "covers").absolutePath
