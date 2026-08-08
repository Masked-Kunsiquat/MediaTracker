package com.hub.media.core.storage

import java.io.File

/**
 * The on-disk directory persistent log files are stored in for the JVM/desktop target:
 * `~/.mediatracker/logs`, mirroring [coverStorageDirectory]'s JVM sibling and the same
 * `~/.mediatracker` app-data root [com.hub.media.core.database.DatabaseFactory] uses for the
 * database file (ROADMAP Task 15 Phase B).
 */
public fun logStorageDirectory(): String =
    File(File(System.getProperty("user.home"), ".mediatracker"), "logs").absolutePath
