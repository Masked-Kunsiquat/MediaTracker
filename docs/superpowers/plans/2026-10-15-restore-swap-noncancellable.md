# #203 — Restore swap guarantees its own completion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DefaultRestoreDatabaseUseCase.commit` runs its swap and marker write under `NonCancellable` itself, so a cancelled caller can no longer interrupt a database replacement; correct the KDoc and document the bare catch.

**Architecture:** One `withContext(NonCancellable)` around `commit`'s body. No signature change, no `app/src/` change. Text-only edits for the KDoc and the catch.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, Room KMP (jvmTest with real files), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-15-restore-swap-noncancellable-design.md`

## Global Constraints

- No behaviour change for any non-cancelled restore: every existing `RestoreDatabaseUseCaseTest` passes unchanged.
- No `app/src/` change. No new dependency.
- Restore tests live in `shared/src/jvmTest/.../features/portability/domain/RestoreDatabaseUseCaseTest.kt` (Room-touching → jvmTest, AGENTS.md §7).
- Package-qualified test filters only; source edits via the Edit tool.
- Before push: `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.

## Review Focus

1. **The test reaches the swap.** A cancelled caller must get *into* `commit` — the positive control `reachedCommit` — and the RED failure must be "nothing was moved", not an unrelated exception (the #81 lesson: a cancellation test can go green or red without touching the code under test).
2. **Dispatcher unchanged.** `withContext(NonCancellable)` replaces only the Job; the app's `Dispatchers.IO` still applies (no main-thread file I/O).
3. **Marker written on every outcome** — success, `false`-path failures, and the catch — exactly as before.
4. **KDoc no longer claims "no intervening suspension point".**

---

### Task 1: `commit` under `NonCancellable`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/RestoreDatabaseUseCase.kt` (`commit`, ~line 265; the bare catch, ~line 383; the class KDoc "exact sequence" paragraph, ~line 135)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/portability/domain/RestoreDatabaseUseCaseTest.kt`

**Interfaces:** Consumes `preRestoreBackupPath(livePath)` (internal, `core.database`), `consumeRestoreMarker(livePath): RestoreMarker?`, `RestoreMarker.Success`. Produces no new API.

- [ ] **Step 1: Write the failing test** — add after `commit_noLiveDatabaseExistedYet_stillSwapsInSuccessfully`; imports `com.hub.media.core.database.preRestoreBackupPath`, `kotlinx.coroutines.cancel`, `kotlinx.coroutines.launch`:

```kotlin
    @Test
    fun commit_callerCancelledBeforeCommit_stillSwapsIn_andRecordsSuccess() =
        runTest {
            // #203: commit's safety must not be borrowed from its caller's NonCancellable. A caller
            // already cancelled when it reaches commit must still get a whole swap and a marker --
            // a restore abandoned between "live moved aside" and "staged activated" is the one
            // state this use case exists to prevent.
            val livePath = path("live.db")
            createFreshDatabaseFile(livePath)
            val candidatePath = path("candidate.db")
            createFreshDatabaseFile(candidatePath)
            val useCase = DefaultRestoreDatabaseUseCase(livePath)
            val staged = assertIs<Resource.Success<StagedRestoreInfo>>(useCase.stage(candidatePath)).data
            val backupPath = preRestoreBackupPath(livePath)
            assertFalse(File(backupPath).exists(), "precondition: no backup before the swap")

            var reachedCommit = false
            val job =
                launch {
                    cancel()
                    reachedCommit = true
                    useCase.commit(staged)
                }
            job.join()

            assertTrue(reachedCommit, "positive control: commit must actually have been called")
            assertTrue(File(backupPath).exists(), "the live database must have been moved aside")
            assertTrue(File(livePath).exists(), "the staged database must have been activated")
            assertFalse(File(staged.stagedFilePath).exists(), "the staged file must have been consumed")
            assertEquals(RestoreMarker.Success, consumeRestoreMarker(livePath))
        }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.features.portability.domain.RestoreDatabaseUseCaseTest'`
Expected: FAIL on `"the live database must have been moved aside"` (the first `fileExists` hop throws `CancellationException` into the bare catch, so nothing moves). If it fails anywhere else — or passes — stop and find out why before Step 3.

- [ ] **Step 3: Implement** — replace `commit`:

```kotlin
    override suspend fun commit(staged: StagedRestoreInfo): Resource<Unit> =
        // NonCancellable: once a restore has started it must finish -- the swap (or its rollback)
        // and the marker recording its outcome. Every step below is a withContext(IO) hop, and so
        // a cancellation check; without this, a caller cancelled mid-swap could leave the live
        // database moved aside and the candidate not yet activated. The Settings caller also wraps
        // this in NonCancellable (for close() and restartApp), but this method's safety must not
        // depend on every caller remembering to. Only the Job is replaced: the caller's dispatcher
        // still applies.
        withContext(NonCancellable) {
            val result = swap(staged)
            val marker =
                when (result) {
                    is Resource.Success -> RestoreMarker.Success
                    is Resource.Error -> RestoreMarker.Failure(result.message)
                }
            writeRestoreMarker(liveDatabaseFilePath, marker)
            result
        }
```

Imports: `kotlinx.coroutines.NonCancellable`, `kotlinx.coroutines.withContext`.

- [ ] **Step 4: Run the class to verify it passes**

Same command as Step 2. Expected: PASS, all `RestoreDatabaseUseCaseTest` tests.

- [ ] **Step 5: Document the bare catch** — replace

```kotlin
        } catch (e: Exception) {
            logger.error(TAG, e) { "restore failed with an unexpected exception during the swap" }
```

with

```kotlin
        } catch (e: Exception) {
            // Deliberately a bare catch, and deliberately no rollback (#203). What can reach it: a
            // SecurityException from File.exists()/delete() inside fileExists/deleteFileIfExists --
            // renameFile catches its own failures and returns false, which takes the rollback paths
            // above, and cancellation cannot arrive because commit runs this under NonCancellable
            // (so there is nothing for catchingNonCancellation to rethrow). A rollback here could
            // not know which step threw; it is also unnecessary, because a full restart follows
            // every commit and selfHealDatabaseIfNeeded restores the backup whenever the live file
            // is missing.
            logger.error(TAG, e) { "restore failed with an unexpected exception during the swap" }
```

- [ ] **Step 6: Correct the KDoc** — in the "exact sequence" paragraph, replace

```
 * where that happens. [commit] itself only ever renames/deletes files; every step below is a single
 * filesystem call with **no** intervening suspension point, so the *practical* window for a process
 * death mid-sequence is a single native syscall's duration, not this whole function's.
```

with

```
 * where that happens. [commit] itself only ever renames/deletes files. Each step below is a single
 * filesystem call made on an IO hop (`withContext(Dispatchers.IO)`), and every hop is a
 * cancellation check -- so [commit] runs the whole sequence, marker included, under
 * `NonCancellable` (#203): a cancelled caller cannot stop it between steps. A *process death* still
 * can, and the steps below say what that leaves behind at each point.
```

- [ ] **Step 7: Verify and commit**

`./gradlew ktlintFormat && ./gradlew :shared:jvmTest` → PASS.

```bash
git add shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/RestoreDatabaseUseCase.kt shared/src/jvmTest/kotlin/com/hub/media/features/portability/domain/RestoreDatabaseUseCaseTest.kt
git commit -m "Restore: commit runs under NonCancellable itself (#203)"
```

---

### Task 2: CHANGELOG, gate, review, PR

- [ ] **Step 1: CHANGELOG** — `[Unreleased]` → `### Internal` (append):

```markdown
- `DefaultRestoreDatabaseUseCase.commit` now runs the database swap and its outcome marker under `NonCancellable` itself, instead of relying on its one caller to do so; its KDoc no longer claims the swap has no suspension points, and its catch-all says why it does not roll back. (#203)
```

  Commit: `Docs: CHANGELOG for #203`.
- [ ] **Step 2: Full gate** (Global Constraints command). All green.
- [ ] **Step 3: Fresh Opus review** of `git diff refactor/81-resource-envelope...HEAD`, briefed with this plan's Review Focus.
- [ ] **Step 4: CodeRabbit** — `coderabbit review --agent --committed --base refactor/81-resource-envelope -c AGENTS.md`.
- [ ] **Step 5: Rewrite #203's description** to the spec's "What #203 claimed" table plus the fix.
- [ ] **Step 6: Push and open the PR** with base `refactor/81-resource-envelope`, body: summary, the claimed-vs-actual table, test evidence, "connectedDebugAndroidTest not run: no `app/src/` change", `Closes #203`.
- [ ] **Step 7: Graphban + outcomes** — memory outcomes appended to `C:\Users\blain\graphban-outcomes\203-restore-swap.md`.
