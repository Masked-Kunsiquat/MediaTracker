package com.hub.media.features.portability.csv

import kotlinx.serialization.Serializable

/** The `format` every export archive's manifest names (#196). Anything else is not one of ours. */
public const val EXPORT_FORMAT: String = "media-tracker-export"

/**
 * The newest archive format this app reads. An archive claiming a higher version was made by a newer
 * app and is refused rather than guessed at.
 */
public const val EXPORT_FORMAT_VERSION: Int = 1

/**
 * The only `app_settings` keys an export carries. An allowlist rather than a denylist, so a setting
 * added later -- above all a credential -- stays out until someone decides it belongs.
 */
public val EXPORTABLE_SETTING_KEYS: List<String> =
    listOf("include_specials", "week_start_day", "log_verbosity", "reading_goal")

/** One CSV inside the archive, as the export recorded it. [rows] counts data rows, not the header. */
@Serializable
public data class ExportManifestFile(
    val name: String,
    val csvSchemaVersion: Int,
    val rows: Int,
)

/**
 * `manifest.json`, the first thing an import reads (#196): what made the archive, what is in it, and
 * the allowlisted settings. Decoded with unknown keys ignored, so a later version can add fields.
 */
@Serializable
public data class ExportManifest(
    val format: String = EXPORT_FORMAT,
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    val appVersion: String,
    val exportedAt: String,
    val files: List<ExportManifestFile>,
    val settings: Map<String, String> = emptyMap(),
)
