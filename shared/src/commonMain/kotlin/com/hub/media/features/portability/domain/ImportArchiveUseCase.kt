package com.hub.media.features.portability.domain

import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.features.portability.csv.ArchiveReadResult
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION_COLUMN
import com.hub.media.features.portability.csv.ExportArchive
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.setIncludeSpecials
import com.hub.media.features.settings.data.setLogVerbosity
import com.hub.media.features.settings.data.setWeekStartDay

/** Imports the one-file export. An interface so ViewModel tests can fake it without Room. */
public interface ArchiveImporter {
    public suspend fun import(
        bytes: ByteArray,
        duplicatePolicy: DuplicatePolicy,
        restoreSettings: Boolean,
    ): Resource<ImportSummary>
}

/**
 * Imports what the one-dialog import picked (#196): an export `.zip`, or a lone
 * `library_export.csv`. A zip is refused -- with nothing written -- if it is not ours, or newer than
 * this app understands. Otherwise its CSVs go through the unchanged [ImportUseCase], and then:
 * - a file whose row count differs from the manifest's is a **note**, not a failure, because editing
 *   an export in a spreadsheet and re-zipping it is legitimate;
 * - settings are restored only when [import]'s `restoreSettings` asks, only for the allowlisted
 *   keys, only through their typed setters, and only after the data import succeeded.
 */
public class ImportArchiveUseCase(
    private val importUseCase: ImportUseCase,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = AppLogger,
) : ArchiveImporter {
    override suspend fun import(
        bytes: ByteArray,
        duplicatePolicy: DuplicatePolicy,
        restoreSettings: Boolean,
    ): Resource<ImportSummary> {
        if (!ExportArchive.isZip(bytes)) {
            // A lone CSV: only a library export qualifies. Anything else is refused outright rather than
            // fed to the importer to be rejected row by row.
            val text = bytes.decodeToString()
            if (!text.removePrefix("\uFEFF").startsWith(CSV_SCHEMA_VERSION_COLUMN)) {
                return Resource.Error(ExportArchive.NOT_OURS)
            }
            return importUseCase.execute(text, null, null, duplicatePolicy, null)
        }
        val read =
            when (val decoded = ExportArchive.decode(bytes)) {
                is ArchiveReadResult.Refused -> return Resource.Error(decoded.reason)
                is ArchiveReadResult.Read -> decoded
            }
        val summary =
            when (
                val result =
                    importUseCase.execute(
                        read.libraryCsv,
                        read.readingLogsCsv,
                        read.episodesCsv,
                        duplicatePolicy,
                        read.watchLogsCsv,
                    )
            ) {
                is Resource.Error -> return result
                is Resource.Success -> result.data
            }

        val notes = mutableListOf<String>()
        val present =
            mapOf(
                ExportArchive.LIBRARY to read.libraryCsv,
                ExportArchive.READING_LOGS to read.readingLogsCsv,
                ExportArchive.EPISODES to read.episodesCsv,
                ExportArchive.WATCH_LOGS to read.watchLogsCsv,
            )
        for (file in read.manifest.files) {
            val csv = present[file.name] ?: continue
            val actual = ExportArchive.dataRowCount(csv)
            if (actual != file.rows) notes += "${file.name} has $actual rows; the export recorded ${file.rows}"
        }

        var restored = 0
        if (restoreSettings) {
            for ((key, value) in read.manifest.settings) {
                val applied =
                    when (key) {
                        "include_specials" ->
                            value.toBooleanStrictOrNull()?.let {
                                settingsRepository
                                    .setIncludeSpecials(
                                        it,
                                    )
                            }
                        "week_start_day" ->
                            WeekStartDay.entries.firstOrNull { it.name == value }?.let {
                                settingsRepository
                                    .setWeekStartDay(
                                        it,
                                    )
                            }
                        "log_verbosity" ->
                            LogLevel.entries.firstOrNull { it.name == value }?.let {
                                settingsRepository
                                    .setLogVerbosity(
                                        it,
                                    )
                            }
                        else -> {
                            notes += "Setting \"$key\" is not restored by this app"
                            continue
                        }
                    }
                if (applied ==
                    null
                ) {
                    notes += "Setting \"$key\" had an invalid value and was not restored"
                } else {
                    restored++
                }
            }
            if (restored > 0) logger.info(TAG) { "Restored $restored setting(s) from an export" }
        }
        return Resource.Success(summary.copy(notes = summary.notes + notes, settingsRestored = restored))
    }

    private companion object {
        const val TAG = "ImportArchive"
    }
}
