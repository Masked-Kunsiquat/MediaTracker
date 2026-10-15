# #115: a restore leaves a trace in the app log

**Issue:** [#115](https://github.com/Masked-Kunsiquat/MediaTracker/issues/115). **Graphban:** MT-17.
**Branch:** `feat/115-restore-logging`, from `main` at `0e3512a`.

## Root cause

`DefaultRestoreDatabaseUseCase` has logged a restore's outcome since `9f99292`, and #115's device
log still showed nothing. The order of events explains it:

1. **`stage()`** runs while the persistent log store is alive, but logs nothing when it succeeds.
2. **The Settings confirm** runs `appContainer.close()` → `commit()` → `restartApp()`.
   `AppContainer.close()` calls `logFileStore.shutdown()` **first**. So `commit()`'s INFO/ERROR
   lines reach logcat only, never the app log the in-app viewer reads, and the process is then
   killed.
3. **The next launch's `createAppContainer`** runs `selfHealDatabaseIfNeeded` and
   `consumeRestoreMarker`. It knows whether the restore succeeded, and whether the live file had to
   be restored from the backup, but it logs neither.

## Design: log at the two moments the store is alive

### 1. When a restore is staged (`stage()` success)

One INFO line, tag `RestoreDatabaseUseCase`:

```
Restore staged: schema v5 (older; migrates on next launch); items=212, sessions=1480, episodes=n/a, viewings=n/a
Restore staged: schema v7 (current); items=212, sessions=1480, episodes=3104, viewings=96
```

The counts come from a new
`internal suspend fun countStagedDatabaseRows(path: String): StagedRowCounts?` in
`StagedDatabaseValidation.kt`. It opens the candidate **read-only** with the same
`BundledSQLiteDriver` + `SQLITE_OPEN_READONLY` that validation uses, so it creates no `-wal`/`-shm`
side effects, and runs `SELECT COUNT(*)` on:

| field | table | present since |
| --- | --- | --- |
| `items` | `media_items` | v1 |
| `sessions` | `reading_sessions` | v1 |
| `episodes` | `episodes` | v6 |
| `viewings` | `watch_logs` | v6 |

A table the candidate doesn't have (an older backup) is `null` and is logged as `n/a`. This is
decided from `sqlite_master`, not from the version, so the line reports what is actually in the
file.

**Counting is best-effort.** Any exception makes it return `null`, after a WARN, and the staged line
says `counts unavailable`. `stage()`'s result never depends on it, following the rule that logging
must never become a new source of failure (ROADMAP Task 15 Phase A). By the time it runs, the file
has already passed `PRAGMA integrity_check`.

**Identifier rule:** counts, the schema version and the older/current flag only. No titles, and no
path. The staged file sits at an app-private cache path, but it adds nothing here.

### 2. On the next launch, after the restore marker is read

A new shared function in `DatabaseRestoreRecovery.kt`, called from `createAppContainer`
(`shared/src/androidMain`) right after `consumeRestoreMarker`:

```kotlin
internal fun logRestoreRecovery(healed: Boolean, marker: RestoreMarker?, logger: Logger = AppLogger)
```

Tag `RestoreRecovery`:
- **`healed`** → WARN: `the live database was missing at startup; the pre-restore backup was moved back into place (an interrupted restore)`
- **`RestoreMarker.Success`** → INFO: `restore completed before this launch: the database was replaced from a staged backup`
- **`RestoreMarker.Failure(message)`** → ERROR: `restore failed before this launch: <message>`. The message is the text the Settings snackbar shows. It can include the app-private pre-restore backup path, which the existing failure logs already include.
- **`healed == false` and `marker == null`** (every ordinary launch) → nothing.

`AppLogger` already includes the file sink at this point: `MediaTrackerApplication.onCreate`
configures it before `appContainer` is first touched.

### 3. Self-heal says whether it healed

`selfHealDatabaseIfNeeded(path)` returns `Boolean`: `true` only when it moved the backup's main file
back into place. Every path that changes nothing returns `false`: the live file is present, there
is no backup, or a sidecar move failed so the main file was left for the next launch. Its existing
tests keep passing, and gain assertions on the return value.

### Unchanged

`commit()`'s own log lines (they still reach logcat), `AppContainer.close()`'s ordering (it is
deliberate, see its comment), the restore flow, and the snackbar. **No `app/src/` change.**

## Testing (TDD, red first)

- **`commonTest`, `RestoreRecoveryLoggingTest`**, with `RecordingLogger`:
  - an ordinary launch logs nothing (positive control: the logger is wired, see the next cases);
  - healed only → exactly one WARN;
  - Success → one INFO;
  - Failure("…") → one ERROR containing the message;
  - healed together with Failure → WARN then ERROR, in that order.
- **`jvmTest`, `RestoreDatabaseUseCaseTest` (real files):**
  - staging a current-schema candidate seeded with 2 items and 3 reading sessions logs one INFO
    containing `schema v7 (current)`, `items=2`, `sessions=3`, `episodes=0` and `viewings=0`;
  - staging an older v2 candidate (built through `MigrationTestHelper`, as the existing
    older-schema tests do) logs `(older; migrates on next launch)` with `episodes=n/a` and
    `viewings=n/a`;
  - `countStagedDatabaseRows` leaves no `-wal`/`-shm` next to the candidate;
  - the self-heal tests also assert `true` when healed, and `false` when the live file is present
    and when there is no backup.
- **The full gate.** No device lane, since `app/src/` is unchanged.

## Also in this PR

`CHANGELOG.md` `[Unreleased]` → `### Internal`: one entry, because the in-app log viewer is a
diagnostics surface rather than a feature. A closing comment on #115, linking the root-cause comment.

## Out of scope

Showing the counts in the restore confirmation dialog: that's a UI change, and could be its own
issue. Reordering `AppContainer.close()`. A "restore requested" line at confirm time: the staged
line is written moments before the confirm dialog appears, and the outcome line on the next launch
brackets the restore.

## Amendment (during implementation)

The design said the read-only count "creates no `-wal`/`-shm` side effects", and the plan's test
asserted it. That is not true, and not because of this change: `stage()`'s existing integrity
validation, a read-only `BundledSQLiteDriver` open of a WAL-mode file, already creates both
sidecars beside the candidate. Proven by disabling the new counting call: the sidecars still
appear. That contradicts `RestoreDatabaseUseCase`'s own KDoc, and the staged file's sidecars are
probably left orphaned after the swap. The assertion was dropped from #115's test, and the finding
was reported to the owner as a separate issue rather than folded in here.
