# #81 item 6 — the cancellation-safe error envelope

**Issue:** [#81](https://github.com/Masked-Kunsiquat/MediaTracker/issues/81), item 6 (the last one open).
**Branch:** `refactor/81-resource-envelope`. **Closes #81** when merged.
**Measured on:** `main` at `4a09df7` (v0.25.0), 2026-10-15.

## Why

Every repository, use case, network client and several ViewModels in `shared/` catch failures the
same way:

```kotlin
try { ... }
catch (e: CancellationException) { throw e }      // must come first: on JVM it IS an Exception
catch (e: Exception) { logger.error(...); Resource.Error("Failed to X: ${e.message ?: "Unknown error"}", cause = e) }
```

The first clause is load-bearing. Without it, a coroutine cancelled because a screen closed is
swallowed into a `Resource.Error`, logged as a failure, and the caller carries on as if nothing had
been cancelled. The convention holds by discipline alone.

| | 2026-09-01 (#81 comment) | now |
| --- | ---: | ---: |
| `catch (e: CancellationException)` clauses in `shared/src/commonMain` | 52 | **72** |
| files carrying one | 21 | **34** |

Two private copies of the envelope already exist (`guarded()` in `TVShowRepository` and
`MovieRepository`), and **the discipline has started to slip**: `LocalImageStorageManager.saveImage`
catches `Exception` around a `withContext(Dispatchers.IO)` with no rethrow, so a cancelled cover or
poster save becomes `Result.failure`.

## Decisions taken in brainstorming

- **Approach 2:** a primitive plus a `Resource` helper (below). Rejected: primitive only (leaves the
  ~37 standard catch bodies duplicated); a source-scanning test (needs a hand-maintained allow-list,
  the shape #109 removed, and removes no duplication).
- **No enforcement mechanism** (lint rule, source scan). The helper makes the right catch the easy
  one; a drift check that cannot reliably fail is worse than none.
- **`saveImage` is fixed in this PR**, test first, in its own commit.
- **`RestoreDatabaseUseCase.swap` is a stacked PR on top of this branch**, not part of this one. Its
  `catch (e: Exception)` has no rollback (only the `false`-return paths roll back), so any exception
  mid-swap — cancellation included — can leave the live database moved aside and the staged one not
  activated. The fix (`NonCancellable` swap + exception-path rollback) is a data-safety change and is
  reviewed as one. Its issue text is drafted for the owner before filing.
- **`TVShowDetailScreen.kt` (1,225 lines) and `BookSessionDialogs.kt` (1,067) go to a new issue**,
  drafted for the owner before filing. #81 closes when this PR merges.

## Design

### The two functions — `core/util/Cancellation.kt`

```kotlin
public inline fun <T> catchingNonCancellation(
    block: () -> T,
    onException: (Exception) -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onException(e)
    }

public inline fun <T> Logger.guardResource(
    tag: String,
    failure: String,
    logMessage: () -> String,
    block: () -> Resource<T>,
): Resource<T> =
    catchingNonCancellation(block) { e ->
        error(tag, e, logMessage)
        Resource.Error("$failure: ${e.message ?: "Unknown error"}", cause = e)
    }
```

- `inline`, not `suspend`: the lambdas are inlined, so call sites keep suspending calls,
  `return@launch`, and early `return Resource.Error(...)`. Non-suspending code can use it too.
- Catches `Exception` only. `Error`s still propagate; `TimeoutCancellationException` is a
  `CancellationException` and is rethrown — both exactly as today.
- The `CancellationException` import is `kotlin.coroutines.cancellation.CancellationException`,
  matching the existing sites.
- **The KDoc holds the one-time explanation** that ~15 call sites currently repeat in 2–4 lines
  each, plus when *not* to use the helper (a site whose cancellation branch has to do work).
- `core/util/` already exists, so §6's tree and `structure.json` need no change.

### Which sites get which (72 total)

**`guardResource` — 37 sites** whose catch body is exactly *log at ERROR with the throwable,
return `"<failure>: <e.message or Unknown error>"` with `cause = e`*. Found mechanically (regex over
each catch body) and re-checked by eye in the plan:

- `MediaRepository` ×2; `BookRepository` ×5; `ReadingSessionRepository` ×3; `MovieRepository` ×4
  (one is `guarded()`); `TVShowRepository` ×12 (one is `guarded()`); `ImportWriteRepository` ×1
- `DeleteMediaUseCase`, `ReconcileMismatchesUseCase`, `ExportArchiveUseCase`, `ExportDataUseCase`,
  `ImportDataUseCase` ×2, `BackfillShowEpisodesUseCase`, `EnsureViewingsUseCase`,
  `MarkEpisodesWatchedUseCase`, `RecordRewatchUseCase`

`MovieRepository.guarded()` and `TVShowRepository.guarded()` are deleted; their callers call
`logger.guardResource` directly. (Their `failure` strings differ — Movie interpolates `$what`, TV
says "Failed to update viewing" — and each call keeps its own.)

**`catchingNonCancellation` — 30 sites** with the same rethrow but their own failure handling, kept
verbatim inside `onException`:

- Clients: `OpenLibraryClient` ×4, `OpenLibrarySearchClient` ×6, `GoogleBooksClient` ×2,
  `TmdbClient` ×2, `CoverImageDownloader`, `OpenLibraryIsbnCoverProbe`
- Use cases: `FetchPosterUseCase`, `ImportArchiveUseCase` (its `continue` stays legal: inline
  lambda inside the loop), `SweepStaleTempFilesUseCase`
- ViewModels: `SettingsViewModel` ×5, `RestoreViewModel`, `EditMovieViewModel`,
  `EditTVShowViewModel`, `MismatchReviewViewModel` ×2, `BackfillViewModel` (the `init`
  progress read only)

**Left hand-written — 5 sites**, because the cancellation branch does work or the catch is not
`Exception`: `BulkBackfillUseCase`, `BulkTmdbBackfillUseCase` (log the resume point before
rethrowing), `DatabaseBackupUseCase` (deletes the partial staging file under `NonCancellable`),
`BackfillViewModel.start` (`settleOutOfRunning()`), `LogFileStore` (catches `Throwable`).

If any site turns out, on reading it in full, not to fit its group (a `finally`, a nested `try`, a
subtle message difference), it moves to the next-looser group — never forced into the tighter one.

**Plus `LocalImageStorageManager.saveImage`** (not currently counted, which is the point): moves to
`catchingNonCancellation`, fixing the swallow.

### Comment preservation

The catch blocks hold this codebase's "why" comments, so this PR is reviewed for comment loss as
much as for code:

- A comment that **only restates the generic reason** ("on JVM CancellationException is an
  Exception, so swallowing it would break structured concurrency…") is deleted; that reason lives
  once in the helper KDoc.
- A comment that names **a consequence specific to its site** stays at that call site. Known
  examples: `OpenLibraryClient` (~219, a swallowed cancellation "would look identical to 'no such
  author'"), `OpenLibrarySearchClient` (~251, "every keystroke that supersedes an in-flight
  request"), `OpenLibraryIsbnCoverProbe` (~189, "carry on as though the probe had simply found no
  cover"), `GoogleBooksClient`/`OpenLibraryClient` inner catches ("a cancelled deserialization is
  not a malformed-JSON failure").
- Every comment inside a `catch (e: Exception)` body moves into `onException` untouched.
- **The PR body carries a table of every deleted comment** (file, first words, reason), so the owner
  can check that nothing explanatory went.

## Behaviour

No change, except `saveImage` now propagates cancellation. Every user-visible message and log line
is byte-identical; the many existing tests that assert `Resource.Error.message` text are the net
that proves it.

## Testing

TDD; each test written first and watched failing.

- **`commonTest` — `CancellationTest`** (the helpers touch no Room, so they run on every target):
  - `catchingNonCancellation`: success passes through (positive control); `CancellationException`
    is rethrown, `onException` never called; an `Exception` reaches `onException`; an `Error`
    propagates uncaught; a non-local `return` from `block` works.
  - `guardResource`: the message is `"<failure>: <e.message>"`; a null `e.message` gives
    `"Unknown error"`; `cause` is the thrown exception; exactly one ERROR log with that throwable
    and the given message; a `Resource.Error` returned *by* the block passes through without
    logging; cancellation rethrown with nothing logged.
- **`saveImage`**: a test that cancels a save in flight and expects the cancellation to propagate,
  run red against current code first. Placed where `LocalImageStorageManager`'s existing tests live.
- **The sweep** is verified by the full gate (AGENTS.md §7) after each layer's commit.

## Commits

1. Spec (this file).
2. Helpers + `CancellationTest`.
3. `saveImage` fix + test.
4. Sweep: repositories → use cases → clients → ViewModels, one commit each.
5. `CHANGELOG.md` (`### Internal`), AGENTS.md §5 error-handling bullet naming the helpers,
   ROADMAP if it tracks #81.

## Out of scope

DAOs (confirmed not extractable, #81 2026-09-01 comment); network client bodies; the restore swap
(stacked PR); the two large UI files (new issue); any enforcement check.
