# Film Watch History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Films get the "when?" prompt on becoming Watched, a viewing history with Watched again, edit and delete, and CSV round-tripping. This matches what episodes got in #131 and #90.

**Architecture:** Film viewings are `watch_logs` rows with `episodeId = null`. Every film write is a `MovieWriteDao` `@Transaction` that ends in one derivation, `recomputeFilmWatchedAt`: a Watched film's `watchedAt` is its latest viewing, and any other status has none. `MovieRepository` turns a `WatchedWhen` into an instant and returns what Undo needs. The screens hold the prompt; the ViewModels hold pending, Undo and error state.

**Tech Stack:** Kotlin Multiplatform, Room KMP (bundled SQLite), Compose Material 3, kotlinx-datetime, kotlin.test, Robolectric and Roborazzi.

**Spec:** `docs/superpowers/specs/2026-10-13-film-watch-history-design.md`

## Global Constraints

- No schema change. `APP_DATABASE_VERSION` stays 7, CSV schema stays v6, and no new dependencies.
- Invariant: `status == WATCHED ⇒ watchedAt == MAX(film viewings)`; otherwise `watchedAt == null`. Viewings survive status changes.
- The prompt offers **Now / Pick a date** only (films have no release date). A picked day is stored as noon local, clamped to now, through `resolveWatchedAt(when, airDate = null, now, zone)`.
- Room tests go in `shared/src/jvmTest`. ViewModel tests await with `uiState.first { }` and never read `.value` after an action (AGENTS.md §7).
- Gate before every push: `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug`, chained with `&&`, never `;`.
- CHANGELOG `[Unreleased]` is updated in the branch (CI enforces it).

## Review Focus

1. A film deleted while its Undo snackbar is showing: restore must be a no-op, not an FK crash. Pinned in Task 1 (`restore_afterFilmDeleted_isNoOp`).
2. Re-saving the Edit form of a Watched film must not add a viewing or move the date. Pinned in Task 2 (`updateMetadata_reSaveWatched_addsNoViewing`).
3. A film viewing row in CSV whose `media_id` is a show, or unknown, must be rejected rather than attached. Pinned in Task 5 (`filmViewing_pointingAtAShow_isRejected`).
4. Episode viewings and film viewings must not collide in import dedup: films share the null `episodeId` key. Pinned in Task 5 (`twoFilmsWithSameDate_bothImported`).
5. Watched → Watching → Watched must add exactly one new viewing, and Undo of leaving Watched must add none. Pinned in Task 1 (`rewatchCycle_addsOneViewing`) and Task 3 (`undoLeavingWatched_restoresWithoutNewViewing`).

---

### Task 1: Film viewing writes in `MovieWriteDao`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/MovieWriteDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/WatchLogDao.kt`
- Test (create): `shared/src/jvmTest/kotlin/com/hub/media/core/database/dao/FilmWatchHistoryDaoTest.kt`

**Interfaces:**
- Produces, in `MovieWriteDao`, all `@Transaction`:
  - `changeStatus(mediaId: String, status: WatchStatus, at: Instant): WatchStatus?` returns the previous status, or null if no such film. It inserts a viewing at `at` only on a move into WATCHED.
  - `addFilmViewing(mediaId: String, at: Instant): Boolean` returns false unless the film is WATCHED.
  - `deleteFilmViewing(logId: String): FilmViewingDeletion?`
  - `updateFilmViewingDate(logId: String, at: Instant): Boolean`
  - `restoreFilmViewings(logs: List<WatchLogEntity>, statusBefore: WatchStatus): Boolean`
  - `restoreFilmStatus(mediaId: String, status: WatchStatus): Boolean`
  - `ensureViewingsForWatchedFilms(): Int`
  - `updateMovieMetadataWithViewing(mediaId, title, releaseYear, purchasePrice, runtimeMinutes, status, at: Instant): Int`
  - `insertMovieAtomically(...)`, changed: also inserts a viewing when `details.status == WATCHED && details.watchedAt != null`.
- Produces `public data class FilmViewingDeletion(val log: WatchLogEntity, val statusBefore: WatchStatus)` in `MovieWriteDao.kt`.
- Produces `WatchLogDao.observeForFilm(mediaId: String): Flow<List<WatchLogEntity>>`, newest first.

- [ ] **Step 1: Write the failing tests.** Create `FilmWatchHistoryDaoTest` with these tests:

```kotlin
class FilmWatchHistoryDaoTest {
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2025-01-10T12:00:00Z")
    private lateinit var db: AppDatabase
    private val dao get() = db.movieWriteDao()

    @BeforeTest fun setUp() { db = testAppDatabase() }
    @AfterTest fun tearDown() { db.close() }

    private suspend fun film(status: WatchStatus = WatchStatus.WATCHLIST): String =
        (MovieRepository(db).addMovie(title = "Heat", status = WatchStatus.WATCHLIST) as Resource.Success).data
            .also { if (status != WatchStatus.WATCHLIST) dao.changeStatus(it, status, t1) }
    private suspend fun viewings(id: String) = db.watchLogDao().observeForFilm(id).first()
    private suspend fun details(id: String) = db.movieDetailsDao().getByMediaId(id)!!
    private suspend fun assertInvariant(id: String) {
        val d = details(id)
        val expected = if (d.status == WatchStatus.WATCHED) viewings(id).maxOfOrNull { it.watchedAt } else null
        assertEquals(expected, d.watchedAt)
    }

    @Test fun becomingWatched_addsOneViewingAtThatInstant() = runTest {
        val id = film()
        assertEquals(WatchStatus.WATCHLIST, dao.changeStatus(id, WatchStatus.WATCHED, t1))
        assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
        assertNull(viewings(id).single().episodeId)
        assertInvariant(id)
    }

    @Test fun leavingWatched_keepsViewings_andClearsWatchedAt() = runTest {
        val id = film(WatchStatus.WATCHED)
        dao.changeStatus(id, WatchStatus.WATCHING, t2)
        assertEquals(1, viewings(id).size)
        assertNull(details(id).watchedAt)
    }

    @Test fun rewatchCycle_addsOneViewing() = runTest {
        val id = film(WatchStatus.WATCHED)
        dao.changeStatus(id, WatchStatus.WATCHING, t2)
        dao.changeStatus(id, WatchStatus.WATCHED, t2)
        assertEquals(listOf(t2, t1), viewings(id).map { it.watchedAt })
        assertEquals(t2, details(id).watchedAt)
    }

    @Test fun watchedToWatched_addsNothing() = runTest {
        val id = film(WatchStatus.WATCHED)
        dao.changeStatus(id, WatchStatus.WATCHED, t2)
        assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
    }

    @Test fun addFilmViewing_onlyWhileWatched() = runTest {
        val id = film()
        assertFalse(dao.addFilmViewing(id, t2))
        dao.changeStatus(id, WatchStatus.WATCHED, t1)
        assertTrue(dao.addFilmViewing(id, t2))
        assertEquals(t2, details(id).watchedAt)
    }

    @Test fun deletingLastViewing_movesToWatchlist() = runTest {
        val id = film(WatchStatus.WATCHED)
        val deletion = assertNotNull(dao.deleteFilmViewing(viewings(id).single().id))
        assertEquals(WatchStatus.WATCHED, deletion.statusBefore)
        assertEquals(WatchStatus.WATCHLIST, details(id).status)
        assertInvariant(id)
        assertTrue(dao.restoreFilmViewings(listOf(deletion.log), deletion.statusBefore))
        assertEquals(WatchStatus.WATCHED, details(id).status)
        assertEquals(t1, details(id).watchedAt)
    }

    @Test fun deletingOneOfTwo_keepsWatched_andRecomputes() = runTest {
        val id = film(WatchStatus.WATCHED)
        dao.addFilmViewing(id, t2)
        dao.deleteFilmViewing(viewings(id).first { it.watchedAt == t2 }.id)
        assertEquals(WatchStatus.WATCHED, details(id).status)
        assertEquals(t1, details(id).watchedAt)
    }

    @Test fun updateFilmViewingDate_recomputes() = runTest {
        val id = film(WatchStatus.WATCHED)
        assertTrue(dao.updateFilmViewingDate(viewings(id).single().id, t2))
        assertEquals(t2, details(id).watchedAt)
    }

    @Test fun restore_afterFilmDeleted_isNoOp() = runTest {
        val id = film(WatchStatus.WATCHED)
        val deletion = assertNotNull(dao.deleteFilmViewing(viewings(id).single().id))
        BulkDeleteUseCase(db).execute(listOf(id))
        assertFalse(dao.restoreFilmViewings(listOf(deletion.log), deletion.statusBefore))
    }

    @Test fun restoreFilmStatus_recomputes() = runTest {
        val id = film(WatchStatus.WATCHED)
        dao.changeStatus(id, WatchStatus.WATCHING, t2)
        assertTrue(dao.restoreFilmStatus(id, WatchStatus.WATCHED))
        assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
        assertEquals(t1, details(id).watchedAt)
    }

    @Test fun ensureViewings_backfillsLegacyWatchedFilm_once() = runTest {
        val id = film()
        dao.updateWatchStatusFields(id, WatchStatus.WATCHED, t1) // the legacy shape: no viewing
        assertEquals(1, dao.ensureViewingsForWatchedFilms())
        assertEquals(0, dao.ensureViewingsForWatchedFilms())
        assertEquals(listOf(t1), viewings(id).map { it.watchedAt })
    }

    @Test fun episodeViewingsAreNotFilmViewings() = runTest {
        // Positive control for observeForFilm's `episodeId IS NULL` filter: a show's episode viewing
        // shares mediaId-space but must not appear.
        val showId = (TVShowRepository(db).addShow(title = "S", seasons = listOf(SeasonQuickFill(1, 1))) as Resource.Success).data
        val ep = db.episodeDao().getByMediaIdAndSeason(showId, 1).single()
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(ep.id to t1))
        assertEquals(emptyList(), viewings(showId))
        assertEquals(1, db.watchLogDao().observeForEpisode(ep.id).first().size)
    }
}
```

(Construct `BulkDeleteUseCase` the way `TVShowDetailViewModelTest` does; copy its constructor call.)

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests '*FilmWatchHistoryDaoTest*'`. Expected: a compile FAIL (`changeStatus` and `observeForFilm` do not exist).

- [ ] **Step 3: Implement.** Add to `WatchLogDao`:

```kotlin
@Query("SELECT * FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL ORDER BY watchedAt DESC")
fun observeForFilm(mediaId: String): Flow<List<WatchLogEntity>>
```

Add to `MovieWriteDao`. Imports: `WatchLogEntity`, `newId`.

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertViewing(log: WatchLogEntity)
@Query("SELECT * FROM watch_logs WHERE id = :logId") suspend fun viewingById(logId: String): WatchLogEntity?
@Query("DELETE FROM watch_logs WHERE id = :logId") suspend fun deleteViewingRow(logId: String): Int
@Query("UPDATE watch_logs SET watchedAt = :at WHERE id = :logId") suspend fun setViewingDate(logId: String, at: Instant): Int
@Query("SELECT COUNT(*) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL")
suspend fun filmViewingCount(mediaId: String): Int
@Query("SELECT status FROM movie_details WHERE mediaId = :mediaId") suspend fun statusOf(mediaId: String): WatchStatus?
@Query("UPDATE movie_details SET status = :status WHERE mediaId = :mediaId")
suspend fun setStatus(mediaId: String, status: WatchStatus): Int

/** The one derivation (#192): a Watched film's `watchedAt` is its latest viewing; otherwise none. */
@Query(
    "UPDATE movie_details SET watchedAt = CASE WHEN status = 'WATCHED' THEN " +
        "(SELECT MAX(watchedAt) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL) " +
        "ELSE NULL END WHERE mediaId = :mediaId",
)
suspend fun recomputeFilmWatchedAt(mediaId: String): Int

private fun filmViewing(mediaId: String, at: Instant) =
    WatchLogEntity(id = newId(), mediaId = mediaId, episodeId = null, watchedAt = at)
```

(Room DAO interfaces cannot hold `private fun`. Make `filmViewing` a top-level `private fun` in the file instead.)

```kotlin
@Transaction
suspend fun changeStatus(mediaId: String, status: WatchStatus, at: Instant): WatchStatus? {
    val previous = statusOf(mediaId) ?: return null
    setStatus(mediaId, status)
    if (status == WatchStatus.WATCHED && previous != WatchStatus.WATCHED) insertViewing(filmViewing(mediaId, at))
    recomputeFilmWatchedAt(mediaId)
    return previous
}

@Transaction
suspend fun addFilmViewing(mediaId: String, at: Instant): Boolean {
    if (statusOf(mediaId) != WatchStatus.WATCHED) return false
    insertViewing(filmViewing(mediaId, at))
    recomputeFilmWatchedAt(mediaId)
    return true
}

@Transaction
suspend fun deleteFilmViewing(logId: String): FilmViewingDeletion? {
    val log = viewingById(logId)?.takeIf { it.episodeId == null } ?: return null
    val before = statusOf(log.mediaId) ?: return null
    deleteViewingRow(logId)
    if (before == WatchStatus.WATCHED && filmViewingCount(log.mediaId) == 0) setStatus(log.mediaId, WatchStatus.WATCHLIST)
    recomputeFilmWatchedAt(log.mediaId)
    return FilmViewingDeletion(log, before)
}

@Transaction
suspend fun updateFilmViewingDate(logId: String, at: Instant): Boolean {
    val log = viewingById(logId)?.takeIf { it.episodeId == null } ?: return false
    setViewingDate(logId, at)
    recomputeFilmWatchedAt(log.mediaId)
    return true
}

/**
 * Undo for [deleteFilmViewing]. A no-op when the film is gone (re-inserting would violate the
 * foreign key). Puts Watched back only if the delete is what moved it to Watchlist, so a status the
 * user changed since wins.
 */
@Transaction
suspend fun restoreFilmViewings(logs: List<WatchLogEntity>, statusBefore: WatchStatus): Boolean {
    val mediaId = logs.firstOrNull()?.mediaId ?: return false
    val current = statusOf(mediaId) ?: return false
    logs.filter { viewingById(it.id) == null }.forEach { insertViewing(it) }
    if (statusBefore == WatchStatus.WATCHED && current == WatchStatus.WATCHLIST) setStatus(mediaId, WatchStatus.WATCHED)
    recomputeFilmWatchedAt(mediaId)
    return true
}

/** Undo for leaving Watched: the status comes back and adds no viewing. */
@Transaction
suspend fun restoreFilmStatus(mediaId: String, status: WatchStatus): Boolean {
    if (setStatus(mediaId, status) == 0) return false
    recomputeFilmWatchedAt(mediaId)
    return true
}

@Query(
    "SELECT * FROM movie_details WHERE status = 'WATCHED' AND watchedAt IS NOT NULL AND NOT EXISTS " +
        "(SELECT 1 FROM watch_logs w WHERE w.mediaId = movie_details.mediaId AND w.episodeId IS NULL)",
)
suspend fun watchedFilmsWithoutViewings(): List<MovieDetailsEntity>

/** The film half of the startup backfill (#90/#192): one viewing at `watchedAt`. Idempotent. */
@Transaction
suspend fun ensureViewingsForWatchedFilms(): Int =
    watchedFilmsWithoutViewings().onEach { insertViewing(filmViewing(it.mediaId, checkNotNull(it.watchedAt))) }.size

@Transaction
suspend fun updateMovieMetadataWithViewing(
    mediaId: String, title: String, releaseYear: Int?, purchasePrice: Double?, runtimeMinutes: Int?,
    status: WatchStatus, at: Instant,
): Int {
    val before = statusOf(mediaId)
    val rows = updateMovieMetadataAtomically(mediaId, title, releaseYear, purchasePrice, runtimeMinutes, status, watchedAt = null)
    if (rows == 0) return 0
    if (status == WatchStatus.WATCHED && before != WatchStatus.WATCHED) insertViewing(filmViewing(mediaId, at))
    recomputeFilmWatchedAt(mediaId)
    return rows
}
```

Change `insertMovieAtomically` so that after the identifiers loop it does:

```kotlin
if (details.status == WatchStatus.WATCHED && details.watchedAt != null) {
    insertViewing(filmViewing(item.id, details.watchedAt))
}
```

Add `public data class FilmViewingDeletion(val log: WatchLogEntity, val statusBefore: WatchStatus)` below the interface. Write KDoc on each new function in the house style: one or two sentences, saying why rather than what.

- [ ] **Step 4: Run them to see them pass.** Run the same command. Expected: PASS, 12 tests. Also run `./gradlew :shared:jvmTest --tests '*MovieRepositoryTest*'`. Expected: PASS. `insertMovieAtomically` now adds a viewing, but nothing there asserts on viewings.

- [ ] **Step 5: Commit.** `git commit -m "Film viewings in MovieWriteDao: status transitions, Watched again, delete/restore, backfill (#192)"`

---

### Task 2: `MovieRepository` and the backfill use case

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/movies/data/MovieRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/tv/domain/EnsureViewingsUseCase.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/MovieDetailViewModel.kt`. Only the `updateWatchStatus` call site changes here (its return type changes).
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/movies/data/MovieRepositoryTest.kt`, `.../features/tv/domain/EnsureViewingsUseCaseTest.kt`

**Interfaces:**
- Consumes Task 1's DAO functions.
- Produces, in `MovieRepository`:
  - `MovieRepository(db, clock = Clock.System, logger = AppLogger, zone: TimeZone = TimeZone.currentSystemDefault())`
  - `addMovie(..., status, ..., watchedWhen: WatchedWhen? = null)`. The new parameter goes **last**.
  - `updateMovieMetadata(..., status, watchedWhen: WatchedWhen? = null): Resource<Unit>`
  - `updateWatchStatus(mediaId, status, watchedWhen: WatchedWhen? = null): Resource<WatchStatus>`. It returns the **previous** status.
  - `observeViewings(mediaId): Flow<List<WatchLogEntity>>`
  - `addViewing(mediaId, watchedWhen): Resource<Unit>`
  - `deleteViewing(logId): Resource<FilmViewingDeletion>`
  - `restoreViewings(logs, statusBefore): Resource<Unit>`
  - `restoreStatus(mediaId, status): Resource<Unit>`
  - `editViewingDate(logId, watchedWhen): Resource<Unit>`
- `resolveWatchedAt` (the companion function) is deleted. Its callers now go through the DAO transitions.

- [ ] **Step 1: Write the failing tests.** Add to `MovieRepositoryTest`. Use a fixed clock at `NOW = Instant.parse("2026-06-01T18:00:00Z")` and `zone = TimeZone.UTC`.

```kotlin
@Test fun addMovie_watched_pickedDay_storesNoonViewing() = runTest {
    val r = MovieRepository(db, fixed(NOW), zone = TimeZone.UTC)
    val id = (r.addMovie(title = "Heat", status = WatchStatus.WATCHED, watchedWhen = WatchedWhen.On(LocalDate(2024, 3, 3))) as Resource.Success).data
    val noon = Instant.parse("2024-03-03T12:00:00Z")
    assertEquals(listOf(noon), db.watchLogDao().observeForFilm(id).first().map { it.watchedAt })
    assertEquals(noon, db.movieDetailsDao().getByMediaId(id)!!.watchedAt)
}

@Test fun addMovie_watched_noWhen_meansNow() = runTest {
    val r = MovieRepository(db, fixed(NOW), zone = TimeZone.UTC)
    val id = (r.addMovie(title = "Heat", status = WatchStatus.WATCHED) as Resource.Success).data
    assertEquals(NOW, db.movieDetailsDao().getByMediaId(id)!!.watchedAt)
    assertEquals(1, db.watchLogDao().observeForFilm(id).first().size)
}

@Test fun updateMetadata_reSaveWatched_addsNoViewing() = runTest {
    val r = MovieRepository(db, fixed(NOW), zone = TimeZone.UTC)
    val id = (r.addMovie(title = "Heat", status = WatchStatus.WATCHED) as Resource.Success).data
    r.updateMovieMetadata(id, title = "Heat (1995)", status = WatchStatus.WATCHED, watchedWhen = WatchedWhen.On(LocalDate(2020, 1, 1)))
    assertEquals(listOf(NOW), db.watchLogDao().observeForFilm(id).first().map { it.watchedAt })
}

@Test fun updateWatchStatus_returnsPreviousStatus() = runTest {
    val id = (repo.addMovie(title = "Heat") as Resource.Success).data
    assertEquals(WatchStatus.WATCHLIST, (repo.updateWatchStatus(id, WatchStatus.WATCHED) as Resource.Success).data)
}

@Test fun editViewingDate_storesNoonOfThePickedDay() = runTest {
    val r = MovieRepository(db, fixed(NOW), zone = TimeZone.UTC)
    val id = (r.addMovie(title = "Heat", status = WatchStatus.WATCHED, watchedWhen = WatchedWhen.On(LocalDate(2024, 3, 3))) as Resource.Success).data
    val log = db.watchLogDao().observeForFilm(id).first().single()
    r.editViewingDate(log.id, WatchedWhen.On(LocalDate(2026, 6, 1))) // today; noon is before NOW (18:00)
    assertEquals(Instant.parse("2026-06-01T12:00:00Z"), db.movieDetailsDao().getByMediaId(id)!!.watchedAt)
}
```

Use the file's existing `object : Clock` pattern for `fixed(...)`. Then fix the two existing tests at lines ~642 and ~670, whose assertions assumed `Resource<Unit>` and stamping on transition:
- `updateWatchStatus_writesStatusAndWatchedAtAndTouchesNothingElse`: keep its "touches nothing else" assertions. It now also expects one film viewing at the clock's now.
- `updateWatchStatus_reSavingAlreadyWatched_preservesOriginalWatchedAt` is unchanged in meaning.

Add to `EnsureViewingsUseCaseTest`:

```kotlin
@Test fun backfillsWatchedFilms_too() = runTest {
    val id = (MovieRepository(db).addMovie(title = "Heat") as Resource.Success).data
    db.movieWriteDao().updateWatchStatusFields(id, WatchStatus.WATCHED, Instant.parse("2024-03-03T12:00:00Z"))
    assertEquals(1, (EnsureViewingsUseCase(db).execute() as Resource.Success).data)
    assertEquals(1, db.watchLogDao().observeForFilm(id).first().size)
}
```

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests '*MovieRepositoryTest*' --tests '*EnsureViewingsUseCaseTest*'`. Expected: a compile FAIL (`watchedWhen` and `zone` parameters are missing).

- [ ] **Step 3: Implement.**
  - Add a private helper: `private fun instantFor(w: WatchedWhen?): Instant = resolveWatchedAt(w ?: WatchedWhen.Now, airDate = null, now = clock.now(), zone = zone) ?: clock.now()`, importing `com.hub.media.features.tv.domain.resolveWatchedAt`.
  - `addMovie`: set `details.watchedAt = if (status == WATCHED) instantFor(watchedWhen) else null`. `insertMovieAtomically` adds the viewing.
  - `updateMovieMetadata`: call `updateMovieMetadataWithViewing(..., at = instantFor(watchedWhen))`.
  - `updateWatchStatus`: `val previous = db.movieWriteDao().changeStatus(mediaId, status, instantFor(watchedWhen)) ?: return Resource.Error("Movie with id=$mediaId not found")`, then `Resource.Success(previous)`.
  - The new functions each wrap their DAO call in the file's existing try/catch shape. A `false` or `null` DAO result becomes `Resource.Error("Movie with id=… not found")`, or "Viewing not found" for a log id.
  - Delete the companion `resolveWatchedAt` and any test that targets it directly. `grep -rn "MovieRepository.resolveWatchedAt\|Companion.resolveWatchedAt" shared/src` must be empty.
  - `EnsureViewingsUseCase.execute`: `val created = db.tvWriteDao().ensureViewingsForWatchedEpisodes() + db.movieWriteDao().ensureViewingsForWatchedFilms()`. Update its KDoc and log line to say "watched episodes and films".
  - `MovieDetailViewModel.updateStatus`: `if (result is Resource.Error) …` still compiles. Leave it; Task 3 rewrites it.

- [ ] **Step 4: Run them to see them pass.** Run the same command. Expected: PASS. Then run `./gradlew :shared:jvmTest`. Expected: PASS (it catches other `addMovie`/`updateWatchStatus` callers).

- [ ] **Step 5: Commit.** `git commit -m "MovieRepository: watchedWhen on add/edit/status, viewing history API; backfill films (#192)"`

---

### Task 3: `MovieDetailViewModel`, the prompt, history and Undo state

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/MovieDetailViewModel.kt`
- Test (create): `shared/src/jvmTest/kotlin/com/hub/media/ui/MovieDetailViewModelTest.kt`

**Interfaces:**
- Consumes Task 2's repository API.
- Produces:
  - `MovieDetailUiState.Ready(movie, errorMessage, viewings: List<WatchLogEntity> = emptyList(), pendingWatched: Boolean = false, pendingWatchedAgain: Boolean = false, viewingUndo: FilmViewingUndo? = null, statusUndo: StatusUndo? = null)`
  - `public data class FilmViewingUndo(val id: Long, val logs: List<WatchLogEntity>, val statusBefore: WatchStatus)`
  - `public data class StatusUndo(val id: Long, val previous: WatchStatus, val current: WatchStatus)`
  - Methods: `requestStatus(status)`, `confirmWatched(when: WatchedWhen)`, `dismissWatched()`, `requestWatchedAgain()`, `confirmWatchedAgain(when)`, `dismissWatchedAgain()`, `deleteViewing(log)`, `undoViewing()`, `consumeViewingUndo()`, `undoStatus()`, `consumeStatusUndo()`, `editViewingDate(log, when)`.
  - `updateStatus` is removed; the route uses `requestStatus`.

- [ ] **Step 1: Write the failing tests.** Build the ViewModel the way `TVShowDetailViewModelTest` does: a `viewModels` tracker, with Main installed through its helper. Use `MovieRepository(db, fixedClock, zone = TimeZone.UTC)`.

```kotlin
@Test fun becomingWatched_prompts_thenConfirmAddsViewing() = runTest {
    val vm = ready()
    vm.requestStatus(WatchStatus.WATCHED)
    vm.uiState.first { (it as? Ready)?.pendingWatched == true }
    vm.confirmWatched(WatchedWhen.Now)
    val s = vm.uiState.first { (it as? Ready)?.viewings?.size == 1 } as Ready
    assertFalse(s.pendingWatched)
    assertEquals(WatchStatus.WATCHED, s.movie.details?.status)
}

@Test fun dismissWatched_changesNothing() = runTest {
    val vm = ready()
    vm.requestStatus(WatchStatus.WATCHED); vm.uiState.first { (it as? Ready)?.pendingWatched == true }
    vm.dismissWatched()
    val s = vm.uiState.first { (it as? Ready)?.pendingWatched == false } as Ready
    assertEquals(WatchStatus.WATCHLIST, s.movie.details?.status)
}

@Test fun leavingWatched_keepsHistory_andOffersUndo() = runTest {
    val vm = ready(watched = true)
    vm.requestStatus(WatchStatus.WATCHING)
    val s = vm.uiState.first { (it as? Ready)?.statusUndo != null } as Ready
    assertEquals(1, s.viewings.size)
    assertEquals(WatchStatus.WATCHED, s.statusUndo!!.previous)
}

@Test fun undoLeavingWatched_restoresWithoutNewViewing() = runTest {
    val vm = ready(watched = true)
    vm.requestStatus(WatchStatus.WATCHING); vm.uiState.first { (it as? Ready)?.statusUndo != null }
    vm.undoStatus()
    val s = vm.uiState.first { (it as? Ready)?.movie?.details?.status == WatchStatus.WATCHED } as Ready
    assertEquals(1, s.viewings.size)
    assertNull(s.statusUndo)
}

@Test fun watchedAgain_addsSecondViewing() = runTest {
    val vm = ready(watched = true)
    vm.requestWatchedAgain(); vm.uiState.first { (it as? Ready)?.pendingWatchedAgain == true }
    vm.confirmWatchedAgain(WatchedWhen.On(LocalDate(2024, 3, 3)))
    vm.uiState.first { (it as? Ready)?.viewings?.size == 2 }
}

@Test fun deletingLast_movesToWatchlist_andUndoRestoresBoth() = runTest {
    val vm = ready(watched = true)
    val log = (vm.uiState.first { (it as? Ready)?.viewings?.size == 1 } as Ready).viewings.single()
    vm.deleteViewing(log)
    vm.uiState.first { (it as? Ready)?.movie?.details?.status == WatchStatus.WATCHLIST && it.viewingUndo != null }
    vm.undoViewing()
    vm.uiState.first { (it as? Ready)?.movie?.details?.status == WatchStatus.WATCHED && it.viewings.size == 1 }
}

@Test fun twoDeletes_mergeIntoOneUndo() = runTest {
    val vm = ready(watched = true)
    vm.requestWatchedAgain(); vm.uiState.first { (it as? Ready)?.pendingWatchedAgain == true }
    vm.confirmWatchedAgain(WatchedWhen.On(LocalDate(2024, 3, 3)))
    val logs = (vm.uiState.first { (it as? Ready)?.viewings?.size == 2 } as Ready).viewings
    vm.deleteViewing(logs[0]); vm.uiState.first { (it as? Ready)?.viewings?.size == 1 }
    vm.deleteViewing(logs[1])
    val s = vm.uiState.first { (it as? Ready)?.viewings?.isEmpty() == true && it.viewingUndo?.logs?.size == 2 } as Ready
    assertEquals(WatchStatus.WATCHED, s.viewingUndo!!.statusBefore)
    vm.undoViewing()
    vm.uiState.first { (it as? Ready)?.viewings?.size == 2 && it.movie.details?.status == WatchStatus.WATCHED }
}

@Test fun editViewingDate_movesWatchedAt() = runTest {
    val vm = ready(watched = true)
    val log = (vm.uiState.first { (it as? Ready)?.viewings?.size == 1 } as Ready).viewings.single()
    vm.editViewingDate(log, WatchedWhen.On(LocalDate(2024, 3, 3)))
    vm.uiState.first { (it as? Ready)?.movie?.details?.watchedAt == Instant.parse("2024-03-03T12:00:00Z") }
}
```

The helper `ready(watched)` adds the film through the repository, with `status = WATCHED` when asked, builds the ViewModel and awaits `Ready`. `Ready` is `MovieDetailUiState.Ready`, imported under that alias.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests '*MovieDetailViewModelTest*'`. Expected: a compile FAIL.

- [ ] **Step 3: Implement.** Follow `TVShowDetailViewModel`'s overlay shape:
  - Private `MutableStateFlow`s: `pendingWatched`, `pendingWatchedAgain`, `viewingUndo`, `statusUndo`, plus a `undoSeq` counter for ids.
  - `uiState = combine(observeMovieDetail(id), observeViewings(id), errorMessage, overlay) { ... }`, where `overlay = combine(pendingWatched, pendingWatchedAgain, viewingUndo, statusUndo, ::Overlay)` and `Overlay` is a private data class.
  - `requestStatus(s)`:
    1. Return if not Ready.
    2. Let `current = details?.status`.
    3. If `s == WATCHED && current != WATCHED`, set `pendingWatched = true`.
    4. Otherwise launch `updateWatchStatus(id, s)`. On Success with `previous == WATCHED && s != WATCHED`, set `statusUndo = StatusUndo(next(), previous, s)`.
  - `confirmWatched(w)`: set `pendingWatched = false` **before** launching (the #90 pattern), then launch `updateWatchStatus(id, WATCHED, w)`.
  - `requestWatchedAgain`, `confirmWatchedAgain(w)` and `dismissWatchedAgain` follow the same pattern with `addViewing(id, w)`.
  - `deleteViewing(log)`: launch `deleteViewing(log.id)`. On Success, merge: `viewingUndo = viewingUndo.value?.let { it.copy(id = next(), logs = it.logs + d.log) } ?: FilmViewingUndo(next(), listOf(d.log), d.statusBefore)`. The merge keeps the **first** `statusBefore`, which is the status before the run of deletes.
  - `undoViewing()`: take and clear `viewingUndo`, then `restoreViewings(logs, statusBefore)`.
  - `undoStatus()`: take and clear `statusUndo`, then `restoreStatus(id, previous)`.
  - `consumeViewingUndo()` and `consumeStatusUndo()` clear without restoring, when the snackbar times out.
  - `editViewingDate(log, w)`: launch `editViewingDate(log.id, w)`.
  - Every Error goes to `errorMessage`.

- [ ] **Step 4: Run them to see them pass.** Run the same command. Expected: PASS, 8 tests.

- [ ] **Step 5: Commit.** `git commit -m "MovieDetailViewModel: ask when, Watched again, history with merged Undo, status Undo (#192)"`

---

### Task 4: Add and Edit carry `watchedWhen`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditMovieViewModel.kt`, `.../ui/AddMovieViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/EditMovieViewModelTest.kt`, `.../ui/AddMovieViewModelTest.kt`

**Interfaces:**
- Produces, in `EditMovieUiState.Editing`:
  - New fields `initialStatus: WatchStatus`, `pendingWatched: Boolean = false`, `watchedWhen: WatchedWhen? = null`.
  - `onStatusChange(value)`: choosing WATCHED when `initialStatus != WATCHED` sets `pendingWatched = true` and leaves `status` unchanged. Any other choice sets `status = value` and `watchedWhen = null`.
  - `confirmWatched(w)` sets `status = WATCHED`, `watchedWhen = w`, `pendingWatched = false`. `dismissWatched()` sets `pendingWatched = false`.
  - `save()` passes `watchedWhen = form.watchedWhen`.
- Produces `AddMovieViewModel.save(title, releaseYear, runtimeMinutes, purchasePrice, status, watchedWhen: WatchedWhen? = null)`.

- [ ] **Step 1: Write the failing tests.**

```kotlin
// EditMovieViewModelTest
@Test fun choosingWatched_prompts_andSavePassesTheDay() = runTest {
    val vm = editing(status = WatchStatus.WATCHLIST)
    vm.onStatusChange(WatchStatus.WATCHED)
    vm.uiState.first { (it as? Editing)?.pendingWatched == true }
    vm.confirmWatched(WatchedWhen.On(LocalDate(2024, 3, 3)))
    vm.uiState.first { (it as? Editing)?.status == WatchStatus.WATCHED }
    vm.save(); vm.uiState.first { (it as? Editing)?.saved == true }
    assertEquals(Instant.parse("2024-03-03T12:00:00Z"), db.movieDetailsDao().getByMediaId(id)!!.watchedAt)
}

@Test fun alreadyWatched_reselectingWatched_doesNotPrompt() = runTest {
    val vm = editing(status = WatchStatus.WATCHED)
    vm.onStatusChange(WatchStatus.WATCHING); vm.onStatusChange(WatchStatus.WATCHED)
    val s = vm.uiState.first { (it as? Editing)?.status == WatchStatus.WATCHED } as Editing
    assertFalse(s.pendingWatched)
}

// AddMovieViewModelTest
@Test fun save_watchedOnPickedDay_storesThatViewing() = runTest {
    vm.save("Heat", null, null, null, WatchStatus.WATCHED, WatchedWhen.On(LocalDate(2024, 3, 3)))
    val id = (vm.uiState.first { it is AddMovieUiState.Saved } as AddMovieUiState.Saved).mediaId
    assertEquals(Instant.parse("2024-03-03T12:00:00Z"), db.watchLogDao().observeForFilm(id).first().single().watchedAt)
}
```

Construct the repository with `zone = TimeZone.UTC` in both files' setup. Match `AddMovieUiState.Saved`'s property name to the one in the file.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests '*EditMovieViewModelTest*' --tests '*AddMovieViewModelTest*'`. Expected: a compile FAIL.
- [ ] **Step 3: Implement** as described in Interfaces. `initialStatus` is filled where `status` is first loaded (line ~90).
- [ ] **Step 4: Run them to see them pass.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Add/Edit movie: ask when on becoming Watched (#192)"`

---

### Task 5: CSV import of film viewings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/WatchLogCsvImporter.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ImportDataUseCase.kt` (the viewing pass, ~lines 539–633)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/ImportWriteDao.kt`, `.../features/portability/data/ImportWriteRepository.kt`
- Modify: `docs/sample-data/watch_logs_sample.csv`, `docs/CSV_FORMAT.md` (or wherever `CSV_FORMAT.md` lives: `git ls-files | grep CSV_FORMAT`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/portability/domain/WatchLogImportExportTest.kt`, `.../SampleLibraryCsvTest.kt`

**Interfaces:**
- `ParsedWatchLogRow.episodeId: String?`, where blank means a film viewing.
- `ImportWriteDao.importAtomically(..., filmViewingDeletes: Collection<String> = emptyList(), touchedFilmIds: Collection<String> = emptyList())`. The same parameters are added to `ImportWriteRepository.importAtomically`.
- `ImportWriteDao`: `deleteFilmViewings(mediaId)`, `filmViewingCountAt(mediaId, watchedAt): Int`, `movieDetailsById(mediaId): MovieDetailsEntity?`, and `recomputeFilmWatchedAt(mediaId)`, the same SQL as Task 1.

- [ ] **Step 1: Write the failing tests.** Add to `WatchLogImportExportTest`. Extend `seedSource()`'s sibling with `seedFilm(): String`, which adds "Heat" as Watched at t1 through `MovieRepository(source, fixed(t1))`, then `source.movieWriteDao().addFilmViewing(id, t2)`.

```kotlin
@Test fun filmViewings_roundTrip() = runTest {
    val film = seedFilm()
    import(target, export(source))
    assertEquals(listOf(t2, t1), target.watchLogDao().observeForFilm(film).first().map { it.watchedAt })
    assertEquals(t2, target.movieDetailsDao().getByMediaId(film)!!.watchedAt)
}

@Test fun filmViewings_reimport_createsNothing() = runTest {
    seedFilm(); val b = export(source)
    import(target, b)
    val again = assertIs<Resource.Success<ImportSummary>>(import(target, b)).data
    assertEquals(0, again.viewingsImported); assertEquals(2, again.viewingsSkipped)
}

@Test fun twoFilmsWithSameDate_bothImported() = runTest {
    val a = seedFilm()
    val b = (MovieRepository(source, fixed(t1)).addMovie(title = "Ronin", status = WatchStatus.WATCHED) as Resource.Success).data
    import(target, export(source))
    assertEquals(listOf(t1), target.watchLogDao().observeForFilm(b).first().map { it.watchedAt })
    assertEquals(2, target.watchLogDao().observeForFilm(a).first().size)
}

@Test fun filmViewing_pointingAtAShow_isRejected() = runTest {
    val (e1, _) = seedSource(); val film = seedFilm(); val bundle = export(source)
    val tampered = bundle.watchLogsCsv.replace("$film,,", "${e1.mediaId},,")
    assertNotEquals(bundle.watchLogsCsv, tampered, "positive control: the tamper took")
    val s = assertIs<Resource.Success<ImportSummary>>(import(target, bundle.copy(watchLogsCsv = tampered))).data
    assertEquals(2, s.rejections.count { it.source == ImportRowSource.WATCH_LOG })
}

@Test fun replace_filmFileWins() = runTest {
    val film = seedFilm()
    import(target, export(source))
    target.movieWriteDao().addFilmViewing(film, Instant.parse("2025-06-01T12:00:00Z"))
    import(target, export(source), DuplicatePolicy.REPLACE)
    assertEquals(listOf(t2, t1), target.watchLogDao().observeForFilm(film).first().map { it.watchedAt })
}

@Test fun film_watchedAtWithoutMatchingViewing_keepsItsDate() = runTest {
    val film = seedFilm(); val b = export(source)
    val trimmed = b.watchLogsCsv.lines().filterNot { film in it && "2025" in it }.joinToString("\n")
    assertEquals(b.watchLogsCsv.lines().size - 1, trimmed.lines().size, "positive control")
    import(target, b.copy(watchLogsCsv = trimmed))
    assertEquals(t2, target.movieDetailsDao().getByMediaId(film)!!.watchedAt)
}
```

Also extend `assertInvariantEverywhere` to check films: for each movie in `MediaRepository(db).observeAll…`, use the same rule as Task 1's `assertInvariant`. `SampleLibraryCsvTest`'s watch-logs guard should assert the sample's film rows resolve to a MOVIE in `library_sample.csv`.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests '*WatchLogImportExportTest*'`. Expected: FAIL. Film rows are rejected with "film viewings are not imported yet".

- [ ] **Step 3: Implement.**
  - Importer: `episodeId = row[COL_EPISODE_ID].ifBlank { null }`. Remove the rejection and its comment.
  - `ImportDataUseCase` viewing pass:
    - Film ids: `val filmIds = (existing movies by id) + inserts/updates where details is ImportDetails.Movie`. Build a `filmById: Map<String, Unit>` from `mediaRepository`'s movie list plus `mediaResolution.inserts.filter { it.details is ImportDetails.Movie }.map { it.mediaItem.id }`, and the same for updates.
    - Key dedup for films separately: `val seenFilmViewings = existingViewings.filter { it.episodeId == null }.groupBy { it.mediaId }.mapValuesTo(mutableMapOf()) { … watchedAt set }`. Restrict the episode map to `episodeId != null`, so films no longer share the null key.
    - REPLACE: `val replacedFilmIds = mediaResolution.updates.filter { it.replaceIdentifiers && it.details is ImportDetails.Movie }.map { it.mediaItem.id }`. For each, clear `seenFilmViewings[id]` and free its stored log ids, mirroring the episode loop.
    - For a row with `episodeId == null`: resolve the id with `resolvedMediaId = mediaResolution.resolvedMediaId[row.mediaId] ?: row.mediaId`. If `resolvedMediaId !in filmIds`, reject with `"media_id '${row.mediaId}' is not a known film (not already in your library, and not present in the library file being imported) -- viewing skipped"`. Otherwise dedup on `seenFilmViewings`, then insert `WatchLogEntity(id, resolvedMediaId, null, row.watchedAt, row.durationSeconds)` and count it.
    - Pass `filmViewingDeletes = replacedFilmIds`. `touchedFilmIds` is every film id among inserts and updates plus every film viewing insert.
  - `ImportWriteDao.importAtomically`: after the episode normalisation, run `filmViewingDeletes.forEach { deleteFilmViewings(it) }` **before** `viewingInserts` are inserted. Move it next to the episode deletes. Then, for each touched film:

    ```kotlin
    val d = movieDetailsById(id) ?: continue
    val at = d.watchedAt
    if (d.status == WatchStatus.WATCHED && at != null && filmViewingCountAt(id, at) == 0) {
        insertViewing(WatchLogEntity(id = newId(), mediaId = id, episodeId = null, watchedAt = at))
    }
    recomputeFilmWatchedAt(id)
    ```

  - Sample data: append two rows for a Watched film in `library_sample.csv`: one at that film's `watched_at`, one earlier. Use its real `media_id`, and copy a row's shape from the existing file.
  - `CSV_FORMAT.md`, File 4 section: a blank `episode_id` is a film viewing. The film must be a movie in the library or the library file. Dedup is on (film, watched_at). REPLACE means the file wins for films too.

- [ ] **Step 4: Run them to see them pass.** Run: `./gradlew :shared:jvmTest --tests '*WatchLogImportExportTest*' --tests '*SampleLibraryCsvTest*' --tests '*ImportDataUseCase*'`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Import film viewings: dedup per film, REPLACE file wins, normalise touched films (#192)"`

---

### Task 6: Shared UI pieces: `ViewingRow` and `FilmWatchedDialog`

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/ViewingRow.kt`
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/FilmWatchedDialog.kt`
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EpisodeSheet.kt` (use `ViewingRow`)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- `@Composable fun ViewingRow(viewing: WatchLogEntity, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier)`. It holds exactly today's Row from `EpisodeSheet` lines 95–109: the day text, then the Edit and Delete `IconButton`s with the same content-description strings.
- `@Composable fun FilmWatchedDialog(title: String, confirmLabelNow: String = stringResource(R.string.film_watched_now), onConfirm: (WatchedWhen) -> Unit, onDismiss: () -> Unit)`. It is an `AlertDialog` titled `stringResource(R.string.film_watched_title, title)`, with buttons **Now** and **Pick a date**. Pick a date sets `picking = true` (`rememberSaveable`) and shows `WatchedDatePickerDialog(initialPickMillis = null, confirmLabel = stringResource(R.string.film_watched_pick_confirm), onPicked = onConfirm, onDismiss = { picking = false })`.
- Strings: `film_watched_title` ("Watched %1$s"), `film_watched_again_title` ("Watched %1$s again"), `film_watched_now` ("Now"), `film_watched_pick` ("Pick a date"), `film_watched_pick_confirm` ("Save").

- [ ] **Step 1: Refactor the EpisodeSheet.** Extract `ViewingRow` and use it in `EpisodeSheet`. The existing `EpisodeSheetContentTest` (5) and `EpisodeSheetGoldenTest` are the guard: they must stay green with no golden diff.
- [ ] **Step 2: Run the guard.** Run: `./gradlew :app:verifyRoborazziDebug --tests '*EpisodeSheet*'`. Expected: PASS, no golden change.
- [ ] **Step 3: Add the dialog.** Create `FilmWatchedDialog` and the strings. It is exercised in Task 7's tests.
- [ ] **Step 4: Commit.** `git commit -m "Extract ViewingRow from the episode sheet; add FilmWatchedDialog (#192)"`

---

### Task 7: Screens: Movie detail History, Add and Edit prompts

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/MovieDetailScreen.kt`
- Modify: `.../ui/screens/EditMovieScreen.kt`, `.../ui/screens/AddMovieScreen.kt`, `app/src/main/res/values/strings.xml`
- Test (create): `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/MovieHistorySectionTest.kt`
- Test (create): `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/MovieDetailHistoryGoldenTest.kt`
- Test (create): `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/MovieHistoryEditDateTest.kt`
- Modify: existing callers of `MovieDetailScreen`, `AddMovieScreen` and `EditMovieScreen` in tests and previews (`grep -rn "MovieDetailScreen(\|AddMovieScreen(\|EditMovieScreen(" app/src`).

**Interfaces:**
- `@Composable internal fun MovieHistorySection(viewings: List<WatchLogEntity>, isWatched: Boolean, onWatchedAgain: () -> Unit, onEditViewing: (WatchLogEntity) -> Unit, onDeleteViewing: (WatchLogEntity) -> Unit)`. It shows a title "History", one `ViewingRow` per viewing, and an `OutlinedButton` "Watched again" when `isWatched`. With no viewings it shows "Not watched yet".
- The stateless `MovieDetailScreen` gains `viewings`, `onWatchedAgain`, `onEditViewing` and `onDeleteViewing`. `onStatusChange` stays the parameter name, and the route passes `viewModel::requestStatus`.
- The route hosts:
  - `FilmWatchedDialog` when `pendingWatched` → `confirmWatched` / `dismissWatched`, and when `pendingWatchedAgain` → `confirmWatchedAgain` / `dismissWatchedAgain`, titled with `film_watched_again_title`.
  - The Edit-date `WatchedDatePickerDialog`: `rememberSaveable` of the editing log id, looked up in `viewings`, with `initialPickMillis = localDateToUtcMidnightMillis(instantToLocalDateTime(log.watchedAt).toLocalDate())`.
  - Two `LaunchedEffect`s on the screen's `snackbarHostState`. `viewingUndo` shows `viewing_deleted_snackbar` with Undo → `undoViewing()`, else `consumeViewingUndo()`. `statusUndo` shows `film_status_moved_snackbar` ("Moved to %1$s") with Undo → `undoStatus()`, else `consumeStatusUndo()`. The status label comes from the same mapper the status chip uses.
- `EditMovieScreen`: when `form.pendingWatched`, show `FilmWatchedDialog(title = form.title, onConfirm = vm::confirmWatched, onDismiss = vm::dismissWatched)`.
- `AddMovieScreen`: hold `pendingWatched` and `watchedWhen` in `rememberSaveable`; `WatchedWhen` is not `Parcelable`, so save the epoch-day or `null` for Now. The status radio's `onClick` for WATCHED opens the dialog, and confirming sets `status` and `watchedWhen`. `onSave` gains a `WatchedWhen?` parameter, and the route passes it to `vm.save`.

- [ ] **Step 1: Write the failing Robolectric test.** Create `MovieHistorySectionTest`, modelled on `EpisodeSheetContentTest`:
  - With two viewings: both day texts show, tapping "Edit date…" on the first reports that log, and "Delete viewing…" reports that log.
  - Watched again is shown when `isWatched` and reports a click.
  - With `viewings = emptyList()`, `isWatched = false`: "Not watched yet" shows and Watched again does not exist (`assertDoesNotExist`). Positive control: the title "History" exists.
- [ ] **Step 2: Run it to see it fail.** Run: `./gradlew :app:testDebugUnitTest --tests '*MovieHistorySectionTest*'`. Expected: a compile FAIL.
- [ ] **Step 3: Implement** the section and the screen and route wiring, the Add and Edit prompts, and the strings. Fix every call site the grep lists.
- [ ] **Step 4: Run it to see it pass.** Expected: PASS.
- [ ] **Step 5: Add the golden.** Create `MovieDetailHistoryGoldenTest`. Copy `EpisodeSheetGoldenTest`'s setup, with `@GraphicsMode(NATIVE)`, `CaptureType.Screenshot()` and `dynamicColor = false`. It captures `MovieHistorySection` with two viewings and `isWatched = true`, and `alsoAssert` checks that the "Watched again" node exists. Record with `./gradlew :app:recordRoborazziDebug --tests '*MovieDetailHistoryGoldenTest*'`, **look at the PNG**, then commit the PNG alone. Re-record any existing movie-detail golden only if its diff is the new section, and look at it too.
- [ ] **Step 6: Add the androidTest.** Create `MovieHistoryEditDateTest`, a copy of `EpisodeSheetEditDateTest` with `MovieHistorySection` as the host: tapping Edit on a viewing opens the picker on that viewing's day, and Save reports `watchedOnPickedDay(dayMillis)`. It is device-only because the dialog holds a date input (AGENTS.md §7).
- [ ] **Step 7: Run the gate, then commit.** Run the full gate from Global Constraints, then commit: `git commit -m "Movie detail History, Watched again, status Undo; Add/Edit ask when (#192)"`.

---

### Task 8: Changelog, docs, device verification

**Files:**
- Modify: `CHANGELOG.md` (`[Unreleased]`), `README.md` if it lists watch-history capabilities (`grep -n "Watched again\|viewing" README.md`)

- [ ] **Step 1: Update CHANGELOG `[Unreleased]`.**
  - **Added:** "Films keep a watch history: setting a film to Watched asks when, *Watched again* records a rewatch, and each viewing's date can be edited or deleted (with Undo). Moving a film off Watched keeps its history."
  - **Changed:** "Watch-history CSV exports and imports now include films."
  - **Internal:** "Film viewings are `watch_logs` rows with no episode; a Watched film's `watchedAt` is always its latest viewing, and the startup backfill gives older watched films their one viewing."
- [ ] **Step 2: Run the gate,** plus `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Run the device suite.** `adb devices`; if a device is listed, run `./gradlew :app:connectedDebugAndroidTest`, then `./gradlew :app:seedDebugDevice`. Record the tally.
- [ ] **Step 4: Drive it by hand on the device.** Open a film and set it to Watched: pick a date, and the History shows it. Watched again → 2 rows. Set it to Watching: History stays, and the snackbar Undo restores Watched with 2 rows. Delete both: the film is on Watchlist; Undo restores both and the Watched status. Edit a date. Add a new film as Watched: the prompt appears.
- [ ] **Step 5: Commit, then run local CodeRabbit.** `git commit -m "Changelog: film watch history (#192)"`, then `coderabbit review --agent --committed --base main -c AGENTS.md` and fix any valid findings with RED→GREEN tests.
