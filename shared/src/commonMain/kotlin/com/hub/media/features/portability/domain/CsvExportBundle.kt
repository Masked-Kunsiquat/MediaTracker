package com.hub.media.features.portability.domain

/**
 * Both generated CSV documents from one [ExportDataUseCase.execute] call (ROADMAP Task 8 Phase A),
 * bundled together rather than returned as two separate results so the app layer writes both
 * files from a single, consistent database snapshot -- generating them from two independent calls
 * (with, say, a user picking a save location in between) would risk a book added/edited between
 * the two reads leaving `library_export.csv` and `reading_logs_export.csv` inconsistent with each
 * other.
 *
 * @property libraryCsv Full text of `library_export.csv` ([com.hub.media.features.portability.csv.LibraryCsvExporter]).
 * @property readingLogsCsv Full text of `reading_logs_export.csv` ([com.hub.media.features.portability.csv.ReadingLogCsvExporter]).
 */
public data class CsvExportBundle(
    val libraryCsv: String,
    val readingLogsCsv: String,
)
