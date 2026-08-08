package com.hub.media.core.storage

import android.content.Context
import java.io.File

/**
 * The on-device directory persistent log files are stored in (ROADMAP Task 15 Phase B):
 * `<filesDir>/logs`, exactly mirroring [coverStorageDirectory]'s `<filesDir>/covers` convention --
 * the app's private, non-cache internal storage. Not created here -- lazily created on first write
 * by [LogFileStore]/`writeFileBytes`, mirroring [coverStorageDirectory]'s own "not created here"
 * note.
 *
 * **This exact directory name (`logs`) is a fixed contract.** The backup/export exclusion rules
 * (`dataExtractionRules`/`backup_rules`, and the explicit carve-outs in the `.sqlite` backup and
 * CSV export code paths -- ROADMAP Task 15 Phase B "Must be excluded from backup and export") are
 * a separate workstream from this one and key off this directory by name/path. Do not rename it
 * without updating every dependent path.
 */
public fun logStorageDirectory(context: Context): String =
    File(context.applicationContext.filesDir, "logs").absolutePath
