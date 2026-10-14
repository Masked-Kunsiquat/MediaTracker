# One-File Export and Import (.zip) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export writes one `.zip` (four unchanged CSVs plus `manifest.json`) through one save dialog. Import reads that zip, or a lone library CSV, through one open dialog, optionally restoring allowlisted settings.

**Architecture:**
- **Archive codec:** a pure archive codec in `shared/.../portability/csv`, using `java.util.zip` through a tiny `expect`/`actual`.
- **Two use cases that compose the existing ones,** so `ExportDataUseCase` / `ImportDataUseCase` and their many test constructions stay untouched: `ExportArchiveUseCase` wraps `ExportUseCase` and `ImportArchiveUseCase` wraps `ImportUseCase`.
- **ViewModels:** the export bytes wait in `ExportViewModel` (the #162 pattern).
- **Removed:** the four-dialog chains are deleted.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization (already a `shared` dependency), `java.util.zip`, Room (jvmTest), Compose, and Robolectric plus androidTest.

**Spec:** `docs/superpowers/specs/2026-10-14-zip-export-design.md`

## Global Constraints

- **CSV content and schema are unchanged:** byte-for-byte today's four CSVs, `CsvSchemaVersion` stays 6, and `APP_DATABASE_VERSION` stays 7.
- **No new dependencies.**
- `EXPORT_FORMAT = "media-tracker-export"` and `EXPORT_FORMAT_VERSION = 1`.
- **Zip entry names, at the root:** `manifest.json`, `library_export.csv`, `reading_logs_export.csv`, `episodes_export.csv`, `watch_logs_export.csv`.
- `EXPORTABLE_SETTING_KEYS = listOf("include_specials", "week_start_day", "log_verbosity")`. **Never** credentials (`google_books_api_key`, `tmdb_credential`) or `*_backfill_*` keys.
- **Refusal messages,** copied verbatim:
  - *"Not a Media Tracker export"*
  - *"This export was made by a newer version of the app — update it to import this file"*
  - *"This export has no library_export.csv"*
  - *"Couldn't read this file"*
- **Restoring settings is opt-in:** "Also restore settings", off by default. It is applied only after the data import succeeds.
- **The suggested file name** is `media-tracker-export-YYYY-MM-DD.zip`, using the local date.
- **The gate before every push,** chained with `&&`: `./gradlew ktlintFormat`, then `:shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug`.

## Review Focus

1. **A zip whose manifest is not valid JSON** (truncated or hand-edited) must be refused with "Not a Media Tracker export" and write nothing, not crash. Pinned in Task 1 (`malformedManifest_isRefused`).
2. **A UTF-8 BOM, or CRLF line endings, in a CSV** inside a zip that went through a spreadsheet must still import, and must not trigger a false row-count note. Pinned in Task 3 (`crlfAndBom_importCleanly`).
3. **Picking something that is neither a zip nor a library CSV** (a photo, a Goodreads CSV) must give a refusal or rejections, never a partial import of garbage. Pinned in Task 3 (`aNonCsvFile_isRefused`).
4. **A refused newer `formatVersion` must also leave settings untouched,** even with "restore settings" ticked. Pinned in Task 3 (`newerVersion_withRestoreTicked_changesNothing`).
5. **Rotation inside the export dialog** must neither reopen it nor lose the bytes (the #162 class of bug). Pinned in Task 5 (`export_recreatedWhileThePickerIsOpen_…`).

---

### Task 1: Manifest model and archive codec

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ExportManifest.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ExportArchive.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ZipEntries.kt` (`internal expect`)
- Create: `shared/src/androidMain/kotlin/com/hub/media/features/portability/csv/ZipEntries.android.kt`, `shared/src/jvmMain/kotlin/com/hub/media/features/portability/csv/ZipEntries.jvm.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/portability/csv/ExportArchiveTest.kt`

**Interfaces:** this task produces the following.

```kotlin
public const val EXPORT_FORMAT: String = "media-tracker-export"
public const val EXPORT_FORMAT_VERSION: Int = 1
public val EXPORTABLE_SETTING_KEYS: List<String> = listOf("include_specials", "week_start_day", "log_verbosity")

@Serializable public data class ExportManifestFile(val name: String, val csvSchemaVersion: Int, val rows: Int)
@Serializable public data class ExportManifest(
    val format: String = EXPORT_FORMAT,
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    val appVersion: String,
    val exportedAt: String,                       // ISO-8601 UTC
    val files: List<ExportManifestFile>,
    val settings: Map<String, String> = emptyMap(),
)

public object ExportArchive {
    public const val MANIFEST: String = "manifest.json"
    public const val LIBRARY: String = "library_export.csv"
    public const val READING_LOGS: String = "reading_logs_export.csv"
    public const val EPISODES: String = "episodes_export.csv"
    public const val WATCH_LOGS: String = "watch_logs_export.csv"
    public fun encode(bundle: CsvExportBundle, manifest: ExportManifest): ByteArray
    public fun decode(bytes: ByteArray): ArchiveReadResult
    public fun isZip(bytes: ByteArray): Boolean      // starts with 'P','K',3,4
    public fun dataRowCount(csv: String): Int       // non-blank lines minus the header; tolerant of CRLF and BOM
}
public sealed interface ArchiveReadResult {
    public data class Read(val manifest: ExportManifest, val libraryCsv: String, val readingLogsCsv: String?, val episodesCsv: String?, val watchLogsCsv: String?) : ArchiveReadResult
    public data class Refused(val reason: String) : ArchiveReadResult
}
internal expect fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray
internal expect fun unzipEntries(bytes: ByteArray): Map<String, ByteArray>   // throws on unreadable input
```

`dataRowCount` counts *records*, not lines, and must agree with the importer. CSVs can hold quoted newlines (e.g. synopses), so it counts via `CsvTableReader.read(csv, header = null)` if that overload exists, or otherwise by counting rows from the existing CSV reader. Check `CsvTableReader`'s API and use the reader the importers use. **Do not** count `\n`. If the reader needs the header, pass the matching exporter's `HEADER`. That makes the signature `dataRowCount(csv: String, header: List<String>): Int`; record a Ruling if so.

- [ ] **Step 1: Write the failing tests** in `ExportArchiveTest`:

```kotlin
class ExportArchiveTest {
    private val bundle = CsvExportBundle(libraryCsv = "h\nr1\nr2\n", readingLogsCsv = "h\n", episodesCsv = "h\nr\n", watchLogsCsv = "h\n")
    // Build the bundle with the real exporters' HEADERs if CsvExportBundle's fields need valid CSV for dataRowCount.
    private val manifest = ExportManifest(appVersion = "0.25.0", exportedAt = "2026-10-14T03:00:00Z",
        files = listOf(ExportManifestFile("library_export.csv", 6, 2)), settings = mapOf("include_specials" to "false"))

    @Test fun roundTrip_keepsEveryFileByteForByte_andTheManifest() {
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(ExportArchive.encode(bundle, manifest)))
        assertEquals(bundle.libraryCsv, read.libraryCsv); assertEquals(bundle.watchLogsCsv, read.watchLogsCsv)
        assertEquals(manifest, read.manifest)
    }
    @Test fun isZip_checksTheHeader() { assertTrue(ExportArchive.isZip(ExportArchive.encode(bundle, manifest))); assertFalse(ExportArchive.isZip("csv_schema_version,".encodeToByteArray())) }
    @Test fun noManifest_isRefused() = assertRefused(zipEntries(listOf("library_export.csv" to bundle.libraryCsv.encodeToByteArray())), "Not a Media Tracker export")
    @Test fun malformedManifest_isRefused() = assertRefused(zipEntries(listOf("manifest.json" to "{\"format\":".encodeToByteArray(), "library_export.csv" to "h\n".encodeToByteArray())), "Not a Media Tracker export")
    @Test fun wrongFormat_isRefused() = assertRefused(zipWith(manifest.copy(format = "something-else")), "Not a Media Tracker export")
    @Test fun newerFormatVersion_isRefused() = assertRefused(zipWith(manifest.copy(formatVersion = EXPORT_FORMAT_VERSION + 1)), "This export was made by a newer version of the app — update it to import this file")
    @Test fun noLibrary_isRefused() = assertRefused(zipEntries(listOf("manifest.json" to Json.encodeToString(manifest).encodeToByteArray())), "This export has no library_export.csv")
    @Test fun unreadableBytes_areRefused() = assertRefused(byteArrayOf(0x50, 0x4B, 3, 4, 9, 9, 9), "Couldn't read this file")
    @Test fun optionalFilesMissing_readAsNull() {
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(zipEntries(listOf("manifest.json" to Json.encodeToString(manifest).encodeToByteArray(), "library_export.csv" to "h\n".encodeToByteArray()))))
        assertNull(read.readingLogsCsv); assertNull(read.episodesCsv); assertNull(read.watchLogsCsv)
    }
    @Test fun unknownManifestFields_areIgnored() { /* manifest JSON with an extra "future": 1 field decodes */ }

    private fun zipWith(m: ExportManifest) = zipEntries(listOf("manifest.json" to Json.encodeToString(m).encodeToByteArray(), "library_export.csv" to "h\n".encodeToByteArray()))
    private fun assertRefused(bytes: ByteArray, reason: String) = assertEquals(ArchiveReadResult.Refused(reason), ExportArchive.decode(bytes))
}
```

Write the body of `unknownManifestFields_areIgnored` in full: build the JSON string by hand with an extra field, then assert `Read`. Construct `CsvExportBundle` with its real field names; check the class first.

- [ ] **Step 2: Run them to see them fail.** `./gradlew :shared:jvmTest --tests '*ExportArchiveTest*'`. Expected: compile FAIL.
- [ ] **Step 3: Implement.**
  - The actuals, identical for Android and JVM:

    ```kotlin
    internal actual fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } } }.toByteArray()
    internal actual fun unzipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> generateSequence { zip.nextEntry }.forEach { e -> if (!e.isDirectory) result[e.name] = zip.readBytes() } }
        if (result.isEmpty()) throw java.util.zip.ZipException("no entries")
        return result
    }
    ```

  - `decode`, in this order:
    1. `unzipEntries`. Any exception → `Refused("Couldn't read this file")`.
    2. No `manifest.json` → "Not a Media Tracker export".
    3. Parse with `Json { ignoreUnknownKeys = true }`. A `SerializationException` or `IllegalArgumentException` → "Not a Media Tracker export".
    4. `format != EXPORT_FORMAT` → "Not a Media Tracker export".
    5. `formatVersion > EXPORT_FORMAT_VERSION` → the newer-version message.
    6. No library entry → "This export has no library_export.csv".
    7. Otherwise `Read`, with the entries decoded as UTF-8.
  - `encode` writes the manifest first, then the four CSVs **always**.
  - KDoc on each public declaration says why, citing #196.
- [ ] **Step 4: Run them to see them pass.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Export archive: manifest model and zip codec (#196)"`

---

### Task 2: `ExportArchiveUseCase`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ExportArchiveUseCase.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/AppContainer.kt` (expose `exportArchiveUseCase`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/portability/domain/ExportArchiveUseCaseTest.kt`

**Interfaces:** this task produces the following.

```kotlin
public data class ExportArchiveFile(val bytes: ByteArray, val suggestedFileName: String)   // equals/hashCode via contentEquals
public interface ArchiveExporter { public suspend fun export(appVersion: String): Resource<ExportArchiveFile> }
public class ExportArchiveUseCase(
    private val exportUseCase: ExportUseCase,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val logger: Logger = AppLogger,
) : ArchiveExporter
```

`AppContainer`: `public val exportArchiveUseCase: ExportArchiveUseCase = ExportArchiveUseCase(exportDataUseCase, settingsRepository)`. Use whatever `ExportDataUseCase` and `SettingsRepository` properties `AppContainer` already has.

- [ ] **Step 1: Write the failing tests.** Real DB (`testAppDatabase()`), seeded with one watched show as `WatchLogImportExportTest.seedSource()` does. Fixed clock `2026-10-14T03:00:00Z`, `zone = TimeZone.UTC`.

```kotlin
@Test fun archive_holdsTheSameCsvsAsTheBundle_andAManifestCountingRows() = runTest {
    val bundle = (exporter.execute() as Resource.Success).data
    val file = (useCase.export("0.25.0") as Resource.Success).data
    assertEquals("media-tracker-export-2026-10-14.zip", file.suggestedFileName)
    val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(file.bytes))
    assertEquals(bundle.libraryCsv, read.libraryCsv); assertEquals(bundle.watchLogsCsv, read.watchLogsCsv)
    assertEquals("0.25.0", read.manifest.appVersion); assertEquals("2026-10-14T03:00:00Z", read.manifest.exportedAt)
    assertEquals(listOf("library_export.csv", "reading_logs_export.csv", "episodes_export.csv", "watch_logs_export.csv"), read.manifest.files.map { it.name })
    assertEquals(ExportArchive.dataRowCount(bundle.episodesCsv /*, header if needed */), read.manifest.files.single { it.name == "episodes_export.csv" }.rows)
    assertTrue(read.manifest.files.all { it.csvSchemaVersion == CsvSchemaVersion.CURRENT })   // use the real constant's name
}

@Test fun settings_onlyTheAllowlist_neverCredentials() = runTest {
    settings.setIncludeSpecials(false); settings.setWeekStartDay(WeekStartDay.MONDAY /* a real entry */); settings.setLogVerbosity(LogLevel.WARN)
    settings.setGoogleBooksApiKey("secret-key"); settings.setString("tmdb_credential", "secret-token")
    assertEquals("secret-key", settings.getGoogleBooksApiKey(), "positive control: the credential is stored")
    val m = (ExportArchive.decode((useCase.export("x") as Resource.Success).data.bytes) as ArchiveReadResult.Read).manifest
    assertEquals(mapOf("include_specials" to "false", "week_start_day" to "MONDAY", "log_verbosity" to "WARN"), m.settings)
    assertFalse(m.settings.values.any { "secret" in it })
}

@Test fun unsetSettings_areOmitted() = runTest {
    val m = (ExportArchive.decode((useCase.export("x") as Resource.Success).data.bytes) as ArchiveReadResult.Read).manifest
    assertEquals(emptyMap(), m.settings)
}
```

- [ ] **Step 2: Run them to see them fail.** `--tests '*ExportArchiveUseCaseTest*'`. Expected: compile FAIL.
- [ ] **Step 3: Implement** `export(appVersion)`:
  1. `exportUseCase.execute()`; an Error passes through.
  2. Build `files` from the four CSVs with `CsvSchemaVersion`'s current value, and `rows = dataRowCount(...)`.
  3. `settings = EXPORTABLE_SETTING_KEYS.mapNotNull { k -> settingsRepository.getString(k)?.let { k to it } }.toMap()`.
  4. `exportedAt = clock.now().toString()`, and `suggestedFileName = "media-tracker-export-${clock.now().toLocalDateTime(zone).date}.zip"`.
  5. `ExportArchive.encode`, inside try/catch → `Resource.Error("Export failed: …")`.
- [ ] **Step 4: Run them to see them pass.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ExportArchiveUseCase: one zip with a manifest and allowlisted settings (#196)"`

---

### Task 3: `ImportArchiveUseCase`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ImportArchiveUseCase.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ImportDataUseCase.kt` (only `ImportSummary`: add `notes: List<String> = emptyList()` and `settingsRestored: Int = 0`)
- Modify: `AppContainer.kt` (expose `importArchiveUseCase`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/portability/domain/ZipImportExportTest.kt`

**Interfaces:** this task produces the following.

```kotlin
public interface ArchiveImporter {
    public suspend fun import(bytes: ByteArray, duplicatePolicy: DuplicatePolicy, restoreSettings: Boolean): Resource<ImportSummary>
}
public class ImportArchiveUseCase(
    private val importUseCase: ImportUseCase,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = AppLogger,
) : ArchiveImporter
```

- [ ] **Step 1: Write the failing tests.** Use a source DB and a target DB, as in `WatchLogImportExportTest`, with source seeded through `seedSource()` and `seedFilm()` (copy those helpers). Exporting goes through `ExportArchiveUseCase` from Task 2.

```kotlin
@Test fun zipRoundTrip_isLossless() = runTest {
    val zip = exportZip(source)
    val s = assertIs<Resource.Success<ImportSummary>>(importer(target).import(zip, DuplicatePolicy.SKIP, restoreSettings = false)).data
    assertEquals(emptyList(), s.notes)
    // Library, episodes and viewings match the source (compare MediaRepository.observeAll* lists by id/title/watchedAt).
}
@Test fun reimport_createsNothing() = runTest { val z = exportZip(source); importer(target).import(z, SKIP, false); val again = …; assertEquals(0, again.viewingsImported) }
@Test fun newerVersion_withRestoreTicked_changesNothing() = runTest {
    sourceSettings.setIncludeSpecials(false)
    val tampered = rewriteManifest(exportZip(source)) { it.copy(formatVersion = EXPORT_FORMAT_VERSION + 1) }
    val r = importer(target).import(tampered, SKIP, restoreSettings = true)
    assertEquals("This export was made by a newer version of the app — update it to import this file", (r as Resource.Error).message)
    assertEquals(emptyList(), MediaRepository(target).observeAllMediaWithDetails().first())
    assertEquals(null, targetSettings.getString("include_specials"))
}
@Test fun restoreSettings_false_leavesSettings() = …          // target settings unchanged; settingsRestored == 0
@Test fun restoreSettings_true_appliesAllThree() = …          // target values equal source's; settingsRestored == 3
@Test fun invalidSettingValue_isSkipped_andNoted() = …        // manifest "log_verbosity" to "LOUD" → not applied, a note names it
@Test fun unknownSettingKey_isIgnored_andNoted() = …          // "google_books_api_key" to "x" in a hand-made manifest → NOT written, noted
@Test fun rowCountMismatch_isANote_notARefusal() = …          // manifest rows for library = actual+1 → Success, one note naming library_export.csv
@Test fun crlfAndBom_importCleanly() = …                      // library CSV rewritten with "﻿" prefix and \r\n → Success, no notes, same items
@Test fun aLoneLibraryCsv_imports() = …                       // bytes = bundle.libraryCsv.encodeToByteArray() → items imported
@Test fun aNonCsvFile_isRefused() = …                         // bytes = PNG header + junk → Resource.Error or zero items with rejections; assert no media written
```

Write each `…` body in full in the test file. The comments above say exactly what to assert. `rewriteManifest(zip, f)` decodes with `unzipEntries`, replaces `manifest.json` with `Json.encodeToString(f(old))`, and re-zips with `zipEntries`, keeping the other entries.

- [ ] **Step 2: Run them to see them fail.** Expected: compile FAIL.
- [ ] **Step 3: Implement** `import(bytes, policy, restoreSettings)`:
  1. **Not a zip** (`!ExportArchive.isZip(bytes)`): decode as UTF-8 and return `importUseCase.execute(csv, null, null, policy, null)`. If the text does not start with `csv_schema_version` after stripping a BOM, return `Resource.Error("Not a Media Tracker export")` instead, which covers `aNonCsvFile_isRefused`.
  2. **Zip:** `ExportArchive.decode`; `Refused` → `Resource.Error(reason)`.
  3. Call `importUseCase.execute(read.libraryCsv, read.readingLogsCsv, read.episodesCsv, policy, read.watchLogsCsv)`. If it is an Error, return it unchanged, with no settings touched.
  4. **Notes:** for each manifest file whose CSV is present, if `dataRowCount(csv) != rows`, add *"library_export.csv has N rows; the export recorded M"*. Strip a leading BOM before counting and importing; check whether the importers already tolerate a BOM, and strip it here only if they don't.
  5. **Settings,** when `restoreSettings`: for each `(k, v)` in `read.manifest.settings`:
     - `include_specials` → `v.toBooleanStrictOrNull()?.let { setIncludeSpecials(it) }`
     - `week_start_day` → `WeekStartDay.entries.firstOrNull { it.name == v }?.let { setWeekStartDay(it) }`
     - `log_verbosity` → `LogLevel.entries.firstOrNull { it.name == v }?.let { setLogVerbosity(it) }`
     - **Any other key** is ignored, with the note *"Setting \"k\" is not restored by this app"*.
     - **Invalid values** are skipped, with the note *"Setting \"k\" had an invalid value and was not restored"*.
  6. Return `summary.copy(notes = notes, settingsRestored = n)`.
- [ ] **Step 4: Run them to see them pass,** then run all of `:shared:jvmTest` (`ImportSummary` gained fields with defaults). Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ImportArchiveUseCase: zip or lone library CSV, refusals, row-count notes, opt-in settings (#196)"`

---

### Task 4: ViewModels

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/ExportViewModel.kt` and its `ExportUiState` (find with `grep -rn "class ExportUiState"`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/ImportViewModel.kt`
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/ViewModelFactories.kt` (lines ~180 and ~194)
- Test: `shared/src/commonTest/kotlin/com/hub/media/ui/ExportViewModelTest.kt` (rewrite), `ImportViewModelTest.kt` (add), `FakeExportDataUseCase.kt` / `FakeImportDataUseCase.kt` (add archive fakes alongside)

**Interfaces:** this task produces the following.

```kotlin
public class ExportViewModel(private val exporter: ArchiveExporter, private val appVersion: String) : ViewModel()
// ExportUiState: Idle, Loading, Success(file: ExportArchiveFile), AwaitingDestination(file: ExportArchiveFile), Error(message)
public fun exportData()                    // Loading → Success | Error; ignored while Loading
public fun awaitDestination()              // Success → AwaitingDestination
public fun takePendingFile(): ExportArchiveFile?   // once, then Idle
public fun reset()

public class ImportViewModel(private val importDataUseCase: ImportUseCase, private val archiveImporter: ArchiveImporter) : ViewModel()
public fun importArchive(bytes: ByteArray, duplicatePolicy: DuplicatePolicy, restoreSettings: Boolean)
// The CSV importData(...) stays (the Goodreads path and tests use the same state machine); remove it only if nothing calls it after Task 5.
```

Factories: `ExportViewModel(appContainer.exportArchiveUseCase, BuildConfig.VERSION_NAME)` and `ImportViewModel(appContainer.importDataUseCase, appContainer.importArchiveUseCase)`.

- [ ] **Step 1: Write the failing tests.**
  - `ExportViewModelTest`, which mirrors `BackupViewModelTest`: `awaitingDestination_holdsTheFile_andIsNotSuccess`, `takePendingFile_handsItOverOnce_andResets`, `takePendingFile_withNothingAwaiting_isNull` (the process-death case), `exportData_passesTheAppVersion` (the fake records it), and `error_reachesErrorState`.
  - `ImportViewModelTest`: `importArchive_passesBytesPolicyAndRestoreFlag` (a fake `ArchiveImporter` records them; assert Success summary state via `uiState.first {}`), and `importArchive_ignoredWhileLoading`.
- [ ] **Step 2: Run them to see them fail.** `./gradlew :shared:jvmTest --tests '*ExportViewModelTest*' --tests '*ImportViewModelTest*'`. Expected: compile FAIL.
- [ ] **Step 3: Implement** to the interfaces above. Delete `ExportViewModel`'s old `CsvExportBundle` success path. Fix every compile error in `shared` tests that construct these ViewModels.
- [ ] **Step 4: Run them to see them pass,** then run `:shared:jvmTest :app:compileDebugKotlin`. The app will fail to compile until Task 5; if it does, record a Ruling and do Tasks 4 and 5 as one commit pair, without pushing in between.
- [ ] **Step 5: Commit.** `git commit -m "Export/Import ViewModels: archive bytes wait for their destination; importArchive (#196)"`

---

### Task 5: Settings screen: one dialog each way

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/SettingsCsvExportFlow.kt` (replace the chain)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/SettingsImportFlows.kt` (replace `rememberCsvImportLauncher`'s chain; `ImportSummaryDialog` shows `notes` and `settingsRestored`)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/SettingsScreenRoute.kt` (`var restoreSettings by rememberSaveable { mutableStateOf(false) }`, passed to the launcher and the screen)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/SettingsScreen.kt` (`ImportDataSetting` gains `restoreSettings: Boolean, onRestoreSettingsChange: (Boolean) -> Unit` and a labelled `Checkbox` row; `SettingsScreen` threads them through), and `SettingsScreenPreviews.kt`, plus every `SettingsScreen(` call site in tests
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/.../ui/screens/SettingsRouteSafChainsTest.kt` (rewrite the export and import sections)

**Behaviour:**
- **Export:**
  - On `ExportUiState.Success(file)`: `exportViewModel.awaitDestination()`, then `launcher.launch(file.suggestedFileName)` with `CreateDocument("application/zip")`.
  - The callback runs `val file = exportViewModel.takePendingFile()`. `uri == null` → cancelled. `file == null` → delete the created document (reuse #162's `deleteCreatedDocument`, moved to a shared internal helper file in `ui/screens`) and show `export_expired_message`. Otherwise write `file.bytes` on IO → success or failure.
- **Import:** `OpenDocument` with `arrayOf("application/zip", "text/*", "application/octet-stream")`. Read the bytes on IO (`contentResolver.openInputStream(uri)?.use { it.readBytes() }`); a null or exception → `import_failure_message`. Otherwise `importViewModel.importArchive(bytes, duplicatePolicy, restoreSettings)`.
- **Strings:**
  - `export_expired_message` ("The export expired before it was saved — please start it again")
  - `import_restore_settings` ("Also restore settings")
  - `import_restore_settings_description` ("Include specials, week start and log detail, if the export has them. Never API keys.")
  - `import_summary_settings_restored` (plural: "%d setting restored" / "%d settings restored")
  - `import_summary_notes_title` ("Notes")
  - Update `settings_export_description` and `settings_import_description` (or their real names) to say *one .zip file*.

- [ ] **Step 1: Write the failing device tests** in `SettingsRouteSafChainsTest`, replacing the old export and import chain tests:
  - `export_happyPath_writesOneZipThatDecodes`: one launch; its input ends with `.zip`; answer with a file URI in `workDir`. The success message shows. `ExportArchive.decode(file.readBytes())` is `Read`, and `manifest.files.size == 4`.
  - `export_cancel_showsCancelled`.
  - `export_recreatedWhileThePickerIsOpen_writesTheZip_andOpensNoSecondPicker`: copy #162's backup recreation test and adapt it.
  - `import_ofThatZip_showsTheSummary`: export first to get a real zip, then answer the import launch with its URI. The summary dialog appears.
  - `import_cancel_showsCancelled`.

  Delete the tests for the removed multi-dialog chains. Keep the backup and restore tests unchanged.
- [ ] **Step 2: Run them to see them fail.** Build the androidTest source set (`./gradlew :app:compileDebugAndroidTestKotlin`): compile FAIL or assertion FAIL is expected. Run them on the device in Step 4.
- [ ] **Step 3: Implement** the behaviour above. Remove the old pending-state variables and launchers. `ktlintFormat`.
- [ ] **Step 4: Run.** The full gate, then `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.github.maskedkunisquat.mediatracker.ui.screens.SettingsRouteSafChainsTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Expected: all PASS.
- [ ] **Step 5: Commit.** `git commit -m "Settings: export and import are one .zip and one dialog; restore-settings option (#196)"`

---

### Task 6: Docs, changelog, device verification

**Files:**
- `docs/sample-data/CSV_FORMAT.md`: a new section **"Container: the export `.zip`"** after the intro. It covers the layout, the manifest fields with the JSON example from the spec, the refusals, the settings allowlist, and the lone-CSV fallback, and says the per-file sections below are unchanged.
- `README.md`: wherever export or import is described (`grep -n -i "export\|import" README.md`).
- `CHANGELOG.md` `[Unreleased]`:
  - **Changed:** "Export now saves one `.zip` file — your library, reading logs, episodes and watch history together — instead of four separate files, and import reads it back in one step. A lone library CSV still imports."
  - **Added:** "Import can also restore your settings (Include specials, week start, log detail) from an export. API keys are never exported."
  - **Internal:** "Export archive format v1: `manifest.json` (format, version, file row counts, allowlisted settings) plus the four schema-v6 CSVs."

- [ ] **Step 1: Write the docs and changelog.** Then run the gate plus `:app:assembleDebug`.
- [ ] **Step 2: Device.** `adb devices`, then the full `./gradlew :app:connectedDebugAndroidTest`, then `./gradlew :app:seedDebugDevice`. Record the tally.
- [ ] **Step 3: Manual check on the device.**
  - Settings → Export → save to Downloads.
  - `adb pull` the zip, list its entries with `python -m zipfile -l`, and print `manifest.json`.
  - Import it back with SKIP: the summary shows nothing new.
  - Change *Include specials*, then import with "Also restore settings": the value comes back.
- [ ] **Step 4: Commit and review.** `git commit -m "Docs and changelog: one-file export (#196)"`, then run local CodeRabbit and fix any valid findings with RED→GREEN.
