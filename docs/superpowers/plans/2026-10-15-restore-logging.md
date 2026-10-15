# #115: A restore leaves a trace in the app log (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore activity reaches the persistent app log in three places:
- a staged line with row counts, written while the log store is alive;
- the outcome, written on the next launch from the restore marker;
- a WARN when startup self-heal moves the backup back.

**Architecture:**
- `logRestoreRecovery(healed, marker, logger)` is pure and lives in `DatabaseRestoreRecovery.kt`. `createAppContainer` calls it.
- `selfHealDatabaseIfNeeded` returns `Boolean`.
- `countStagedDatabaseRows(path, logger)` opens the candidate read-only, beside the validation code.
- `stage()` logs one INFO line on success.

**Tech Stack:** Kotlin Multiplatform, `androidx.sqlite` `BundledSQLiteDriver`, Room KMP (jvmTest), kotlin.test, `RecordingLogger`.

**Spec:** `docs/superpowers/specs/2026-10-15-restore-logging-design.md`

## Global Constraints

- **No `app/src/` change.** The call site is `shared/src/androidMain/.../ui/AppContainer.android.kt`.
- **Logging must never fail its caller.** Counting is best-effort: an exception gives `null` and a WARN, and `stage()`'s result never depends on it. Write it with `catchingNonCancellation` (`core/util/Cancellation.kt`, AGENTS.md §5).
- **Identifier rule:** counts, the schema version and the older/current flag only. No titles or paths in the new lines, except that the Failure message is the snackbar's own text.
- **Test placement:** pure tests go in `commonTest`; real-file tests go in `jvmTest` (`RestoreDatabaseUseCaseTest`). Use package-qualified filters and the Edit tool.
- **Graphban:** add evidence to MT-17 after each task; move it to `review` with the PR link at the end.
- **Before push:** `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.

## Review Focus

1. **An ordinary launch logs nothing new:** no marker and no heal means zero entries.
2. **`selfHealDatabaseIfNeeded` returns `true` only when the main-file rename succeeded.** The WAL-failure first call returns `false`; the retry returns `true`.
3. **Counting creates no `-wal`/`-shm`** beside the candidate. The connection is opened read-only.
4. **Existing `entries.single()` assertions** on `stage()` rejections are unaffected: the staged line is written on success only.
5. **An older candidate** without `episodes`/`watch_logs` reports `n/a`, not 0.

---

### Task 1: Next-launch logging, and self-heal reports whether it healed

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/DatabaseRestoreRecovery.kt` (`selfHealDatabaseIfNeeded` ~line 131; add `logRestoreRecovery`)
- Modify: `shared/src/androidMain/kotlin/com/hub/media/ui/AppContainer.android.kt` (~lines 44-48)
- Test (create): `shared/src/commonTest/kotlin/com/hub/media/core/database/RestoreRecoveryLoggingTest.kt`
- Test (modify): `shared/src/jvmTest/.../portability/domain/RestoreDatabaseUseCaseTest.kt`, the self-heal tests (~lines 599, 628, 644, 678, 692)

**Interfaces:** Produces `public suspend fun selfHealDatabaseIfNeeded(liveDatabaseFilePath: String): Boolean` and `internal fun logRestoreRecovery(healed: Boolean, marker: RestoreMarker?, logger: Logger = AppLogger)`.

- [ ] **Step 1: Write the failing tests.**

`RestoreRecoveryLoggingTest.kt`:

```kotlin
package com.hub.media.core.database

import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #115: the launch after a restore is the first moment the persistent log store is alive again. */
class RestoreRecoveryLoggingTest {
    @Test
    fun ordinaryLaunch_logsNothing() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = false, marker = null, logger = logger)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun heal_logsOneWarning() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = true, marker = null, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertTrue("pre-restore backup was moved back" in entry.message, entry.message)
    }

    @Test
    fun successMarker_logsOneInfo() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = false, marker = RestoreMarker.Success, logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertTrue("restore completed before this launch" in entry.message, entry.message)
    }

    @Test
    fun failureMarker_logsOneErrorCarryingTheMessage() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = false, marker = RestoreMarker.Failure("Restore aborted: disk full."), logger = logger)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertTrue("Restore aborted: disk full." in entry.message, entry.message)
    }

    @Test
    fun healAndFailure_logWarnThenError() {
        val logger = RecordingLogger()
        logRestoreRecovery(healed = true, marker = RestoreMarker.Failure("x"), logger = logger)
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), logger.entries.map { it.level })
    }
}
```

In `RestoreDatabaseUseCaseTest`, wrap each existing `selfHealDatabaseIfNeeded(livePath)` call:
- `selfHeal_liveFilePresent_isANoOp`: `assertFalse(selfHealDatabaseIfNeeded(livePath), "nothing to heal")`
- `selfHeal_liveFileMissingButBackupExists_restoresFromBackup`: `assertTrue(selfHealDatabaseIfNeeded(livePath), "healed")`
- `selfHeal_neitherLiveNorBackupExists_isANoOp`: `assertFalse(selfHealDatabaseIfNeeded(livePath), "nothing to heal")`
- `selfHeal_walRenameFailure_…`: first call `assertFalse(…, "main file left for the next launch")`; the retry `assertTrue(…, "retry heals")`

Anchor each edit on the line *after* the call, which is unique.

- [ ] **Step 2: Run them to verify they fail.**
`./gradlew :shared:jvmTest --tests 'com.hub.media.core.database.RestoreRecoveryLoggingTest' --tests 'com.hub.media.features.portability.domain.RestoreDatabaseUseCaseTest'`
Expected: compile failures, `Unresolved reference 'logRestoreRecovery'` and an argument-type mismatch (Unit where a Boolean is expected).

- [ ] **Step 3: Implement.** `selfHealDatabaseIfNeeded`:

```kotlin
public suspend fun selfHealDatabaseIfNeeded(liveDatabaseFilePath: String): Boolean {
    val backupPath = preRestoreBackupPath(liveDatabaseFilePath)
    if (fileExists(liveDatabaseFilePath) || !fileExists(backupPath)) return false

    val backupWal = "$backupPath-wal"
    val backupShm = "$backupPath-shm"
    val walRestored = !fileExists(backupWal) || renameFile(backupWal, "$liveDatabaseFilePath-wal")
    val shmRestored = !fileExists(backupShm) || renameFile(backupShm, "$liveDatabaseFilePath-shm")
    return walRestored && shmRestored && renameFile(backupPath, liveDatabaseFilePath)
}
```

Add `@return` to its KDoc: "`true` only when the backup's main file was moved back into place; `false` when there was nothing to heal or a sidecar move failed (the next launch retries)". Then add:

```kotlin
private const val RECOVERY_TAG = "RestoreRecovery"

/**
 * Records, on the launch after a restore, what [selfHealDatabaseIfNeeded] and [consumeRestoreMarker]
 * found (#115). This is the first moment the persistent log store is alive again: the restore
 * itself ran after `AppContainer.close()` had shut the store down, so its own lines reached logcat
 * only. An ordinary launch (nothing healed, no marker) logs nothing.
 */
internal fun logRestoreRecovery(
    healed: Boolean,
    marker: RestoreMarker?,
    logger: Logger = AppLogger,
) {
    if (healed) {
        logger.warn(RECOVERY_TAG) {
            "the live database was missing at startup; the pre-restore backup was moved back into place " +
                "(an interrupted restore)"
        }
    }
    when (marker) {
        RestoreMarker.Success ->
            logger.info(RECOVERY_TAG) { "restore completed before this launch: the database was replaced from a staged backup" }
        is RestoreMarker.Failure -> logger.error(RECOVERY_TAG) { "restore failed before this launch: ${marker.message}" }
        null -> Unit
    }
}
```

Imports, if absent: `AppLogger`, `Logger`, `info`, `warn`, `error` from `com.hub.media.core.util`. In `AppContainer.android.kt`:

```kotlin
    val pendingRestoreMarker =
        runBlocking {
            val healed = selfHealDatabaseIfNeeded(databaseFilePath)
            consumeRestoreMarker(databaseFilePath).also { marker -> logRestoreRecovery(healed, marker) }
        }
```

(Plus `import com.hub.media.core.database.logRestoreRecovery`. Since it is `internal` and androidMain is in the same module, it is visible.)

- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Prove the return value is pinned.** Temporarily change the early `return false` to `return true` and re-run. Expected: `selfHeal_liveFilePresent_isANoOp` and `selfHeal_neitherLiveNorBackupExists_isANoOp` fail. Restore it.
- [ ] **Step 6: Commit** `Restore: the next launch logs the outcome and any self-heal (#115)`, then append evidence to MT-17.

---

### Task 2: The staged line, with row counts

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/StagedDatabaseValidation.kt` (add `StagedRowCounts`, `countStagedDatabaseRows`, `toLogText`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/RestoreDatabaseUseCase.kt` (`stage()` success path, ~line 260)
- Test: `RestoreDatabaseUseCaseTest` (jvmTest)

**Interfaces:**
- `internal data class StagedRowCounts(val items: Long?, val sessions: Long?, val episodes: Long?, val viewings: Long?)`
- `internal suspend fun countStagedDatabaseRows(path: String, logger: Logger = AppLogger): StagedRowCounts?`
- `internal fun StagedRowCounts?.toLogText(): String`

- [ ] **Step 1: Write the failing tests** (in `RestoreDatabaseUseCaseTest`, near the logging tests; add imports `kotlin.time.Instant`, `com.hub.media.features.books.data.ReadingSessionRepository` and `com.hub.media.core.util.LogLevel`, if absent):

```kotlin
    @Test
    fun stage_currentSchema_logsAStagedLineWithRowCounts() =
        runTest {
            val candidatePath = path("candidate.db")
            val db = openDatabase(candidatePath)
            val books = BookRepository(db)
            val sessions = ReadingSessionRepository(db)
            val ids =
                (1..2).map { i ->
                    assertIs<Resource.Success<String>>(
                        books.addBook(
                            title = "Book $i",
                            releaseYear = 2019,
                            purchasePrice = null,
                            format = BookFormat.HARDCOVER,
                            totalPages = 100,
                            isbn = null,
                            externalIdentifiers = emptyList(),
                        ),
                    ).data
                }
            repeat(3) { n ->
                assertIs<Resource.Success<String>>(
                    sessions.logSession(
                        mediaId = ids.first(),
                        timestampStart = Instant.fromEpochSeconds(1_700_000_000L + n * 3_600),
                        timestampEnd = Instant.fromEpochSeconds(1_700_000_600L + n * 3_600),
                        durationSeconds = 600,
                        startUnit = n * 10.0,
                        endUnit = n * 10.0 + 10,
                    ),
                )
            }
            db.close()
            val recorder = RecordingLogger()

            val result = DefaultRestoreDatabaseUseCase(path("live.db"), logger = recorder).stage(candidatePath)

            assertIs<Resource.Success<StagedRestoreInfo>>(result)
            val staged = recorder.entries.single { it.message.startsWith("Restore staged") }
            assertEquals(LogLevel.INFO, staged.level)
            for (part in listOf("schema v$APP_DATABASE_VERSION (current)", "items=2", "sessions=3", "episodes=0", "viewings=0")) {
                assertTrue(part in staged.message, "expected '$part' in: ${staged.message}")
            }
            assertFalse("Book 1" in staged.message, "identifier rule: no titles")
            // (A planned "no -wal/-shm beside the candidate" assertion was dropped: validation's own
            // read-only open already creates them -- see the spec's Amendment.)
        }

    @Test
    fun stage_olderSchema_logsMissingTablesAsNotApplicable() =
        runTest {
            migrationHelper.createDatabase(2).use { db ->
                db.execSQL(
                    "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash) " +
                        "VALUES ('media-1', 'BOOK', 'Old Book', 2015, 4.99, 1600000000000, NULL)",
                )
            }
            val candidatePath = tempDir.resolve("migration-seed.db").toString()
            val recorder = RecordingLogger()

            DefaultRestoreDatabaseUseCase(path("live.db"), logger = recorder).stage(candidatePath)

            val staged = recorder.entries.single { it.message.startsWith("Restore staged") }
            for (part in listOf("schema v2 (older; migrates on next launch)", "items=1", "sessions=0", "episodes=n/a", "viewings=n/a")) {
                assertTrue(part in staged.message, "expected '$part' in: ${staged.message}")
            }
        }
```

- [ ] **Step 2: Run them to verify they fail.** `./gradlew :shared:jvmTest --tests 'com.hub.media.features.portability.domain.RestoreDatabaseUseCaseTest'`. Expected: both new tests FAIL with `NoSuchElementException` from `single {}`, because no staged line exists. If anything else fails (e.g. the `logSession` signature), fix the test first.

- [ ] **Step 3: Implement.** In `StagedDatabaseValidation.kt`:

```kotlin
/**
 * Row counts read from a staged restore candidate, for the restore's log line (#115). A `null` field
 * is a table the candidate does not have: an older backup predating it (episodes and watch logs
 * arrived in schema v6). It is not the same as 0.
 */
internal data class StagedRowCounts(
    val items: Long?,
    val sessions: Long?,
    val episodes: Long?,
    val viewings: Long?,
)

/**
 * Counts the rows a restore would bring in, by opening the candidate read-only exactly as
 * [validateStagedDatabaseIntegrity] does, so no `-wal`/`-shm` appears beside it. Best-effort: any
 * failure is logged and returns `null`, and a restore never depends on it. Logging must not become
 * a new source of failure for its caller.
 */
internal suspend fun countStagedDatabaseRows(
    path: String,
    logger: Logger = AppLogger,
): StagedRowCounts? =
    withContext(Dispatchers.IO) {
        catchingNonCancellation(
            block = {
                BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { connection ->
                    val tables = mutableSetOf<String>()
                    connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                        while (statement.step()) tables += statement.getText(0)
                    }

                    fun count(table: String): Long? =
                        if (table !in tables) {
                            null
                        } else {
                            connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                                statement.step()
                                statement.getLong(0)
                            }
                        }
                    StagedRowCounts(
                        items = count("media_items"),
                        sessions = count("reading_sessions"),
                        episodes = count("episodes"),
                        viewings = count("watch_logs"),
                    )
                }
            },
            onException = { e ->
                logger.warn(TAG, e) { "could not count the staged database's rows" }
                null
            },
        )
    }

/** `items=…, sessions=…, episodes=…, viewings=…`, with `n/a` for an absent table. */
internal fun StagedRowCounts?.toLogText(): String =
    if (this == null) {
        "counts unavailable"
    } else {
        "items=${items ?: "n/a"}, sessions=${sessions ?: "n/a"}, episodes=${episodes ?: "n/a"}, viewings=${viewings ?: "n/a"}"
    }
```

(`TAG` is the file's existing tag. Use whatever name the file already uses for it. Import `com.hub.media.core.util.catchingNonCancellation`.)

In `stage()`, just before `return Resource.Success(StagedRestoreInfo(...))`:

```kotlin
        val counts = countStagedDatabaseRows(incomingFilePath, logger)
        logger.info(TAG) {
            "Restore staged: schema v${info.userVersion} " +
                (if (info.userVersion < APP_DATABASE_VERSION) "(older; migrates on next launch)" else "(current)") +
                "; ${counts.toLogText()}"
        }
```

- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS for the whole class.
- [ ] **Step 5: Commit** `Restore: stage() logs what the candidate holds (#115)`, then append evidence to MT-17.

---

### Task 3: CHANGELOG, gate, review, PR

- [ ] **CHANGELOG** `[Unreleased]` → `### Internal` (append):
  `- A database restore now leaves a trace in the app log: a line when a backup is staged (schema version and row counts), and on the next launch the outcome, plus a warning if startup had to move the pre-restore backup back. The restore's own lines were written after the log store had been shut down, so they never reached it. (#115)`
- [ ] **Full gate** (Global Constraints).
- [ ] **Fresh Opus review** with this plan's Review Focus. **CodeRabbit:** `coderabbit review --agent --committed --base main -c AGENTS.md`.
- [ ] **Push and open the PR** (`Closes #115`), **comment on #115** linking the root-cause comment, and **move MT-17 to `review`** with the PR URL as evidence.
- [ ] **Outcomes table:** `C:\Users\blain\graphban-outcomes\115-restore-logging.md`.
