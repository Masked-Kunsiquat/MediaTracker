# #81 item 6 — Cancellation-safe error envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 67 hand-written `catch (e: CancellationException) { throw e } catch (e: Exception)` envelopes in `shared/src/commonMain` with two inline helpers, fix the one site that swallows cancellation, and change no user-visible string.

**Architecture:** `core/util/Cancellation.kt` holds `catchingNonCancellation` (the rethrow primitive) and `Logger.guardResource` (the standard log-and-`Resource.Error` body built on it). Both are `inline`, so call sites keep suspending calls, `return@launch`, early `return`, and `continue`. Sites are swept layer by layer; five sites whose cancellation branch does work stay hand-written.

**Tech Stack:** Kotlin 2.2.21 Multiplatform, kotlinx-coroutines, kotlin.test, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-15-resource-envelope-design.md`

## Global Constraints

- Every user-visible message and every log line stays byte-identical. A site whose body does not fit `guardResource` exactly goes to `catchingNonCancellation` instead; never adjust a message to make it fit.
- Only `shared/src/commonMain` production code changes (plus `LocalImageStorageManager`'s test). No new dependency, package or module.
- The 5 hand-written sites are not touched: `BulkBackfillUseCase`, `BulkTmdbBackfillUseCase`, `DatabaseBackupUseCase`, `BackfillViewModel.start` (the `catch` around ~line 115), `LogFileStore`.
- `CancellationException` is `kotlin.coroutines.cancellation.CancellationException`.
- Comments: a comment that only restates the generic rethrow reason is deleted **and recorded** in `$CLAUDE_JOB_DIR/tmp/deleted-comments.md` (`| file:line | first ~8 words | reason |`). A comment naming a site-specific consequence stays above the call; its consequence sentence stays verbatim, only a clause describing catch *ordering* ("ahead of the Exception catch below") may be reworded, since that ordering no longer exists at the site. Every comment inside a `catch (e: Exception)` body moves into `onException` / stays above `guardResource` untouched.
- Tests: `commonTest` for anything not touching Room. Run tests with package-qualified filters (`--tests 'com.hub.media.core.util.CancellationTest'`), never a leading wildcard.
- Edit source with the Edit tool and exact anchors, never a heredoc/scripted replace.
- Before every push: `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.
- Commit messages end with the session's Co-Authored-By / Claude-Session lines.

## Review Focus

1. **A message drifts by one character** (missing `cause`, `message =` vs positional, `warn` vs `error`) during the `guardResource` sweep → users see different error text. Guard: each sweep task's step re-runs the classifier diff below and the existing message-asserting tests; `CancellationTest` pins the exact format.
2. **A `return` inside a `guardResource` block** that used to return from the function now must still do so (non-local) rather than become the lambda's value → wrong result type or skipped code. Guard: `catchingNonCancellation_allowsNonLocalReturnFromBlock`.
3. **`continue` / `return@launch` in `onException`** (ImportArchiveUseCase, EditMovie/EditTVShow ViewModels) stops compiling or changes control flow. Guard: `catchingNonCancellation_allowsNonLocalContinueFromHandler` and the existing ViewModel `LoadFailed` tests.
4. **Real coroutine cancellation** (a job cancelled while suspended inside the block), not just a thrown `CancellationException`, must still propagate. Guard: `catchingNonCancellation_propagatesRealJobCancellation`.
5. **A site-specific "why" comment deleted** as if generic. Guard: the deleted-comments log is reviewed line by line in the PR body; the whole-branch review checks it.

---

### Task 1: The helpers

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/util/Cancellation.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/core/util/CancellationTest.kt`

**Interfaces:**
- Consumes: `Logger`, `Logger.error(tag, throwable, message)`, `Resource` (all `com.hub.media.core.util`); `RecordingLogger` (commonTest, same package).
- Produces:
  - `public inline fun <T> catchingNonCancellation(block: () -> T, onException: (Exception) -> T): T`
  - `public inline fun <T> Logger.guardResource(tag: String, failure: String, logMessage: () -> String, block: () -> Resource<T>): Resource<T>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hub.media.core.util

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CancellationTest {
    private class FatalTestError : Error("fatal")

    // --- catchingNonCancellation ---

    @Test
    fun catchingNonCancellation_returnsBlockResult_whenNothingThrows() {
        var handlerCalls = 0
        val result = catchingNonCancellation(block = { 42 }, onException = { handlerCalls++; -1 })
        assertEquals(42, result)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_rethrowsCancellation_withoutCallingHandler() {
        val cancellation = CancellationException("screen closed")
        var handlerCalls = 0
        val thrown =
            assertFailsWith<CancellationException> {
                catchingNonCancellation(block = { throw cancellation }, onException = { handlerCalls++ })
            }
        assertSame(cancellation, thrown)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_passesOrdinaryException_toHandler() {
        val failure = IllegalStateException("disk full")
        val result = catchingNonCancellation<Any>(block = { throw failure }, onException = { it })
        assertSame(failure, result)
    }

    @Test
    fun catchingNonCancellation_letsErrorsPropagate() {
        var handlerCalls = 0
        assertFailsWith<FatalTestError> {
            catchingNonCancellation(block = { throw FatalTestError() }, onException = { handlerCalls++ })
        }
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_allowsNonLocalReturnFromBlock() {
        fun earlyExit(): String {
            catchingNonCancellation(block = { return "returned early" }, onException = { "handled" })
            return "fell through"
        }
        assertEquals("returned early", earlyExit())
    }

    @Test
    fun catchingNonCancellation_allowsNonLocalContinueFromHandler() {
        val visited = mutableListOf<Int>()
        for (i in 1..3) {
            catchingNonCancellation(
                block = { if (i == 2) error("skip $i") },
                onException = { continue },
            )
            visited += i
        }
        assertEquals(listOf(1, 3), visited)
    }

    @Test
    fun catchingNonCancellation_blockMaySuspend() =
        runTest {
            val result = catchingNonCancellation(block = { delay(1); "after delay" }, onException = { "handled" })
            assertEquals("after delay", result)
        }

    @Test
    fun catchingNonCancellation_propagatesRealJobCancellation() =
        runTest {
            var handled = false
            var reachedBlock = false
            val job =
                launch {
                    catchingNonCancellation(
                        block = {
                            reachedBlock = true
                            awaitCancellation()
                        },
                        onException = { handled = true },
                    )
                }
            runCurrent()
            assertTrue(reachedBlock, "positive control: the block must actually be suspended when cancelled")
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(handled)
        }

    // --- guardResource ---

    @Test
    fun guardResource_passesSuccessThrough_withoutLogging() {
        val logger = RecordingLogger()
        val result = logger.guardResource(tag = "Tag", failure = "Failed to save", logMessage = { "unused" }) {
            Resource.Success(7)
        }
        assertEquals(Resource.Success(7), result)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun guardResource_passesBlocksOwnError_throughWithoutLogging() {
        val logger = RecordingLogger()
        val result = logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "unused" }) {
            Resource.Error("Item with id=1 not found")
        }
        assertEquals(Resource.Error("Item with id=1 not found"), result)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun guardResource_wrapsException_inPrefixedMessage_withCause_andOneErrorLog() {
        val logger = RecordingLogger()
        val failure = IllegalStateException("disk full")
        val result =
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "save failed: id=1" }) {
                throw failure
            }
        val error = assertIs<Resource.Error>(result)
        assertEquals("Failed to save: disk full", error.message)
        assertSame(failure, error.cause)
        assertEquals(
            listOf(RecordingLogger.Entry(LogLevel.ERROR, "Tag", "save failed: id=1", failure)),
            logger.entries,
        )
    }

    @Test
    fun guardResource_usesUnknownError_whenExceptionHasNoMessage() {
        val logger = RecordingLogger()
        val result =
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "x" }) {
                throw IllegalStateException()
            }
        assertEquals("Failed to save: Unknown error", assertIs<Resource.Error>(result).message)
    }

    @Test
    fun guardResource_rethrowsCancellation_andLogsNothing() {
        val logger = RecordingLogger()
        assertFailsWith<CancellationException> {
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "x" }) {
                throw CancellationException("screen closed")
            }
        }
        assertTrue(logger.entries.isEmpty())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.core.util.CancellationTest'`
Expected: FAIL — compilation, `Unresolved reference 'catchingNonCancellation'` / `'guardResource'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.hub.media.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block], handing any [Exception] to [onException] -- except [CancellationException], which is
 * always rethrown untouched.
 *
 * ### Why the rethrow exists
 * On Kotlin/JVM `CancellationException` *is* an `Exception`, so a bare `catch (e: Exception)` around
 * suspending code also catches coroutine cancellation. Swallowing it breaks structured concurrency:
 * a coroutine whose screen was closed carries on as though nothing happened, and the cancellation is
 * reported -- and logged -- as a failure. Every catch around suspending code in `shared/` used to
 * spell this out by hand (72 sites when #81 item 6 replaced them); this function is that clause,
 * written once.
 *
 * ### What it does not change
 * Only [Exception] is caught: an [Error] (out-of-memory, a failed assertion) still propagates.
 * `TimeoutCancellationException` is a `CancellationException`, so it is rethrown too.
 *
 * Both lambdas are inlined, so [block] may suspend, and either may `return` from the enclosing
 * function, `return@launch`, or `continue` an enclosing loop.
 *
 * ### When not to use it
 * When the cancellation branch itself has work to do -- log a resume point, delete a partial file
 * under `NonCancellable`, settle UI state -- keep a hand-written `catch (e: CancellationException)`
 * that does that work and then rethrows (`BulkBackfillUseCase`, `DatabaseBackupUseCase`,
 * `BackfillViewModel.start`). Use [guardResource] for the standard repository failure.
 */
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

/**
 * The standard repository / use-case failure, on top of [catchingNonCancellation]: logs at ERROR
 * under [tag] with the exception attached and [logMessage] as the text, and returns
 * `Resource.Error("<failure>: <exception message, or "Unknown error">", cause = e)`.
 *
 * A [Resource] that [block] returns itself -- including its own `Resource.Error` for "not found" --
 * passes through unlogged. Cancellation is rethrown and logged nowhere.
 *
 * Only for sites whose failure is exactly that shape. A site that warns instead, omits the cause, or
 * must keep the exception's message out of user-facing text (a URL carrying a credential) uses
 * [catchingNonCancellation] with its own handler.
 *
 * @param failure The user-facing prefix, e.g. `"Failed to update season"`.
 * @param logMessage The log text; identifiers only, never library content (see [Logger]).
 */
public inline fun <T> Logger.guardResource(
    tag: String,
    failure: String,
    logMessage: () -> String,
    block: () -> Resource<T>,
): Resource<T> =
    catchingNonCancellation(block) { e ->
        this.error(tag, e, logMessage)
        Resource.Error("$failure: ${e.message ?: "Unknown error"}", cause = e)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.core.util.CancellationTest'`
Expected: PASS, 13 tests.

- [ ] **Step 5: Prove the cancellation test can fail**

Temporarily delete the `catch (e: CancellationException) { throw e }` clause from `catchingNonCancellation`; re-run Step 4's command. Expected: `rethrowsCancellation_withoutCallingHandler`, `propagatesRealJobCancellation` and `guardResource_rethrowsCancellation_andLogsNothing` FAIL. Restore the clause; re-run; PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/com/hub/media/core/util/Cancellation.kt shared/src/commonTest/kotlin/com/hub/media/core/util/CancellationTest.kt
git commit -m "Add catchingNonCancellation and guardResource (#81)"
```

---

### Task 2: `saveImage` stops swallowing cancellation

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/storage/LocalImageStorageManager.kt:34-44`
- Test: `shared/src/commonTest/kotlin/com/hub/media/core/storage/LocalImageStorageManagerTest.kt`

**Interfaces:**
- Consumes: `catchingNonCancellation` (Task 1).
- Produces: unchanged `saveImage(bytes: ByteArray): Result<String>` signature; cancellation now propagates.

- [ ] **Step 1: Write the failing test** (append to the class; add imports `kotlinx.coroutines.cancel`, `kotlinx.coroutines.launch`, `kotlin.test.assertNull`)

```kotlin
    @Test
    fun cancelledSave_propagatesCancellation_insteadOfReturningFailure() =
        runTest {
            var outcome: Result<String>? = null
            var reachedSave = false
            val job =
                launch {
                    cancel() // this coroutine is now cancelled; the write's withContext must notice
                    reachedSave = true
                    outcome = manager.saveImage("cancelled".encodeToByteArray())
                }
            job.join()
            assertTrue(reachedSave, "positive control: saveImage must actually have been called")
            assertTrue(job.isCancelled)
            assertNull(outcome, "a cancelled save must propagate cancellation, not return $outcome")
        }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.core.storage.LocalImageStorageManagerTest'`
Expected: FAIL on `assertNull` — message contains `Failure(kotlinx.coroutines.JobCancellationException`. **If it passes instead**, cancellation is being thrown before the `try` (e.g. inside `FileLockRegistry.withLock`) and the test does not exercise the swallow: stop and investigate rather than proceeding.

- [ ] **Step 3: Implement** — replace the `try`/`catch` inside `lockRegistry.withLock(hash) { ... }`:

```kotlin
        return lockRegistry.withLock(hash) {
            catchingNonCancellation(
                block = {
                    val filename = "$hash.jpg"
                    // Returns false when the file already existed — fine for deduplication
                    writeImageIfNotExists(baseDirectoryPath, filename, bytes)
                    Result.success(filename)
                },
                onException = { Result.failure(it) },
            )
        }
```

Add `import com.hub.media.core.util.catchingNonCancellation`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.core.storage.LocalImageStorageManagerTest'`
Expected: PASS (all existing tests plus the new one).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/com/hub/media/core/storage/LocalImageStorageManager.kt shared/src/commonTest/kotlin/com/hub/media/core/storage/LocalImageStorageManagerTest.kt
git commit -m "saveImage: propagate cancellation instead of returning it as a failure (#81)"
```

---

## The sweep (Tasks 3–6): shared rules

Each sweep task converts the listed sites and nothing else. For every site:

1. Read the whole function, not just the catch.
2. Confirm the group: for `guardResource`, the catch body must be exactly `logger.error(TAG, e) { <log> }` + `Resource.Error(<"failure: ${e.message ?: "Unknown error"}">, cause = e)` (positional or named `message =`/`cause =`), and the CE branch must be `throw e` plus at most a generic comment. Anything else → `catchingNonCancellation`.
3. Convert using the matching template below.
4. Record each deleted generic comment in `$CLAUDE_JOB_DIR/tmp/deleted-comments.md`.
5. Remove the `CancellationException` import if the file no longer uses it; add `import com.hub.media.core.util.guardResource` / `catchingNonCancellation`.

**Template G — `guardResource`, expression body.** Before:

```kotlin
    public suspend fun clearSeason(mediaId: String, seasonNumber: Int): Resource<List<WatchLogEntity>> =
        try {
            Resource.Success(db.tvWriteDao().clearSeasonReturningPrevious(mediaId, seasonNumber))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to clear season $seasonNumber for show: id=$mediaId" }
            Resource.Error("Failed to update season: ${e.message ?: "Unknown error"}", cause = e)
        }
```

After:

```kotlin
    public suspend fun clearSeason(mediaId: String, seasonNumber: Int): Resource<List<WatchLogEntity>> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to update season",
            logMessage = { "Failed to clear season $seasonNumber for show: id=$mediaId" },
        ) {
            Resource.Success(db.tvWriteDao().clearSeasonReturningPrevious(mediaId, seasonNumber))
        }
```

**Template G′ — block body (`return try { ... }` or `{ ...; try {...} }` as the last expression).** Replace `try {` with `logger.guardResource(tag = TAG, failure = "...", logMessage = { "..." }) {` and delete both catch clauses. Statements before the `try` stay where they are. A `return Resource.Error(...)` inside the old `try` body stays as written — it is a non-local return from the function, exactly as before.

**Template N — `catchingNonCancellation`.** Before:

```kotlin
            val movie =
                try {
                    movieRepository.observeMovieDetail(movieId).first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Uncaught, this would escape viewModelScope and crash the app.
                    _uiState.value = EditMovieUiState.LoadFailed
                    return@launch
                }
```

After:

```kotlin
            val movie =
                catchingNonCancellation(
                    block = { movieRepository.observeMovieDetail(movieId).first() },
                    onException = {
                        // Uncaught, this would escape viewModelScope and crash the app.
                        _uiState.value = EditMovieUiState.LoadFailed
                        return@launch
                    },
                )
```

Use `onException = { e -> ... }` when the body reads `e`; otherwise leave the parameter implicit. A `try ... finally` wrapping the catches (MismatchReviewViewModel:127) becomes `try { catchingNonCancellation(...) } finally { ... }`.

**Site-specific CE comments** move to just above the `catchingNonCancellation(` call, e.g. OpenLibraryClient ~219:

```kotlin
        // Cancellation is rethrown, never swallowed: this method already returns null for an
        // unresolved author, so a swallowed cancellation would look identical to "no such author"
        // instead of propagating like every other cancellation in this file.
```

**Count check after each task** (from the repo root):

```bash
grep -rn "catch (e: CancellationException)" shared/src/commonMain | wc -l
```

Expected after Task 1: 73 · Task 3: 46 · Task 4: 33 · Task 5: 17 · Task 6: **6** (the helper + the 5 hand-written sites).

---

### Task 3: Repositories (27 sites → `guardResource`)

**Files (Modify):**
- `shared/src/commonMain/kotlin/com/hub/media/core/database/MediaRepository.kt` — :115 `"Failed to delete item"`, :144 `"Failed to update cover image"`
- `.../features/books/data/BookRepository.kt` — :195 `"Failed to add book"`, :221 `"Failed to fetch book"`, :354 `"Failed to update book metadata"`, :414 `"Failed to apply backfilled metadata"`, :465 `"Failed to update reading status"` (4 carry the generic CE comment → delete + log)
- `.../features/books/data/ReadingSessionRepository.kt` — :98 `"Failed to log reading session"`, :180 `"Failed to update reading session"`, :203 `"Failed to delete reading session"` (generic CE comments → delete + log)
- `.../features/movies/data/MovieRepository.kt` — :169 `"Failed to add movie"`, :210 `"Failed to update movie metadata"`, :245 `"Failed to update movie status"`, :332 private `guarded()` (`"Failed to $what"`)
- `.../features/tv/data/TVShowRepository.kt` — :332 `"Failed to add TV show"`, :471/:666/:701 `"Failed to update season"`, :527 `"Failed to add the missing episodes"`, :567 `"Failed to remove season"`, :613/:687 `"Failed to update episode"`, :717 `"Failed to undo"`, :760 private `guarded()` (`"Failed to update viewing"`), :801 `"Failed to update show metadata"`, :834 `"Failed to update show status"`
- `.../features/portability/data/ImportWriteRepository.kt` — :67 `"Import failed -- nothing was written"` (generic CE comment → delete + log)

**Interfaces:** Consumes `Logger.guardResource` (Task 1). Produces no new API; `MovieRepository.write` and `TVShowRepository.guarded` stay as one-line delegations so their callers are untouched.

- [ ] **Step 1: Baseline** — run `./gradlew :shared:jvmTest` and confirm green before editing (these repositories' tests live in `jvmTest`; they assert the messages this task must preserve).
- [ ] **Step 2: Convert `MediaRepository`, `BookRepository`, `ReadingSessionRepository`, `ImportWriteRepository`** with Templates G/G′.
- [ ] **Step 3: Convert `MovieRepository`.** Its three public sites use Template G/G′. Its private `write(what, block: () -> Resource<T>)` helper (~line 326) keeps its name, signature and callers; only its body changes:

```kotlin
    private inline fun <T> write(
        what: String,
        block: () -> Resource<T>,
    ): Resource<T> = logger.guardResource(tag = TAG, failure = "Failed to $what", logMessage = { "Failed to $what" }, block = block)
```

- [ ] **Step 4: Convert `TVShowRepository`.** Its eleven public sites use Template G/G′. Its private `guarded(what, block: suspend () -> T)` (~line 754, 3 callers) keeps its name, signature and callers; only its body changes, preserving its two different strings:

```kotlin
    private suspend fun <T> guarded(
        what: String,
        block: suspend () -> T,
    ): Resource<T> =
        logger.guardResource(tag = TAG, failure = "Failed to update viewing", logMessage = { "Failed to $what" }) {
            Resource.Success(block())
        }
```
- [ ] **Step 5: Verify** — `./gradlew ktlintFormat && ./gradlew :shared:jvmTest`; PASS. Count check: 46.
- [ ] **Step 6: Commit** — `git commit -m "Repositories: catch through guardResource (#81)"` (all six files).

---

### Task 4: Use cases (10 → `guardResource`, 3 → `catchingNonCancellation`)

**Files (Modify):**
- `guardResource`: `.../features/media/domain/DeleteMediaUseCase.kt:69` `"Failed to delete items"` · `ReconcileMismatchesUseCase.kt:170` `"Failed to add the missing episodes"` · `.../portability/domain/ExportArchiveUseCase.kt:76` `"Export failed"` · `ExportDataUseCase.kt:137` `"Failed to export data"` (generic CE comment) · `ImportDataUseCase.kt:732` `"Import failed"` and `:834` `"Goodreads import failed"` (generic CE comments) · `.../tv/domain/BackfillShowEpisodesUseCase.kt:144` `"Failed to backfill episodes"` · `EnsureViewingsUseCase.kt:30` `"Viewing backfill failed"` · `MarkEpisodesWatchedUseCase.kt:65` `"Failed to mark watched"` · `RecordRewatchUseCase.kt:37` `"Failed to record the viewing"`
- `catchingNonCancellation`: `.../tv/domain/FetchPosterUseCase.kt:102` (keeps the "URL is never interpolated" comment) · `.../portability/domain/ImportArchiveUseCase.kt:110` (`continue` in `onException`; keeps the "Each setting on its own" comment above) · `.../portability/domain/SweepStaleTempFilesUseCase.kt:46`

**Interfaces:** Consumes Task 1's two functions.

- [ ] **Step 1: Convert the 10 `guardResource` sites** (Templates G/G′).
- [ ] **Step 2: Convert the 3 `catchingNonCancellation` sites** (Template N). `ImportArchiveUseCase`:

```kotlin
                val applied =
                    catchingNonCancellation(
                        block = { restoreSetting(key, value) },
                        onException = { e ->
                            logger.warn(TAG, e) { "Restoring setting $key failed" }
                            notes += "Setting \"$key\" could not be saved and was not restored"
                            continue
                        },
                    )
```
- [ ] **Step 3: Verify** — `./gradlew ktlintFormat && ./gradlew :shared:jvmTest`; PASS. Count check: 33.
- [ ] **Step 4: Commit** — `git commit -m "Use cases: catch through guardResource / catchingNonCancellation (#81)"`.

---

### Task 5: Network clients (16 → `catchingNonCancellation`)

**Files (Modify):** `.../features/books/network/OpenLibraryClient.kt` :87 (inner; keep "a cancelled deserialization is not a malformed-JSON failure"), :120 (generic → delete + log; keep the "WARN, not ERROR" body comment), :184, :219 (keep, reworded per the shared rules) · `OpenLibrarySearchClient.kt` :133, :145, :183, :199, :239 (keep "Exception type only" body comment; its `return Resource.Error(...)` stays a non-local return), :251 (keep "every keystroke that supersedes an in-flight request") · `GoogleBooksClient.kt` :103 (inner; keep), :139 (generic → delete + log) · `.../features/tv/network/TmdbClient.kt` :139, :192 (keep the "api_key credential" body comment) · `CoverImageDownloader.kt:46` (generic "spurious WARN… mid-download" → delete + log) · `OpenLibraryIsbnCoverProbe.kt:189` (keep "carry on as though the probe had simply found no cover").

Nested sites (an inner catch around JSON decoding inside an outer catch) become a `catchingNonCancellation` inside the outer one's `block`. Convert inner first, then outer, so each edit's anchor is still unique.

- [ ] **Step 1: Convert `OpenLibraryClient` and `GoogleBooksClient`.**
- [ ] **Step 2: Convert `OpenLibrarySearchClient`.**
- [ ] **Step 3: Convert `TmdbClient`, `CoverImageDownloader`, `OpenLibraryIsbnCoverProbe`.**
- [ ] **Step 4: Verify** — `./gradlew ktlintFormat && ./gradlew :shared:jvmTest`; PASS (the client tests assert that URLs and credentials stay out of messages). Count check: 17.
- [ ] **Step 5: Commit** — `git commit -m "Network clients: catch through catchingNonCancellation (#81)"`.

---

### Task 6: ViewModels (11 → `catchingNonCancellation`)

**Files (Modify):** `.../ui/SettingsViewModel.kt` :140, :160, :183, :204, :227 · `RestoreViewModel.kt:77` · `EditMovieViewModel.kt:100` and `EditTVShowViewModel.kt:81` (`return@launch` — Template N verbatim) · `MismatchReviewViewModel.kt:95`, `:127` (`finally` stays outside) · `BackfillViewModel.kt:70` only (the `init` progress read; its CE comment "Rethrown for the same reason start() rethrows it" → delete + log). **Do not touch `BackfillViewModel.kt:115`.**

- [ ] **Step 1: Convert all 11.**
- [ ] **Step 2: Verify** — `./gradlew ktlintFormat && ./gradlew :shared:jvmTest`; PASS. Count check: **6**; list them with `grep -rln "catch (e: CancellationException)" shared/src/commonMain` and confirm exactly six files, one clause each: `Cancellation.kt`, `BulkBackfillUseCase.kt`, `BulkTmdbBackfillUseCase.kt`, `DatabaseBackupUseCase.kt`, `BackfillViewModel.kt`, `LogFileStore.kt`.
- [ ] **Step 3: Commit** — `git commit -m "ViewModels: catch through catchingNonCancellation (#81)"`.

---

### Task 7: Docs, full gate, reviews, PR

**Files (Modify):** `CHANGELOG.md` (`[Unreleased]` → `### Internal`, last section), `AGENTS.md` §5 "Error Handling" bullet, `ROADMAP.md:82` (the "Next: #81's remaining" line).

- [ ] **Step 1: CHANGELOG** — under `## [Unreleased]` → `### Internal`:

```markdown
- Every catch around suspending code in `shared/` now goes through two helpers in `core/util/Cancellation.kt`: `catchingNonCancellation`, which always rethrows coroutine cancellation, and `guardResource`, the standard log-and-`Resource.Error` failure built on it. 67 hand-written copies of the rethrow are gone; five sites whose cancellation branch does real work keep theirs. Closes the last item of #81.
- `LocalImageStorageManager.saveImage` rethrows coroutine cancellation instead of returning it as `Result.failure`; it was the one suspending catch in `shared/` without the rethrow.
```

  (Internal, not Fixed: a cancelled save's caller is itself being cancelled, so no user-visible difference has been shown. Promote it only if the review finds one.)

- [ ] **Step 2: AGENTS.md §5** — append to the Error Handling bullet: `Catch around suspending code with core/util/Cancellation.kt's catchingNonCancellation (or guardResource for the standard repository failure), never a bare catch (e: Exception): on JVM that also catches coroutine cancellation. A hand-written rethrow is only for a site whose cancellation branch must do work first.`
- [ ] **Step 3: ROADMAP.md:82** — read the sentence; replace "#81's remaining …" with a statement that #81 is complete and name the follow-ups (restore-swap fix, the two large UI files) by issue number once the owner has filed them, or by description until then.
- [ ] **Step 4: Commit** — `git commit -m "Docs: record the cancellation helpers (#81)"`.
- [ ] **Step 5: Full gate** — `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`. All green. `app/src` is untouched, so `connectedDebugAndroidTest` is not required; the PR says so in words.
- [ ] **Step 6: Whole-branch review** — a fresh Opus reviewer over `git diff main...HEAD` plus `deleted-comments.md`, briefed to (a) check every message is byte-identical, (b) check every deleted comment was generic, (c) re-grade findings by user effect. Fix confirmed findings in new commits.
- [ ] **Step 7: CodeRabbit** — `coderabbit review --agent --committed --base main -c AGENTS.md`; address or rebut each finding.
- [ ] **Step 8: Push and open the PR** (after the owner's go-ahead to push): body with summary, the site table (37 / 30 / 5), the deleted-comments table, test evidence, "connectedDebugAndroidTest not run: no `app/src` change", `Closes #81`.
- [ ] **Step 9: Owner-facing drafts** — issue text for (a) the restore-swap fix (stacked PR on this branch) and (b) splitting `TVShowDetailScreen.kt` / `BookSessionDialogs.kt`. Show both; file nothing without approval.
- [ ] **Step 10: Graphban** — MT-11 to `review` with evidence (PR link, gate output); memory-outcomes table written to `C:\Users\blain\graphban-outcomes\81-resource-envelope.md`.
