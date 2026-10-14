package com.hub.media.features.portability.domain

import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.EXPORTABLE_SETTING_KEYS
import com.hub.media.features.portability.csv.ExportArchive
import com.hub.media.features.portability.csv.ExportManifest
import com.hub.media.features.portability.csv.ExportManifestFile
import com.hub.media.features.settings.data.SettingsRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** The export as one file: the zip's bytes and the name the save dialog suggests. */
public class ExportArchiveFile(
    public val bytes: ByteArray,
    public val suggestedFileName: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ExportArchiveFile && suggestedFileName == other.suggestedFileName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + suggestedFileName.hashCode()
}

/** Produces the one-file export. An interface so ViewModel tests can fake it without Room. */
public interface ArchiveExporter {
    /** @param appVersion Recorded in the manifest; `shared` cannot see the app's build config. */
    public suspend fun export(appVersion: String): Resource<ExportArchiveFile>
}

/**
 * The one-file export (#196): today's four CSVs from one [ExportUseCase] snapshot, unchanged, plus a
 * `manifest.json` recording what made them, how many rows each holds, and the allowlisted settings.
 * Composes [ExportUseCase] rather than replacing it, so the CSV export stays exactly what it was.
 */
public class ExportArchiveUseCase(
    private val exportUseCase: ExportUseCase,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val logger: Logger = AppLogger,
) : ArchiveExporter {
    override suspend fun export(appVersion: String): Resource<ExportArchiveFile> {
        val bundle =
            when (val result = exportUseCase.execute()) {
                is Resource.Error -> return result
                is Resource.Success -> result.data
            }
        return try {
            val now = clock.now()
            val files =
                listOf(
                    ExportArchive.LIBRARY to bundle.libraryCsv,
                    ExportArchive.READING_LOGS to bundle.readingLogsCsv,
                    ExportArchive.EPISODES to bundle.episodesCsv,
                    ExportArchive.WATCH_LOGS to bundle.watchLogsCsv,
                ).map { (name, csv) -> ExportManifestFile(name, CSV_SCHEMA_VERSION, ExportArchive.dataRowCount(csv)) }
            // Stored values as they are, for allowlisted keys only; one never set is left out.
            val settings =
                EXPORTABLE_SETTING_KEYS
                    .mapNotNull { key ->
                        settingsRepository.getString(key)?.let { key to it }
                    }.toMap()
            val manifest =
                ExportManifest(appVersion = appVersion, exportedAt = now.toString(), files = files, settings = settings)
            Resource.Success(
                ExportArchiveFile(
                    bytes = ExportArchive.encode(bundle, manifest),
                    suggestedFileName = "media-tracker-export-${now.toLocalDateTime(zone).date}.zip",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Building the export archive failed" }
            Resource.Error("Export failed: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    private companion object {
        const val TAG = "ExportArchive"
    }
}
