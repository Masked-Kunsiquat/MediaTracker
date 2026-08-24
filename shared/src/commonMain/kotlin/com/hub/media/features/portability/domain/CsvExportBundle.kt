package com.hub.media.features.portability.domain

/**
 * All three generated CSV documents from one [ExportDataUseCase.execute] call (ROADMAP Task 8 Phase
 * A; `episodesCsv` added ROADMAP Task 13 Phase C), bundled together rather than returned as
 * separate results so the app layer writes every file from a single, consistent database snapshot
 * -- generating them from independent calls (with, say, a user picking a save location in between)
 * would risk a book added/edited between the reads leaving `library_export.csv`,
 * `reading_logs_export.csv`, and `episodes_export.csv` inconsistent with each other.
 *
 * @property libraryCsv Full text of `library_export.csv` ([com.hub.media.features.portability.csv.LibraryCsvExporter]).
 * @property readingLogsCsv Full text of `reading_logs_export.csv` ([com.hub.media.features.portability.csv.ReadingLogCsvExporter]).
 * @property episodesCsv Full text of `episodes_export.csv` ([com.hub.media.features.portability.csv.EpisodeCsvExporter]).
 */
public data class CsvExportBundle(
    val libraryCsv: String,
    val readingLogsCsv: String,
    val episodesCsv: String,
)
