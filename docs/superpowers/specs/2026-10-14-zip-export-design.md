# One-file export and import: a `.zip` with a manifest (#196)

CSV export writes four files (library, reading logs, episodes, watch logs), each through its own system save dialog, and import mirrors that with four open dialogs. Each media type added a dialog. This change keeps the format and replaces the container: **one `.zip` holding the four CSVs plus a `manifest.json`, with one dialog each way.** Decided with the owner on 2026-10-14. The comments on #196 record why a zip was chosen over a workbook or JSON.

## Decisions

| Question | Decision |
| :--- | :--- |
| Container | One `.zip`. The four CSVs inside are **byte-for-byte today's files**: CSV schema v6 and `CSV_FORMAT.md`'s columns are unchanged. |
| Old loose CSVs | Import accepts **a `.zip`, or a lone `library_export.csv`**, from one dialog. The four-dialog chain is removed. Old exports with several files must be zipped by hand. |
| Manifest | `manifest.json` at the zip's root, **required** in a zip. |
| Settings | Written to the manifest from an **allowlist**. Restored on import **only when the user ticks "Also restore settings"** (off by default). Never credentials. |
| Newer format | A zip whose `formatVersion` is newer than this app understands is **refused**, with nothing written. |
| Row-count mismatch | A **note** in the import summary, not a refusal, because editing a CSV in a spreadsheet and re-zipping is legitimate. |

## Archive

File name: `media-tracker-export-YYYY-MM-DD.zip` (local date).

```
manifest.json
library_export.csv
reading_logs_export.csv
episodes_export.csv
watch_logs_export.csv
```

The CSVs are written from the one `CsvExportBundle` snapshot, exactly as today. All five entries are always present on export, even when a CSV has only its header.

## `manifest.json`

```json
{
  "format": "media-tracker-export",
  "formatVersion": 1,
  "appVersion": "0.25.0",
  "exportedAt": "2026-10-14T03:00:00Z",
  "files": [
    { "name": "library_export.csv", "csvSchemaVersion": 6, "rows": 42 },
    { "name": "reading_logs_export.csv", "csvSchemaVersion": 6, "rows": 17 },
    { "name": "episodes_export.csv", "csvSchemaVersion": 6, "rows": 310 },
    { "name": "watch_logs_export.csv", "csvSchemaVersion": 6, "rows": 311 }
  ],
  "settings": { "include_specials": "true", "week_start_day": "MONDAY", "log_verbosity": "INFO" }
}
```

**Fields:**
- `format` must equal `"media-tracker-export"`.
- `formatVersion` is an `Int`; this change defines `1`.
- `rows` counts **data rows**, excluding the header.
- `appVersion` is passed in from the app (`BuildConfig.VERSION_NAME`), because `shared` cannot see it.
- `exportedAt` comes from the use case's clock.
- `settings` maps stored `app_settings` keys to their stored string values, **only** for keys in the allowlist `EXPORTABLE_SETTING_KEYS = { include_specials, week_start_day, log_verbosity }`. A key that has never been set is omitted.

**Credentials and backfill progress are excluded by construction.** That means `google_books_api_key`, `tmdb_credential` and every `*_backfill_*` key. The allowlist names what goes in rather than what stays out, so a setting added later is excluded until someone decides it belongs.

**JSON:** encoded and decoded with kotlinx.serialization (`ignoreUnknownKeys = true`), so a future version can add fields without breaking older readers.

## Shared layer

All of this lives in `features/portability`.

- **`csv/ExportManifest.kt`:** the `@Serializable` `ExportManifest` / `ExportManifestFile` data classes, `EXPORT_FORMAT = "media-tracker-export"`, `EXPORT_FORMAT_VERSION = 1` and `EXPORTABLE_SETTING_KEYS`.
- **`csv/ExportArchive.kt`:**
  - `encodeExportArchive(bundle, manifest): ByteArray`.
  - `decodeExportArchive(bytes): ArchiveReadResult`, where `ArchiveReadResult` is either `Read(manifest, libraryCsv, readingLogsCsv?, episodesCsv?, watchLogsCsv?)` or `Refused(reason)`.
  - `isZip(bytes): Boolean`, which checks the `PK\u0003\u0004` header.
  - The zip work itself is an `internal expect` pair, `zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray` and `unzipEntries(bytes): Map<String, ByteArray>`. Its `androidMain` and `jvmMain` actuals both use `java.util.zip`.
  - **Refusal reasons:** not a readable zip; no `manifest.json` ("Not a Media Tracker export"); `format` mismatch; `formatVersion > EXPORT_FORMAT_VERSION` ("This export was made by a newer version of the app — update it to import this file"); no `library_export.csv`.
  - Entries are matched by **exact name at the root**. Other entries are ignored.
- **`domain/ExportDataUseCase`:** gains `exportArchive(appVersion: String): Resource<ExportArchiveBytes>`, which builds the bundle as today, counts rows per CSV, reads the allowlisted settings, and returns the zip bytes plus the suggested file name. The existing `execute()` that returns a `CsvExportBundle` stays, because tests and the seed path use it.
- **`domain/ImportDataUseCase`:** gains `importArchive(bytes, duplicatePolicy, restoreSettings: Boolean): Resource<ImportSummary>`.
  - **Zip:** decode it. A refusal becomes `Resource.Error(reason)`. Otherwise call the existing `execute(library, readingLogs, episodes, policy, watchLogs)`.
  - **Not a zip:** treat the bytes as UTF-8 `library_export.csv` and call `execute(library, null, null, policy, null)`.
  - **Afterwards:** compare each CSV's data-row count with the manifest and add a note per mismatch. If `restoreSettings`, write each allowlisted setting present in the manifest through its typed setter, which validates the value. Invalid values and unknown keys are skipped and listed.
- **`ImportSummary`:** gains `notes: List<String> = emptyList()` (row-count mismatches, skipped settings) and `settingsRestored: Int = 0`.
- **Settings restore runs only after the data import succeeds,** and outside its transaction: settings are not library data, and a failed import must change nothing.

## App layer

- **Export (`SettingsCsvExportFlow.kt`):** one `CreateDocument("application/zip")` named `media-tracker-export-YYYY-MM-DD.zip`. The bytes are written on `Dispatchers.IO`, with success, cancelled and failure messages as today. The pending bytes wait in `ExportViewModel` state (`ExportUiState.AwaitingDestination`), the pattern #162 established for backup, so a rotation inside the dialog neither loses them nor reopens the dialog. After process death, the empty document is deleted and the user is told the export expired.
- **Import (`SettingsImportFlows.kt`):** one `OpenDocument(arrayOf("application/zip", "text/*", "application/octet-stream"))`. The bytes are read on IO and passed to `ImportViewModel.importArchive(bytes, policy, restoreSettings)`. **"Also restore settings"** is a checkbox beside the existing duplicate-policy choice, off by default, and is passed through.
- **Import summary dialog:** shows `notes` and "N settings restored" when non-zero.
- **Removed:** the four-launcher chains on both sides, with their pending-state variables. The Goodreads import is unchanged.
- **Strings:** the new user-facing messages above.

## Docs

- **`docs/sample-data/CSV_FORMAT.md`:** a new **"Container: the export `.zip`"** section with the layout, the manifest fields, the refusal rules, the settings allowlist and the lone-CSV fallback. The per-file column docs are unchanged.
- **Sample data:** `docs/sample-data` stays four loose CSVs. `SampleDataSeedTest` and `seedDebugDevice` keep importing them through `ImportDataUseCase.execute`, not through the dialog.
- **README and CHANGELOG:** updated. Under **Changed**: export and import are one file and one dialog. Under **Added**: settings can be restored.

## Testing

- **jvmTest (`ExportArchiveTest`, `ZipImportExportTest`):**
  - The round trip is lossless: export to a zip, import into an empty database, and compare. This reuses the `WatchLogImportExportTest` fixtures.
  - The manifest's row counts and allowlisted settings are correct, and no credential key appears even when credentials are set. That last check is a positive control: credentials **are** stored, and they are absent from the manifest.
  - Refusals: not a zip but not CSV either; a zip without a manifest; a wrong `format`; `formatVersion = 2`, refused with nothing written (the library is still empty afterwards); a zip without `library_export.csv`.
  - Optional files missing still import.
  - Tampering with a CSV's row count yields a note, not a refusal.
  - A lone library CSV imports.
  - `restoreSettings = false` changes no setting; `true` applies all three; an invalid value is skipped and noted.
- **commonTest:** `ExportViewModel`'s `AwaitingDestination` and take-once behaviour, matching `BackupViewModelTest`; and `ImportViewModel.importArchive` passing its arguments through.
- **androidTest (`SettingsRouteSafChainsTest`):**
  - Export is one launch, and the zip written to the destination decodes.
  - Import of that zip shows the summary.
  - Cancel at either dialog.
  - Recreation while the export dialog is open neither reopens it nor loses the bytes (`StateRestorationTester`, as in #162).
- **Device:** export, pull the zip, list its entries and read `manifest.json`, then import into the reseeded app with SKIP (nothing duplicated) and with "Also restore settings".

## Out of scope

- Importing several loose CSVs at once (zip them by hand).
- Streaming very large libraries; the zip is built in memory, which is fine at this app's scale.
- Settings outside the allowlist.
- The `.sqlite` backup, which is unchanged.
