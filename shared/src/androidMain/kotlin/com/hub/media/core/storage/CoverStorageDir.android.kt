package com.hub.media.core.storage

import android.content.Context
import java.io.File

/**
 * The on-device directory cover images are stored in: `<filesDir>/covers`, i.e. the app's
 * private, non-cache internal storage per AGENTS.md §4. Not created here — the directory is
 * created lazily on first write by [LocalImageStorageManager].
 */
public fun coverStorageDirectory(context: Context): String =
    File(context.applicationContext.filesDir, "covers").absolutePath
