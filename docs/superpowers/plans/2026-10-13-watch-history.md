# Watch history (viewings, Rewatched, correcting a date) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every episode viewing becomes a `watch_logs` row, with `episodes.watchedAt` always the
latest viewing. Users can mark "Watched again", see an episode's viewings, and edit or delete
each viewing with Undo. Viewings survive CSV export and import.

**Architecture:** all invariant-preserving writes live in `TVWriteDao` as `@Transaction` methods,
and nothing else writes `watchedAt`. A read-only `WatchLogDao` serves the UI. Repository and use
cases wrap the writes in `Resource`. The view model drives a stateless episode-sheet content
composable that the route hosts in a `ModalBottomSheet`. The CSV gains a fourth file, wired the
same way `episodes_export.csv` is.

**Tech Stack:** Kotlin Multiplatform, Room KMP (bundled SQLite), Compose Material 3, kotlinx-datetime 0.8, kotlin.test, Robolectric and Roborazzi.

**Spec:** `docs/superpowers/specs/2026-10-13-watch-history-design.md`. **Its "Review revisions"
section overrides the text above it.** Read the spec first.

## Global Constraints

- **Invariant:** `episodes.watchedAt == max(watch_logs.watchedAt for that episode)`, or `null`
  when it has none. It must hold after every write, inside that write's transaction.
- **No schema change.** `APP_DATABASE_VERSION` stays 7, and `shared/schemas/` is untouched.
  `CSV_SCHEMA_VERSION` goes from 5 to 6.
- **Episodes only.** Films (`episodeId = null`) are out of scope. Never write a film viewing.
- **No users yet** (owner, 2026-10-13). The backfill is a cheap idempotent step, not a migration.
- **IDs** come from `com.hub.media.core.util.newId()`.
- **Dates** for Pick a date and Edit date go through `resolveWatchedAt` / `WatchedWhen.On`:
  noon local, clamped to now.
- **Room tests** go in `shared/src/jvmTest`. View-model tests await with `uiState.first { … }`,
  never `.value`.
- **Robolectric** never opens a date picker or a `ModalBottomSheet` window. It tests the content
  composable. The picker flow is androidTest only (AGENTS.md §7).
- **Gate:** `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug`,
  plus `ktlintCheck :app:lintDebug :app:assembleDebug`. Compile androidTest whenever `app/src`
  changes.
- **Commits** end with:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01X3dhMjqKB1M3voUhWUrqxJ`.

## Review Focus

1. **Undo after a season shrink or removal.** Viewings must not be re-inserted for a deleted
   episode, and Undo must not crash. Pinned in Task 1.
2. **Back-dated "Watched again".** A rewatch dated earlier than the current `watchedAt` adds a
   viewing but leaves `watchedAt` unchanged. Pinned in Task 1.
3. **Deleting a middle viewing then undoing it.** It is restored, and `watchedAt` is unchanged.
   Pinned in Task 1.
4. **Re-importing the same export.** Zero new viewings. Pinned in Task 4.
5. **A slot-matched import** (episode ids didn't round-trip). Viewings attach to the library's
   episode, and nothing hits a foreign-key error. Pinned in Task 4.

---

## File map

| File | Responsibility |
|---|---|
| Modify `core/database/dao/TVWriteDao.kt` | Viewing-aware writes (tick, rewatch, delete, edit, untick, restore, backfill); remove bypass writes |
| Create `core/database/dao/WatchLogDao.kt` + register in `AppDatabase` | Read-only viewing queries |
| Modify `core/database/entities/WatchLogEntity.kt` | KDoc: `watchedAt` is the latest viewing (spec revision 10) |
| Modify `features/tv/data/TVShowRepository.kt` | `untickEpisode` / `clearSeason` return viewings; `restoreViewings`, `deleteViewing`, `editViewingDate`, `restoreViewing`, `observeViewings`, `observeViewingCounts`; test helpers rerouted |
| Create `features/tv/domain/RecordRewatchUseCase.kt`, `EnsureViewingsUseCase.kt` | Rewatch; startup backfill |
| Modify `ui/TickPrompt.kt`, `ui/TVShowDetailViewModel.kt`, `ui/AppContainer.kt` | Undo payload becomes viewings; episode sheet state and actions |
| Create `features/portability/csv/WatchLogCsvExporter.kt`, `WatchLogCsvImporter.kt` | The fourth CSV file |
| Modify `features/portability/csv/CsvSchemaVersion.kt`, `domain/CsvExportBundle.kt`, `domain/ExportDataUseCase.kt`, `domain/ImportDataUseCase.kt`, `core/database/dao/ImportWriteDao.kt`, `data/ImportWriteRepository.kt`, `ui/ImportViewModel.kt` | Wiring, policies, normalisation |
| Create `app/.../ui/components/WatchedDatePickerDialog.kt`, `app/.../ui/screens/EpisodeSheet.kt` | Extracted picker; stateless sheet content |
| Modify `app/.../ui/components/MarkWatchedDialog.kt`, `ui/screens/TVShowDetailScreen.kt`, `SettingsImportFlows.kt`, `SettingsCsvExportFlow.kt`, `MediaTrackerApplication.kt`, `res/values/strings.xml` | UI wiring |
| Docs / sample | `docs/sample-data/watch_logs_sample.csv`, `CSV_FORMAT.md`, `README.md`, `CHANGELOG.md` |

All `shared` paths are under `shared/src/commonMain/kotlin/com/hub/media/`.

---

### Task 1: Viewing-aware writes in `TVWriteDao` and a read-only `WatchLogDao`

**Files:** `TVWriteDao.kt`, new `WatchLogDao.kt`, `AppDatabase.kt` (add `abstract fun watchLogDao(): WatchLogDao`), `WatchLogEntity.kt` (KDoc).
**Test:** `shared/src/jvmTest/kotlin/com/hub/media/features/tv/data/WatchHistoryDaoTest.kt`. Update `UntickRestoreTest` to the new signatures.

**Interfaces (produces):**

```kotlin
// WatchLogDao (read-only)
@Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId ORDER BY watchedAt DESC")
fun observeForEpisode(episodeId: String): Flow<List<WatchLogEntity>>
@Query("SELECT episodeId, COUNT(*) AS viewings FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NOT NULL GROUP BY episodeId")
fun observeViewingCounts(mediaId: String): Flow<List<EpisodeViewingCount>>   // data class EpisodeViewingCount(val episodeId: String, val viewings: Int)

// TVWriteDao (all @Transaction unless a plain @Query)
suspend fun markEpisodesWatchedAt(stamps: Map<String, Instant>): Int         // now also inserts one viewing per row the guard changed
suspend fun addViewing(episodeId: String, at: Instant): Boolean               // false if the episode doesn't exist; watchedAt = max
suspend fun deleteViewing(logId: String): WatchLogEntity?                     // returns the deleted row for Undo; recomputes
suspend fun updateViewingDate(logId: String, at: Instant): Boolean            // recomputes
suspend fun restoreViewing(log: WatchLogEntity): Boolean                      // FK-safe: episode exists AND no row with log.id
suspend fun clearEpisodeReturningPrevious(episodeId: String): List<WatchLogEntity>   // CHANGED return type
suspend fun clearSeasonReturningPrevious(mediaId: String, seasonNumber: Int): List<WatchLogEntity>  // CHANGED
suspend fun restoreAll(previous: List<WatchLogEntity>): Int                   // CHANGED: per episode, only if it exists, watchedAt IS NULL, and it has no viewings
suspend fun ensureViewingsForWatchedEpisodes(): Int                           // backfill; returns rows created
```

  Remove `markEpisodeWatched`, `clearEpisodeWatched` (fold into `clearEpisodeReturningPrevious`),
  `clearSeasonWatched` (fold in) and `restoreWatchedAt`.

- [ ] **Step 1: Write the failing tests** in `WatchHistoryDaoTest`. Each test checks the invariant
  with a helper: `assertInvariant(episodeId)` compares `episodeDao().getById(id).watchedAt` with
  `watchLogs(id).maxOfOrNull { it.watchedAt }`, where `watchLogs` is a test-only
  `db.watchLogDao().observeForEpisode(id).first()`. Use the `addShow` and quick-fill set-up from
  `UntickRestoreTest`.
  - `tick_writesOneViewingPerEpisodeChanged_andNoneForAlreadyWatched`: `markEpisodesWatchedAt`
    over E1 (watched already) and E2 (unwatched). Exactly one new viewing, on E2. The invariant
    holds for both.
  - `rewatch_addsViewing_andMovesWatchedAtForward`: E1 at t1, then `addViewing(E1, t2 > t1)`.
    Two viewings, and `watchedAt == t2`.
  - `backdatedRewatch_addsViewing_butKeepsWatchedAt` (Review Focus 2): `addViewing(E1, t0 < t1)`.
    `watchedAt == t1`.
  - `deleteMiddleViewing_thenRestore_keepsWatchedAt` (Review Focus 3): three viewings t1 < t2 <
    t3; delete t2, then `restoreViewing(deleted)`. Three viewings, and `watchedAt == t3` throughout.
  - `deleteLastViewing_unticks`: one viewing; `deleteViewing` → `watchedAt == null`.
  - `editDate_recomputes`: two viewings; edit the latest to an earlier date → `watchedAt` = the
    new max.
  - `untick_returnsViewings_andRestoreAllPutsThemBack`: two viewings; clear → both returned,
    `watchedAt == null`; `restoreAll` → both back, invariant holds.
  - `restoreAll_afterReTick_writesNothing`.
  - `restoreAll_afterSeasonShrink_skipsTheDeletedEpisode_andDoesNotThrow` (Review Focus 1):
    E3 watched; clear the season and capture the payload; `TVShowRepository.setSeasonLength(…, 2)`
    deletes E3; `restoreAll` restores E1 and E2, skips E3, and does not throw.
  - `backfill_isIdempotent`: insert an episode with `watchedAt` set and no viewings, directly via
    `insertEpisodes`. The first call returns 1; the second returns 0; one viewing exists, with
    that date.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew :shared:jvmTest --tests '*WatchHistoryDaoTest' -q` → compile errors (missing
  methods and DAO).
- [ ] **Step 3: Implement.** The key bodies:

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertViewing(log: WatchLogEntity)
@Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId") suspend fun viewingsFor(episodeId: String): List<WatchLogEntity>
@Query("SELECT * FROM watch_logs WHERE id = :logId") suspend fun viewingById(logId: String): WatchLogEntity?
@Query("DELETE FROM watch_logs WHERE id = :logId") suspend fun deleteViewingRow(logId: String): Int
@Query("DELETE FROM watch_logs WHERE episodeId = :episodeId") suspend fun deleteViewingsFor(episodeId: String): Int
@Query("UPDATE watch_logs SET watchedAt = :at WHERE id = :logId") suspend fun setViewingDate(logId: String, at: Instant): Int
@Query("UPDATE episodes SET watchedAt = (SELECT MAX(watchedAt) FROM watch_logs WHERE episodeId = :episodeId) WHERE id = :episodeId")
suspend fun recomputeWatchedAt(episodeId: String): Int
@Query("UPDATE episodes SET watchedAt = NULL WHERE id = :episodeId") suspend fun clearWatchedAtRow(episodeId: String): Int

@Transaction
suspend fun markEpisodesWatchedAt(stamps: Map<String, Instant>): Int {
    var changed = 0
    for ((id, at) in stamps) {
        if (markEpisodeWatchedIfUnwatched(id, at) == 1) {
            val e = getByIds(listOf(id)).single()
            insertViewing(WatchLogEntity(id = newId(), mediaId = e.mediaId, episodeId = id, watchedAt = at))
            changed++
        }
    }
    return changed
}

@Transaction
suspend fun addViewing(episodeId: String, at: Instant): Boolean {
    val e = getByIds(listOf(episodeId)).singleOrNull() ?: return false
    insertViewing(WatchLogEntity(id = newId(), mediaId = e.mediaId, episodeId = episodeId, watchedAt = at))
    recomputeWatchedAt(episodeId)
    return true
}

@Transaction
suspend fun deleteViewing(logId: String): WatchLogEntity? {
    val log = viewingById(logId) ?: return null
    deleteViewingRow(logId)
    log.episodeId?.let { recomputeWatchedAt(it) }
    return log
}

@Transaction
suspend fun updateViewingDate(logId: String, at: Instant): Boolean {
    val log = viewingById(logId) ?: return false
    setViewingDate(logId, at)
    log.episodeId?.let { recomputeWatchedAt(it) }
    return true
}

@Transaction
suspend fun restoreViewing(log: WatchLogEntity): Boolean {
    val episodeId = log.episodeId ?: return false
    if (getByIds(listOf(episodeId)).isEmpty() || viewingById(log.id) != null) return false
    insertViewing(log)
    recomputeWatchedAt(episodeId)
    return true
}

@Transaction
suspend fun clearEpisodeReturningPrevious(episodeId: String): List<WatchLogEntity> {
    val previous = viewingsFor(episodeId)
    deleteViewingsFor(episodeId)
    clearWatchedAtRow(episodeId)
    return previous
}

@Transaction
suspend fun clearSeasonReturningPrevious(mediaId: String, seasonNumber: Int): List<WatchLogEntity> =
    episodesInSeason(mediaId, seasonNumber).flatMap { clearEpisodeReturningPrevious(it.id) }

@Transaction
suspend fun restoreAll(previous: List<WatchLogEntity>): Int {
    var restored = 0
    for ((episodeId, logs) in previous.filter { it.episodeId != null }.groupBy { it.episodeId!! }) {
        val e = getByIds(listOf(episodeId)).singleOrNull() ?: continue          // deleted since: skip (FK)
        if (e.watchedAt != null || viewingsFor(episodeId).isNotEmpty()) continue // re-ticked since: newer wins
        logs.forEach { insertViewing(it) }
        recomputeWatchedAt(episodeId)
        restored++
    }
    return restored
}

@Query("SELECT * FROM episodes e WHERE e.watchedAt IS NOT NULL AND NOT EXISTS (SELECT 1 FROM watch_logs w WHERE w.episodeId = e.id)")
suspend fun watchedWithoutViewings(): List<EpisodeEntity>

@Transaction
suspend fun ensureViewingsForWatchedEpisodes(): Int =
    watchedWithoutViewings().onEach {
        insertViewing(WatchLogEntity(id = newId(), mediaId = it.mediaId, episodeId = it.id, watchedAt = it.watchedAt!!))
    }.size
```

  - Room cannot generate UUIDs in SQL, so the backfill is a read-then-insert inside one
    `@Transaction`. That is spec revision 5's allowed alternative, and the transaction is what
    serialises it.
  - `restoreAll` counts **episodes** restored. Update `UntickRestoreTest`: `previous` becomes a
    list, and `restoreAll(previous)` returns the episode count.
  - `restore_neverOverwritesAReTick` stays as it is, adapted to the list type.
  - Update `WatchLogEntity`'s KDoc to say a rewatch moves `watchedAt` to the latest viewing.
- [ ] **Step 4: Run and confirm they pass.** `./gradlew :shared:jvmTest --tests '*WatchHistoryDaoTest' --tests '*UntickRestoreTest' -q`.
  The full `:shared:jvmTest` will not compile until Task 2 updates the callers, so run only these
  two test classes here.
- [ ] **Step 5: Falsify the guards, one at a time.** In each case confirm a test fails, then
  restore the code:
  - remove `recomputeWatchedAt` from `deleteViewing`;
  - drop the `== 1` check in `markEpisodesWatchedAt`;
  - remove the existence `continue` in `restoreAll`.
- [ ] **Step 6: Commit:** `Viewings are the truth: viewing-aware TV writes and WatchLogDao (#90)`.

---

### Task 2: Repository, use cases and the Undo payload

**Files:** `TVShowRepository.kt`, new `RecordRewatchUseCase.kt`, `TickPrompt.kt`
(`UntickEvent.previous: List<WatchLogEntity>`; `mergeUntick` concatenates), `TVShowDetailViewModel.kt`
(compiles against the new types; no new UI yet), `AppContainer.kt` (expose `recordRewatchUseCase`).
**Test:** `TVShowDetailViewModelTest`: update the existing untick tests to use viewing lists, and
add two new tests.

**Interfaces:**
- Consumes: Task 1's DAO methods.
- Produces, on `TVShowRepository`:
  - `untickEpisode(episodeId): Resource<List<WatchLogEntity>>`
  - `clearSeason(mediaId, seasonNumber): Resource<List<WatchLogEntity>>`
  - `restoreWatched(previous: List<WatchLogEntity>): Resource<Int>`
  - `deleteViewing(logId): Resource<WatchLogEntity?>`
  - `restoreViewing(log): Resource<Boolean>`
  - `editViewingDate(logId, at): Resource<Boolean>`
  - `observeViewings(episodeId): Flow<List<WatchLogEntity>>`
  - `observeViewingCounts(mediaId): Flow<Map<String, Int>>`

  It also produces
  `class RecordRewatchUseCase(db, clock = Clock.System, zone = { TimeZone.currentSystemDefault() })`,
  with `suspend fun execute(episodeId, watchedWhen: WatchedWhen): Resource<Unit>`. It resolves the
  date through `resolveWatchedAt(watchedWhen, episode.airDate, now, zone())`; an unresolvable
  `OnAirDate` returns `Resource.Error`.
- **Test helpers:** `setEpisodeWatched(id, true)` → `markEpisodesWatchedAt(mapOf(id to clock.now()))`;
  `false` → `clearEpisodeReturningPrevious`. `setSeasonWatched` gets the same treatment. They stay
  `internal`.

- [ ] **Step 1: Write the failing tests.**
  - Make the existing `untick_thenUndo_restoresTheExactTimestamp`,
    `clearSeason_thenUndo_restoresEachEpisodesOwnDate` and
    `twoQuickUnticks_shareOneUndo_thatRestoresBoth` assert on viewing lists: for example
    `event.previous.map { it.watchedAt } == listOf(t1)`.
  - New: `rewatch_viaUseCase_addsAViewing_andMovesWatchedAtForward`.
  - New: `rewatch_onAirDate_withoutAnAirDate_isRefused`.
- [ ] **Step 2: Run and confirm they fail.** `./gradlew :shared:jvmTest --tests '*TVShowDetailViewModelTest' -q` → compile errors.
- [ ] **Step 3: Implement** the interfaces above, using the existing try/catch `Resource` shape
  in `TVShowRepository`. `UntickEvent` keeps `label`, `episodeNumber`, `seasonNumber`, `isSeason`
  and `merged`; only `previous` changes type. The view model's `undo(event)` calls
  `restoreWatched(event.previous)`.
- [ ] **Step 4: Run the whole shared suite.** `./gradlew :shared:jvmTest :app:compileDebugKotlin -q`
  → green. Fix every caller of the old `Map` payload, including Up next, which does not untick
  and so should need nothing.
- [ ] **Step 5: Commit:** `Repository and Undo carry viewings; RecordRewatchUseCase (#90)`.

---

### Task 3: Startup backfill

**Files:** new `features/tv/domain/EnsureViewingsUseCase.kt`; `AppContainer.kt`
(`ensureViewingsUseCase`); `app/.../MediaTrackerApplication.kt` (launch it on the existing
`loggingScope`, or a sibling `SupervisorJob` scope, on `Dispatchers.Default`, after the container
is built).
**Test:** `shared/src/jvmTest/.../features/tv/domain/EnsureViewingsUseCaseTest.kt`.

- [ ] **Step 1: Write the failing tests:**
  - `execute_createsOneViewingPerWatchedEpisodeWithout`;
  - `execute_twice_isIdempotent`;
  - `execute_ignoresUnwatchedAndAlreadyLogged`.

  Each returns `Resource<Int>`.
- [ ] **Step 2: Run and confirm they fail** (the class is missing).
- [ ] **Step 3: Implement** `EnsureViewingsUseCase(db, logger)` → `db.tvWriteDao().ensureViewingsForWatchedEpisodes()`,
  wrapped in try/catch. On success it logs `info` with the count when it is greater than 0.
  Launch it from `MediaTrackerApplication.onCreate`, and comment that a restore restarts the
  process, so restored backups are covered too.
- [ ] **Step 4: Run and confirm they pass.** Then `./gradlew :app:compileDebugKotlin -q`.
- [ ] **Step 5: Commit:** `Backfill a viewing for every watched episode at startup (#90)`.

---

### Task 4: CSV: `watch_logs_export.csv` export and import, schema v6

**Files:**
- New `WatchLogCsvExporter.kt` and `WatchLogCsvImporter.kt`, following `EpisodeCsvExporter` /
  `EpisodeCsvImporter`.
- `CsvSchemaVersion.kt`: set it to 6 and add a "v6" KDoc paragraph saying the existing files'
  headers are unchanged.
- `CsvExportBundle` (`watchLogsCsv`) and `ExportDataUseCase` (read
  `db.watchLogDao()` / a new `observeAll` query).
- `ImportUseCase.execute` gains `watchLogsCsv: String?`; update `ImportDataUseCase` and every
  implementer and fake.
- `ImportWriteDao.importAtomically` gains `viewingInserts: List<WatchLogEntity>`,
  `viewingDeletesForEpisodes: List<String>` (REPLACE) and `touchedEpisodeIds: Set<String>`. Its
  normalisation step runs last, inside the same transaction.
- `ImportWriteRepository` and `ImportViewModel.importData(..., watchLogsCsv, ...)`.
- Docs: `CSV_FORMAT.md` and `README.md`.
- Sample data: add `docs/sample-data/watch_logs_sample.csv`, with **two** viewings of one already
  watched Fleabag episode so the device shows "Watched 2×".

**Tests:**
- `shared/src/jvmTest/.../portability/domain/WatchLogImportExportTest.kt`.
- Update the existing tests that assert `CSV_SCHEMA_VERSION == 5` and that construct
  `CsvExportBundle` or call `execute(...)` with three CSVs.
- `SampleLibraryCsvTest`, if it enumerates sample files.

**The rules (spec revision 3):**
- Rows resolve `episode_id` through the same `existingEpisodesById` / `existingEpisodesBySlot`
  maps the episode import builds. That needs the row's `media_id` → resolved show, then
  `(season, episode)` from the original episode row in this import when ids did not round-trip.
  A row that resolves to no episode is rejected with a reason and counted in the summary, never
  a foreign-key abort.
- **Dedup:** skip a viewing when the resolved episode already has one with an equal `watchedAt`,
  either in the database or earlier in this file.
- **REPLACE:** for each episode the episode CSV replaced **and** for which this file has
  viewings, delete its existing viewings first.
- **Normalisation**, for every touched episode, in this order:
  1. if it has viewings, `watchedAt = max(viewings)`;
  2. else if `watchedAt != null`, insert one viewing at `watchedAt`.

  "Touched" means: episodes inserted or updated by the episode CSV, plus episodes receiving
  viewings.

Steps:
- [ ] **Step 1: Write the failing tests:**
  - `roundTrip_isLossless`: two shows, three viewings → export → import into a fresh database →
    identical viewings.
  - `reimportingTheSameExport_createsNoViewings` (Review Focus 4).
  - `slotMatchedImport_attachesViewingsToTheLibrarysEpisode` (Review Focus 5). Export from DB A,
    import into DB B where the same show exists with *different* episode ids → the viewings land
    on B's episodes and there is no exception.
  - `episodeImportedWatched_withNoViewingsFile_getsOneViewing`.
  - `replace_replacesThatEpisodesViewings`.
  - `v5Files_withoutWatchLogs_stillImport`.
- [ ] **Step 2: Run and confirm they fail** (compile errors).
- [ ] **Step 3: Implement** to the rules above. Export the viewings in a stable order:
  `ORDER BY mediaId, episodeId, watchedAt`.
- [ ] **Step 4: Wire the UI chains.**
  - In `SettingsCsvExportFlow.kt`, add a fourth `CreateDocument` launcher,
    `watch_logs_export.csv`, after episodes, mirroring the episodes launcher.
  - In `SettingsImportFlows.kt`, add a fourth optional pick after episodes, read with
    `readCsvFromUri` exactly like `episodesCsv`.
  - Update `SettingsRouteSafChainsTest` (androidTest) to the four-file chain.
  - Update `SampleDataSeedTest` to pass `watchLogsCsv = asset("watch_logs_sample.csv")`, and make
    sure the asset copy task copies the new sample file. Check `copySampleDataToTestAssets` in
    `app/build.gradle.kts`.
- [ ] **Step 5: Run.** `./gradlew :shared:jvmTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin -q` → green.
- [ ] **Step 6: Falsify the guards.** Remove the dedup and confirm `reimporting…` fails. Remove
  the normalisation and confirm `episodeImportedWatched…` fails.
- [ ] **Step 7: Commit:** `CSV: watch_logs_export.csv with dedup, REPLACE semantics and invariant normalisation (schema v6) (#90)`.

---

### Task 5: Extract `WatchedDatePickerDialog`

**Files:** new `app/.../ui/components/WatchedDatePickerDialog.kt`; `MarkWatchedDialog.kt` (use it).
**Test:** the existing `MarkWatchedDialogTest` and `MarkWatchedDialogPickerTest` must still pass.

**Interfaces (produces):**
`@Composable fun WatchedDatePickerDialog(initialPickMillis: Long?, confirmLabel: String, onPicked: (WatchedWhen) -> Unit, onDismiss: () -> Unit)`.
Its body is `MarkWatchedDialog`'s current `if (picking) { … }` block, using `isPickableDay`,
`pickableUpToYear` and `watchedOnPickedDay`. `MarkWatchedDialog` calls it with
`tick.initialPickMillis` and `stringResource(R.string.mark_watched_pick_confirm)`.

- [ ] **Step 1: Move the code.** This is a pure refactor with no behaviour change.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*MarkWatchedDialog*' :app:verifyRoborazziDebug :app:compileDebugAndroidTestKotlin -q` → green, with no golden diff.
- [ ] **Step 3: Commit:** `Extract WatchedDatePickerDialog from MarkWatchedDialog (#90)`.

---

### Task 6: Episode sheet: view model state, stateless content, and the route

**Files:**
- `TVShowDetailViewModel.kt` (sheet state and actions).
- New `app/.../ui/screens/EpisodeSheet.kt`, holding the stateless `EpisodeSheetContent`.
- `TVShowDetailScreen.kt`:
  - add a row tap → `onEpisodeOpen(episodeId)`;
  - add a "Watched N×" label;
  - the route hosts `ModalBottomSheet { EpisodeSheetContent(...) }`, the Watched-again dialog, the
    edit picker, and the viewing-delete Undo.
- `strings.xml`.

**Tests:**
- `TVShowDetailViewModelTest` (new cases).
- `app/src/test/.../ui/screens/EpisodeSheetContentTest.kt` (Robolectric).
- `app/src/test/.../ui/goldens/EpisodeSheetGoldenTest.kt`.
- `app/src/androidTest/.../ui/screens/EpisodeSheetEditDateTest.kt`.

**Interfaces:**
- View model:
  - `openEpisode(episodeId)` / `closeEpisode()`. These expose
    `Ready.openEpisode: OpenEpisode? (episode: EpisodeEntity, viewings: List<WatchLogEntity>)`,
    sourced from `observeViewings` through `flatMapLatest` on an `openEpisodeId` flow, nested into
    the outer `combine` the same way `pendingTick` was.
  - `requestRewatch()` sets a `pendingRewatch: PendingTick?`, built with `buildPendingTick` but with
    `earlierIds = emptyList()` and the title used by the "again" string.
  - `confirmRewatch(WatchedWhen)` clears `pendingRewatch` **before** launching, as #185 does, then
    calls `RecordRewatchUseCase.execute`.
  - `dismissRewatch()`.
  - `deleteViewing(logId)` → on success sets `viewingUndo: ViewingUndo?(id, log)`; `undoViewing(v)`
    → `restoreViewing`; `consumeViewingUndo(id)`.
  - `editViewingDate(logId, WatchedWhen)` resolves the date with `resolveWatchedAt(…, airDate = null, …)`,
    so only `On(date)` is used, then calls `editViewingDate`.
- Row label data: `Ready.viewingCounts: Map<String, Int>` from `observeViewingCounts(showId)`.
- **`MarkWatchedDialog` gains a title override.** Add `titleOverride: String? = null`, used when
  non-null. Watched again passes "Watched "%1$s" again?".
- **`EpisodeSheetContent(episode, viewings, airDateLabel, onTick, onWatchedAgain, onEditViewing: (WatchLogEntity) -> Unit, onDeleteViewing: (WatchLogEntity) -> Unit)`.**
  It's stateless and holds no picker. It shows "Not watched yet" plus a primary **Mark watched**
  when `viewings` is empty; otherwise the viewings newest first, each with Edit and Delete
  `IconButton`s, and a **Watched again** button.
- **Strings:** `episode_sheet_not_watched`, `episode_sheet_watched_again`,
  `episode_sheet_viewings_title`, `episode_sheet_edit_date_cd`, `episode_sheet_delete_viewing_cd`,
  `mark_watched_title_again` ("Watched \"%1$s\" again?"), `viewing_deleted_snackbar` ("Viewing
  removed"), and a `<plurals name="episode_watched_times">` with "Watched %d×".

Steps:
- [ ] **Step 1: Write the failing view-model tests:**
  - `openEpisode_exposesItsViewings`;
  - `watchedAgain_addsAViewing`;
  - `doubleConfirmRewatch_writesOnce` (`StandardTestDispatcher`, then await
    `viewings.size == 2`);
  - `deleteLastViewing_unticks_andUndoRestores`;
  - `deleteMiddleViewing_undo_restoresIt`;
  - `editViewingDate_recomputesWatchedAt`.
- [ ] **Step 2: Write the failing Robolectric tests** on `EpisodeSheetContent`:
  - the empty state shows "Not watched yet" and Mark watched calls `onTick`;
  - three viewings render newest first;
  - Edit and Delete call back with the right log;
  - Watched again calls back.

  Also test the "Watched 3×" label on `TVShowDetailScreen` through its existing stateless test
  set-up. Add `viewingCounts` to the fixture `Ready` state, and to `TVShowDetailUiState.Ready` as
  a defaulted field: `viewingCounts: Map<String, Int> = emptyMap()`.
- [ ] **Step 3: Run and confirm they fail.**
- [ ] **Step 4: Implement** the view model, `EpisodeSheetContent`, the row tap and label, and the
  route hosting.
  - The row's text `Column` gets `Modifier.clickable { onEpisodeOpen(episode.id) }`. The circle
    keeps its own toggle.
  - The `TVShowDetailScreen` signature gains `onEpisodeOpen: (String) -> Unit`. Thread it through
    every caller: previews, goldens, the occlusion lane and androidTest, exactly as #185 did.
  - The route hosts:
    - `openEpisode?.let { ModalBottomSheet(onDismissRequest = viewModel::closeEpisode) { EpisodeSheetContent(...) } }`;
    - `pendingRewatch?.let { MarkWatchedDialog(it, titleOverride = …, onConfirm = { w, _ -> viewModel.confirmRewatch(w) }, onDismiss = viewModel::dismissRewatch) }`;
    - an `editing: WatchLogEntity?` piece of local state that shows
      `WatchedDatePickerDialog(initialPickMillis = editing.watchedAt.toEpochMilliseconds(), …)`.
  - The viewing Undo uses the screen's snackbar, keyed on `viewingUndo?.id`, the same shape as the
    untick Undo.
- [ ] **Step 5: Write the golden.** `EpisodeSheetGoldenTest` captures `EpisodeSheetContent` with
  three viewings, with `alsoAssert` checking the three dates and "Watched again". Record it and
  **look at it**.
- [ ] **Step 6: Write the androidTest.** `EpisodeSheetEditDateTest` is stateless: on
  `EpisodeSheetContent`, tap Edit, and the host shows `WatchedDatePickerDialog` opened on the
  viewing's date. Confirm, and assert `onPicked` received `watchedOnPickedDay(thatMillis)`.
- [ ] **Step 7: Run the full gate.** Compile androidTest. Re-record the TV show goldens if the
  label changed their fixture, look at them, and commit the PNGs separately.
- [ ] **Step 8: Falsify the guard.** Move `pendingRewatch = null` inside the launch and confirm
  `doubleConfirmRewatch_writesOnce` fails.
- [ ] **Step 9: Commit:** `Episode sheet: viewing history, Watched again, edit and delete with Undo (#90)`.
  Commit the goldens separately.

---

### Task 7: Changelog, device, manual check, review, PR

- [ ] **`CHANGELOG.md`, `[Unreleased]`:**
  - Under `### Added`: **Watch history** (#90). Tap an episode to see every time you watched it;
    mark it **Watched again**; edit or delete a viewing, with Undo. A re-watched episode shows
    "Watched 2×". Viewings are included in the CSV export as `watch_logs_export.csv`, and are
    restored on import.
  - Under `### Changed`: unticking an episode now also removes its viewings, and Undo restores
    them.
  - Under `### Internal`: CSV schema v6; the startup backfill.
- [ ] **Run the full gate.** Then the device suite (`adb devices`, then
  `./gradlew :app:connectedDebugAndroidTest`, without `leaveApksInstalledAfterRun`), then
  `./gradlew :app:seedDebugDevice`.
- [ ] **Check by hand over adb:**
  - open Fleabag, tap the row of the sample's twice-watched episode → the sheet shows 2 viewings,
    and the row says "Watched 2×";
  - Watched again → Now → 3 viewings;
  - delete one → Undo restores it;
  - edit a date through the picker;
  - untick the episode → Undo restores all its viewings.
- [ ] **Final whole-branch review** by a fresh Opus reviewer (executing-plans), then a local
  `coderabbit review --agent --committed --base main -c AGENTS.md`.
- [ ] **Push and open the PR**, with `Closes #90` in the **body**. Leave #153 open but comment
  that part B is done.

---

## Self-review notes

- **Spec coverage:**
  - the model table → Tasks 1–2;
  - the interaction → Tasks 5–6;
  - the backfill (revision 5) → Task 3;
  - CSV (revisions 3 and 9) → Task 4;
  - the FK-safe Undo (revision 1) → Task 1;
  - the per-viewing Undo (revision 2) → Tasks 1 and 6;
  - removing the bypass paths (revision 4) → Tasks 1–2;
  - the tick writing a viewing (revision 6) → Task 1;
  - extracting the picker (revision 7) → Task 5;
  - test lanes (revision 8) → Task 6;
  - supersession and the KDoc (revision 10) → Task 1 (the #90 comment is already posted).
- **One deliberate deviation:** the backfill is a Kotlin read-then-insert inside one transaction,
  not a pure `INSERT … SELECT`, because SQLite can't produce this app's UUID ids. The spec's own
  revision 5 permits this.
- **Type consistency:**
  - `UntickEvent.previous: List<WatchLogEntity>` everywhere;
  - `restoreAll(List<WatchLogEntity>): Int` counts episodes;
  - `RecordRewatchUseCase.execute(episodeId, WatchedWhen): Resource<Unit>`;
  - `WatchedDatePickerDialog(initialPickMillis, confirmLabel, onPicked, onDismiss)` in Tasks 5
    and 6.
