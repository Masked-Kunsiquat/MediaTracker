# #203 — the restore swap guarantees its own completion

**Issue:** [#203](https://github.com/Masked-Kunsiquat/MediaTracker/issues/203).
**Branch:** `fix/203-restore-swap-rollback`, stacked on `refactor/81-resource-envelope` (#205).
**Measured on:** `refactor/81-resource-envelope` at `c2e7272`, 2026-10-15.

## What #203 claimed, and what the code shows

#203 was filed during #81 item 6 on a reading of `DefaultRestoreDatabaseUseCase.swap` alone. Tracing
its caller and its file operations narrowed it considerably:

| #203 said | The code |
| --- | --- |
| Cancellation mid-swap lands in the bare `catch (e: Exception)` and is reported as a failure | The only caller (`SettingsBackupRestoreFlows.kt`, restore confirm) runs `appContainer.close()` → `commit` → `restartApp` inside `withContext(Dispatchers.IO + NonCancellable)`, so a cancellation cannot reach the swap today. |
| An exception mid-swap skips the rollback | `renameFile` (jvm and android actuals) catches its own exceptions and returns `false`, which takes the rollback paths. `fileExists` / `deleteFileIfExists` can throw only a `SecurityException` from `File.exists()` / `delete()` in app-private storage. |
| The live file may be missing until the next launch | `restartApp` runs unconditionally after `commit`; `selfHealDatabaseIfNeeded` then restores the pre-restore backup before Room opens anything. |

So the bare catch is unreachable defence-in-depth, not a live bug. What is actually wrong:

1. **`commit`'s safety is borrowed from its caller.** The only thing preventing a cancellation
   between, say, "live file moved aside" and "staged file activated" is that one call site
   remembered `NonCancellable`. A second caller (a future import-from-restore flow, a test
   harness, a refactor of the Settings flow) inherits none of it.
2. **The class KDoc is false** where it says every swap step is "a single filesystem call with
   **no** intervening suspension point". Each step is a `withContext(Dispatchers.IO)` hop, and each
   hop is a cancellation check.
3. **The bare catch says nothing about why it does not roll back**, so it reads as an oversight —
   which is how #203 came to be filed.

## Design

### `commit` runs under `NonCancellable` itself

```kotlin
override suspend fun commit(staged: StagedRestoreInfo): Resource<Unit> =
    withContext(NonCancellable) {
        val result = swap(staged)
        val marker = when (result) { ... }   // unchanged
        writeRestoreMarker(liveDatabaseFilePath, marker)
        result
    }
```

The swap and the marker write both sit inside: a restore that has started always finishes the
swap (or its existing `false`-path rollback) **and** records its outcome. `NonCancellable` only
replaces the `Job`; the dispatcher is inherited, so the app's `Dispatchers.IO` context still
applies.

**The app caller is unchanged.** Its own `NonCancellable` still guards `appContainer.close()` and
`restartApp`, which are outside `commit`; its comment ("commit itself never throws … the only
failure mode this guards against is cancellation") stays true. No `app/src/` change, so the device
lane is not required for this PR.

### The bare catch documents itself

The catch keeps its behaviour (log, `Resource.Error("Restore failed: …")`) and gains a comment
stating: what can reach it (a `SecurityException` from `File.exists()`/`delete()`; cancellation no
longer can), why it does not attempt a rollback (it cannot know which step threw, and the
unconditional restart plus `selfHealDatabaseIfNeeded` already restore the backup whenever the live
file is missing), and that it is deliberately not `catchingNonCancellation` (there is nothing to
rethrow under `NonCancellable`).

### KDoc correction

The "exact sequence" section replaces the "no intervening suspension point" claim with the truth:
each step is an IO hop, and `commit` runs the whole sequence under `NonCancellable`, so a
cancellation cannot land between steps; the practical window for a *process death* is unchanged
(and is what `selfHealDatabaseIfNeeded` covers).

## Testing (TDD, `jvmTest`, real files — `RestoreDatabaseUseCaseTest`)

**`commit_callerCancelledBeforeCommit_stillSwapsIn_andRecordsSuccess`**

- Arrange: a live database and a distinct candidate, staged outside any cancelled scope; capture
  the staged file's bytes.
- Act: `launch { cancel(); reachedCommit = true; useCase.commit(staged) }`, then `join()`.
- Assert: `reachedCommit` (positive control); the live path's bytes equal the captured candidate
  bytes; `consumeRestoreMarker(livePath)` is the success marker.
- **Expected RED today:** the swap's first `fileExists` hop throws `CancellationException` into the
  bare catch, the swap returns `Error` with the live file untouched, and the marker write throws —
  so the bytes assertion fails. Watch it fail for that reason before the fix; if it fails for any
  other reason, or passes, stop and find out why.

The existing commit/self-heal tests (`commit_stagedFileMissing_…`, `commit_walSidecarRenameFailure_…`,
`commit_noLiveDatabaseExistedYet_…`, the round trips) are the regression net for the unchanged
`false`-path behaviour.

## Also in this PR

- `CHANGELOG.md` `[Unreleased]` → `### Internal`: one entry.
- #203's description rewritten to match "What #203 claimed" above (owner already approved the
  re-scope).

## Out of scope

A rollback on the exception path (would need fault injection in the file ops to test a path nothing
reaches; declined in brainstorming). Any change to the app-side caller. Any change to
`selfHealDatabaseIfNeeded`.
