# Show Watch-throughs and Film Viewing Ratings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Number a show's passes through its episodes as watch-throughs, each with its own ticks, abandoned flag and half-star rating. Give every film viewing an optional rating. Both reuse #230's stars, header row and rate-on-finish dialog.

**Architecture:**
- **Tables:**
  - a new `watch_throughs` table (schema v8, edited in place, since it is unreleased);
  - `watch_logs` gains `watchThroughId` (episode viewings) and `rating` (film viewings).
- **`episodes.watchedAt` narrows to the latest run.** It stays the cache every checklist reader uses, but now means "latest viewing *in the latest watch-through*". So the checklist, progress, tick prompt and up-next code keep working unchanged.
- **A run's state is derived; only `abandoned` is stored.**
- **Staged like #230:**
  1. additive table and migration;
  2. latest-run semantics and run actions;
  3. drop `tv_details.status`;
  4. film ratings;
  5. the show ViewModel;
  6. CSV;
  7. shared UI pieces;
  8. the show screen;
  9. the film screen;
  10. goldens, docs and device.

**Tech Stack:** Kotlin Multiplatform, Room KMP (bundled SQLite in jvmTest), `kotlin.time.Instant`, Jetpack Compose + Material 3, Robolectric + Roborazzi.

**Spec:** `docs/superpowers/specs/2026-10-21-watch-throughs-design.md` (approved 2026-10-21). Read it before Task 1. Part 1's plan, `docs/superpowers/plans/2026-10-17-read-throughs.md`, is the model for most of these shapes.

## Global Constraints

- **Ratings:**
  - half-steps stored as `Int` **1..10**, validated with `isValidRating` (`features/books/data/Rating.kt`); `null` = unrated;
  - an episode viewing's `rating` is always `null`;
  - a film viewing's `watchThroughId` is always `null`;
  - an episode viewing's `watchThroughId` is always set.
- **Watch-throughs:** every show has watch-through #1. Only the latest run can be removed, never #1, and only with no viewings.
- **Schema:**
  - `APP_DATABASE_VERSION` stays **8**;
  - `MIGRATION_7_8` is extended and `shared/schemas/.../8.json` is regenerated in place (v8 shipped in no tag);
  - the AGENTS.md §8 v8 row is amended in the commit that changes the migration.
- **CSV:** `CSV_SCHEMA_VERSION` 7 → **8**.
- **Code rules:**
  - UUID ids (`newId()`, or `SQL_UUID` inside the migration).
  - Catch around suspending code with `guardResource` / `catchingNonCancellation`, never a bare `catch (e: Exception)`.
  - Room tests go in `shared/src/jvmTest`, pure tests in `commonTest`, and VM tests await `uiState.first { … }`.
  - No new dependencies.
- **Before each commit:** `ktlintFormat`, max line 120. Each commit ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01X3dhMjqKB1M3voUhWUrqxJ
  ```
- **Per-task check:** `./gradlew :shared:jvmTest :shared:testDebugUnitTest ktlintCheck` (and `:app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleDebug` from Task 7 on). The full gate runs before push.
- **Book goldens must not change** (Task 7 moves shared components). If `verifyRoborazziDebug` reports a book diff, the move changed something and must be fixed, never re-recorded.

## Review Focus

1. **Watch again and the checklist.** A show with every episode ticked, after Watch again, must show 0 ticked. Removing that empty run must restore every tick with its exact date. *Test: Task 2 `watchAgain_clearsTicks_andRemoveRestoresThem`.*
2. **Unticking during run #2** must not delete run #1's viewings: stats would lose history. *Test: Task 2 `untickInRunTwo_keepsRunOneViewings`.*
3. **A viewing whose run was removed after an untick** must make Undo skip it quietly, not violate the foreign key and abort. *Test: Task 2 `restoreAll_afterItsRunWasRemoved_skipsQuietly`.*
4. **A double tap on Watch again** must create one run and report an error for the second, not crash. *Test: Task 2 `watchAgain_twice_createsOneRun`.*
5. **An abandoned show in an old (≤ 7) export** loses its flag on import. That is today's behaviour, since the flag was never exported. A **v8** export must round-trip it. *Test: Task 6 `v8RoundTrip_keepsAbandonedRun_andFilmRatings`.*

---

### Task 1: The watch-throughs table, viewing columns, and `MIGRATION_7_8` (additive)

Nothing reads runs yet. Every path that creates a show or an episode viewing assigns one, so Task 2 can switch readers without repairing data. `tv_details.status` stays until Task 3.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/entities/WatchThroughEntity.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/WatchThroughDao.kt`
- Modify:
  - `.../core/database/entities/WatchLogEntity.kt` (two columns, a FK and an index)
  - `.../core/database/AppDatabase.kt` (entity list, `watchThroughDao()`)
  - `.../core/database/Migrations.kt` (`MIGRATION_7_8` steps 4–6 and its KDoc)
  - `.../core/database/dao/TVWriteDao.kt` (`insertShowAtomically`, `insertViewing` callers)
  - `.../core/database/dao/ImportWriteDao.kt` (show insert creates run #1; episode viewings get the latest run)
  - `AGENTS.md` §8 v8 row
- Test:
  - `shared/src/jvmTest/.../core/database/MigrationTest.kt`
  - `shared/src/jvmTest/.../core/database/WatchThroughDaoTest.kt` (new)

**Interfaces:**
- Produces:
  - `WatchThroughEntity(id: String, mediaId: String, number: Int, abandoned: Boolean = false, rating: Int? = null)`
  - `WatchThroughDao`: `insert`, `insertFirstIfMissing`, `observeForMedia(mediaId): Flow<List<WatchThroughEntity>>`, `getForMedia`, `observeAll`, `getAll`, `getLatest(mediaId)`, `getById`, `setAbandoned(id, Boolean): Int`, `updateRating(id, Int?): Int`, `viewingCount(id): Int`, `deleteIfRemovable(id): Int`
  - `WatchLogEntity(..., watchThroughId: String? = null, rating: Int? = null)`
  - `TVWriteDao.latestRunId(mediaId): String`, which self-heals by creating run #1

- [ ] **Step 1: Write the failing migration tests** in `MigrationTest.kt`, after `migrate7To8_emptyDatabase_validatesCleanly`:

```kotlin
    private fun androidx.sqlite.SQLiteConnection.seedV7Show(
        id: String,
        status: String,
    ) {
        execSQL(
            "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash, " +
                "communityRating, synopsis) VALUES ('$id', 'TV_SHOW', 'Show $id', NULL, NULL, 1700000000000, NULL, NULL, NULL)",
        )
        execSQL("INSERT INTO tv_details (mediaId, totalSeasons, status) VALUES ('$id', 1, '$status')")
    }

    private fun androidx.sqlite.SQLiteConnection.seedV7Episode(
        id: String,
        mediaId: String,
        number: Int,
        watchedAt: Long?,
    ) {
        execSQL(
            "INSERT INTO episodes (id, mediaId, seasonNumber, episodeNumber, watchedAt) " +
                "VALUES ('$id', '$mediaId', 1, $number, ${watchedAt ?: "NULL"})",
        )
    }

    private fun androidx.sqlite.SQLiteConnection.seedV7Viewing(
        id: String,
        mediaId: String,
        episodeId: String?,
        at: Long,
    ) {
        execSQL(
            "INSERT INTO watch_logs (id, mediaId, episodeId, watchedAt, durationSeconds) " +
                "VALUES ('$id', '$mediaId', ${episodeId?.let { "'$it'" } ?: "NULL"}, $at, NULL)",
        )
    }

    /** Every show gets watch-through #1, abandoned only when the show was (#231). */
    @Test
    fun migrate7To8_everyShowGetsRunOne_carryingAbandoned() {
        helper.createDatabase(7).use { db ->
            db.seedV7Show("dropped", "ABANDONED")
            db.seedV7Show("watching", "WATCHLIST")
            db.seedV7Show("empty", "WATCHLIST")
        }
        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
            val rows = mutableListOf<String>()
            db.prepare("SELECT mediaId, number, abandoned, rating IS NULL FROM watch_throughs ORDER BY mediaId").use { s ->
                while (s.step()) rows += "${s.getText(0)}:${s.getLong(1)}:${s.getLong(2)}:${s.getLong(3)}"
            }
            assertEquals(listOf("dropped:1:1:1", "empty:1:0:1", "watching:1:0:1"), rows)
            db.prepare("PRAGMA table_info(watch_logs)").use { s ->
                val cols = mutableListOf<String>()
                while (s.step()) cols += s.getText(1)
                assertTrue("watchThroughId" in cols && "rating" in cols && "durationSeconds" in cols, "$cols")
            }
        }
    }

    /** Episode viewings join run #1; film viewings join none; ticks are untouched (#231). */
    @Test
    fun migrate7To8_episodeViewingsJoinRunOne_filmViewingsJoinNone() {
        helper.createDatabase(7).use { db ->
            db.seedV7Show("tv", "WATCHLIST")
            db.seedV7Episode("e1", "tv", 1, 1_700_000_000_000)
            db.seedV7Episode("e2", "tv", 2, null)
            db.seedV7Viewing("l1", "tv", "e1", 1_700_000_000_000)
            db.execSQL(
                "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash, " +
                    "communityRating, synopsis) VALUES ('film', 'MOVIE', 'Heat', NULL, NULL, 1700000000000, NULL, NULL, NULL)",
            )
            db.execSQL("INSERT INTO movie_details (mediaId, runtimeMinutes, status, watchedAt) VALUES ('film', 170, 'WATCHED', 1700000000000)")
            db.seedV7Viewing("l2", "film", null, 1_700_000_000_000)
        }
        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
            val rows = mutableListOf<String>()
            db
                .prepare(
                    "SELECT l.id, w.number, l.rating IS NULL FROM watch_logs l " +
                        "LEFT JOIN watch_throughs w ON w.id = l.watchThroughId ORDER BY l.id",
                ).use { s ->
                    while (s.step()) rows += "${s.getText(0)}:${if (s.isNull(1)) "-" else s.getLong(1)}:${s.getLong(2)}"
                }
            assertEquals(listOf("l1:1:1", "l2:-:1"), rows)
            db.prepare("SELECT id, watchedAt FROM episodes ORDER BY id").use { s ->
                val ticks = mutableListOf<String>()
                while (s.step()) ticks += "${s.getText(0)}:${if (s.isNull(1)) "-" else s.getLong(1)}"
                assertEquals(listOf("e1:1700000000000", "e2:-"), ticks)
            }
        }
    }
```

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests "*MigrationTest.migrate7To8*"`. Expected: FAIL (`no such table: watch_throughs`).

- [ ] **Step 3: Add the entity, the DAO and the columns.**

`WatchThroughEntity.kt`:

```kotlin
package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pass through a show (#231): watch-through #1, #2... Its episodes' ticks are the viewings that
 * carry its id. Watching, Finished and Up to date are derived from those ticks, never stored, as a
 * show's state always was; only [abandoned] is stored, because giving up is a decision no tick count
 * expresses. A show always has #1.
 *
 * @property rating Half-steps 1..10 (`isValidRating`), or `null` for unrated.
 */
@Entity(
    tableName = "watch_throughs",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId", "number"], unique = true)],
)
public data class WatchThroughEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val number: Int,
    val abandoned: Boolean = false,
    val rating: Int? = null,
)
```

`WatchThroughDao.kt`:

```kotlin
package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlinx.coroutines.flow.Flow

/** A show's watch-throughs (#231). Writes that also move ticks live in [TVWriteDao]'s transactions. */
@Dao
interface WatchThroughDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: WatchThroughEntity)

    /** Creates watch-through #1 unless the show already has it (the unique index makes this a no-op). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstIfMissing(run: WatchThroughEntity): Long

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number")
    fun observeForMedia(mediaId: String): Flow<List<WatchThroughEntity>>

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number")
    suspend fun getForMedia(mediaId: String): List<WatchThroughEntity>

    @Query("SELECT * FROM watch_throughs ORDER BY mediaId, number")
    fun observeAll(): Flow<List<WatchThroughEntity>>

    @Query("SELECT * FROM watch_throughs ORDER BY mediaId, number")
    suspend fun getAll(): List<WatchThroughEntity>

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun getLatest(mediaId: String): WatchThroughEntity?

    @Query("SELECT * FROM watch_throughs WHERE id = :id")
    suspend fun getById(id: String): WatchThroughEntity?

    @Query("UPDATE watch_throughs SET abandoned = :abandoned WHERE id = :id")
    suspend fun setAbandoned(
        id: String,
        abandoned: Boolean,
    ): Int

    @Query("UPDATE watch_throughs SET rating = :rating WHERE id = :id")
    suspend fun updateRating(
        id: String,
        rating: Int?,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_logs WHERE watchThroughId = :id")
    suspend fun viewingCount(id: String): Int

    /** Deletes [id] only if it is its show's latest run, not #1, and has no viewings -- one statement, no race. */
    @Query(
        "DELETE FROM watch_throughs WHERE id = :id AND number > 1 " +
            "AND number = (SELECT MAX(w.number) FROM watch_throughs w WHERE w.mediaId = watch_throughs.mediaId) " +
            "AND NOT EXISTS (SELECT 1 FROM watch_logs l WHERE l.watchThroughId = :id)",
    )
    suspend fun deleteIfRemovable(id: String): Int
}
```

In `WatchLogEntity`:
- add a third foreign key, `ForeignKey(entity = WatchThroughEntity::class, parentColumns = ["id"], childColumns = ["watchThroughId"], onDelete = ForeignKey.CASCADE)`;
- add `Index(value = ["watchThroughId"])`;
- add the fields `val watchThroughId: String? = null` and `val rating: Int? = null` after `durationSeconds`;
- KDoc them:
  - `watchThroughId` is the run an episode viewing belongs to, always set for an episode and `null` for a film (#231);
  - `rating` is half-steps for a film viewing, always `null` for an episode, which is rated per watch-through.

In `AppDatabase`, add `WatchThroughEntity::class` to `entities` and `abstract fun watchThroughDao(): WatchThroughDao`.

- [ ] **Step 4: Extend `MIGRATION_7_8`.** Append inside `loggedMigration(7, 8) { … }`, after the `book_details` rebuild, and add steps 4–6 to its KDoc:

```kotlin
                // 4. Watch-throughs (#231): every show gets #1, abandoned if the show was.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_throughs` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, " +
                        "`number` INTEGER NOT NULL, `abandoned` INTEGER NOT NULL, `rating` INTEGER, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_throughs_mediaId_number` " +
                        "ON `watch_throughs` (`mediaId`, `number`)",
                )
                connection.execSQL(
                    "INSERT INTO `watch_throughs` (`id`, `mediaId`, `number`, `abandoned`, `rating`) " +
                        "SELECT $SQL_UUID, m.`id`, 1, CASE WHEN t.`status` = 'ABANDONED' THEN 1 ELSE 0 END, NULL " +
                        "FROM `media_items` m LEFT JOIN `tv_details` t ON t.`mediaId` = m.`id` " +
                        "WHERE m.`type` = 'TV_SHOW'",
                )
                // 5. Viewings gain their run (episodes) and a rating (films).
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_logs_new` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, " +
                        "`episodeId` TEXT, `watchedAt` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`watchThroughId` TEXT, `rating` INTEGER, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`episodeId`, `mediaId`) REFERENCES `episodes`(`id`, `mediaId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`watchThroughId`) REFERENCES `watch_throughs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `watch_logs_new` (`id`, `mediaId`, `episodeId`, `watchedAt`, `durationSeconds`, " +
                        "`watchThroughId`, `rating`) SELECT l.`id`, l.`mediaId`, l.`episodeId`, l.`watchedAt`, " +
                        "l.`durationSeconds`, CASE WHEN l.`episodeId` IS NULL THEN NULL ELSE w.`id` END, NULL " +
                        "FROM `watch_logs` l LEFT JOIN `watch_throughs` w ON w.`mediaId` = l.`mediaId` AND w.`number` = 1",
                )
                connection.execSQL("DROP TABLE `watch_logs`")
                connection.execSQL("ALTER TABLE `watch_logs_new` RENAME TO `watch_logs`")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_logs_mediaId` ON `watch_logs` (`mediaId`)")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_logs_episodeId_mediaId` ON `watch_logs` (`episodeId`, `mediaId`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_logs_watchThroughId` ON `watch_logs` (`watchThroughId`)",
                )
```

Step 6 (the `tv_details` rebuild) is Task 3.

- [ ] **Step 5: Assign a run on every write that creates a show or an episode viewing.**
  - **In `TVWriteDao`:**
    - add `@Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFirstRunIfMissing(run: WatchThroughEntity): Long`;
    - add `@Query("SELECT id FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1") suspend fun latestRunIdOrNull(mediaId: String): String?`;
    - add:
    ```kotlin
        /** The show's latest run, creating #1 if it has none -- the self-heal #230's `insertIntoLatestRead` does. */
        suspend fun latestRunId(mediaId: String): String =
            latestRunIdOrNull(mediaId) ?: run {
                insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = mediaId, number = 1))
                checkNotNull(latestRunIdOrNull(mediaId))
            }
    ```
    - In `insertShowAtomically`, after `insertTVDetails(details)`, call `insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = item.id, number = 1))`.
    - In `markEpisodesWatchedAt`, `addViewing` and `ensureViewingsForWatchedEpisodes`, build the `WatchLogEntity` with `watchThroughId = latestRunId(episode.mediaId)`.
    - In `restoreViewing` and `restoreAll`, logs keep their own `watchThroughId`; Task 2 handles a missing run.
  - **In `ImportWriteDao`:**
    - add the same `insertFirstRunIfMissing` / `latestRunIdOrNull` / `latestRunId`;
    - in `importAtomically`'s insert branch, `is ImportDetails.TVShow -> { insertTvDetails(details.details); insertFirstRunIfMissing(WatchThroughEntity(newId(), insert.mediaItem.id, 1)) }`;
    - in the viewings section, `viewingInserts.forEach { insertViewing(if (it.episodeId != null && it.watchThroughId == null) it.copy(watchThroughId = latestRunId(it.mediaId)) else it) }`;
    - give the synthetic "watched but no viewing" insert `watchThroughId = latestRunId(episode.mediaId)`.

- [ ] **Step 6: Write `WatchThroughDaoTest`** (jvmTest, built like `ReadThroughDaoTest`):

```kotlin
class WatchThroughDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun show(episodes: Int = 2): String =
        (repo.addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = episodes))) as Resource.Success).data

    @Test
    fun addingAShow_createsRunOne() =
        runTest {
            val id = show()
            assertEquals(listOf(1), db.watchThroughDao().getForMedia(id).map { it.number })
        }

    @Test
    fun aTick_landsInTheLatestRun() =
        runTest {
            val id = show()
            val e = db.episodeDao().getByMediaIdAndSeason(id, 1).first()
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(e.id to Instant.parse("2025-01-01T12:00:00Z")))
            val run = db.watchThroughDao().getLatest(id)!!
            assertEquals(run.id, db.watchLogDao().observeForEpisode(e.id).first().single().watchThroughId)
        }

    @Test
    fun removable_onlyLatest_notOne_noViewings() =
        runTest {
            val id = show()
            val one = db.watchThroughDao().getLatest(id)!!
            assertEquals(0, db.watchThroughDao().deleteIfRemovable(one.id), "#1 never goes")
            val two = WatchThroughEntity(newId(), id, 2)
            db.watchThroughDao().insert(two)
            assertEquals(1, db.watchThroughDao().deleteIfRemovable(two.id))
        }
}
```

- [ ] **Step 7: Amend the AGENTS.md §8 v8 row** to: `MIGRATION_7_8` — `read_throughs`; sessions grouped by read; a book's status and finish date move onto its read (#230); `watch_throughs`, episode viewings grouped by watch-through, film viewings rated; a show's abandoned flag moves onto its watch-through (#231).

- [ ] **Step 8: Run the check, view the schema diff, and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest ktlintCheck`. Expected: PASS. `git diff shared/schemas` shows only v8 changing. Commit: `Watch-throughs: table, viewing columns and migration (#231)`.

---

### Task 2: Ticks follow the latest run; Watch again, Remove, Abandon, rate

**Files:**
- Modify:
  - `shared/src/commonMain/.../core/database/dao/TVWriteDao.kt` (the "Watched state and viewings" section)
  - `.../core/database/dao/ImportWriteDao.kt` (`recomputeEpisodeWatchedAt`, `viewingCountAt`)
  - `.../features/media/data/MediaWithDetails.kt` (`TVShow.runs`)
  - `.../features/tv/data/TVShowRepository.kt` (observers attach runs; run actions)
  - `.../core/database/MediaRepository.kt:99` (attach runs)
  - `.../ui/LibraryStatusFilter.kt` (`ofShow` takes `abandoned: Boolean`)
  - `.../ui/ShowsFinished.kt`, `.../core/database/dao/StatsDao.kt` (`observeShowStates`), `.../core/database/dao/ShowStateRow.kt`
  - `.../ui/TVShowDetailViewModel.kt` (`isAbandoned` from the run; `setAbandoned` through the run)
  - `.../core/di/AppContainer.kt` (or wherever `readThroughRepository` is built): `watchThroughRepository`
- Create: `shared/src/commonMain/.../features/tv/data/WatchThroughRepository.kt`
- Test:
  - `shared/src/jvmTest/.../features/tv/data/WatchThroughRepositoryTest.kt` (new)
  - `WatchHistoryDaoTest.kt`, `UntickRestoreTest.kt` (extended)
  - `commonTest/.../ui/LibraryStatusFilterTest.kt`, `ShowsFinishedTest` (wherever `countFinishedShows` is tested)

**Interfaces:**
- Consumes: Task 1's DAO and `TVWriteDao.latestRunId`.
- Produces:
  - `MediaWithDetails.TVShow(item, details, runs: List<WatchThroughEntity> = emptyList())`, with `latestRun: WatchThroughEntity?`, `isAbandoned: Boolean` (latest run's) and `rating: TitleRating?`
  - `WatchThroughRepository(db)`:
    - `watchAgain(mediaId): Resource<WatchThroughEntity>`
    - `removeRun(runId): Resource<Unit>`
    - `setAbandoned(mediaId, Boolean): Resource<Unit>`
    - `setRating(runId, Int?): Resource<Unit>`
    - `observeRuns(mediaId): Flow<List<WatchThroughEntity>>`
  - `LibraryStatusFilter.ofShow(abandoned: Boolean, tvProgress, airingStatus)`
  - `ShowStateRow(mediaId, airingStatus, abandoned: Boolean, finishedEarlierRun: Boolean)`

- [ ] **Step 1: Write the failing DAO tests** in `WatchHistoryDaoTest` (reuse its `season`, `dao`, `viewings`, `watchedAt`, and the times `t0..t3`):

```kotlin
    private suspend fun runs(mediaId: String) = db.watchThroughDao().getForMedia(mediaId)

    @Test
    fun watchAgain_clearsTicks_andRemoveRestoresThem() =
        runTest {
            val (e1, e2) = season(2)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1, e2.id to t2))
            val two = dao.startNextRun(e1.mediaId, newId())
            assertEquals(listOf(null, null), listOf(watchedAt(e1.id), watchedAt(e2.id)), "run #2 starts empty")
            assertEquals(2, viewings(e1.id).size + viewings(e2.id).size, "run #1's history is kept")
            assertTrue(dao.removeRun(two))
            assertEquals(listOf(t1, t2), listOf(watchedAt(e1.id), watchedAt(e2.id)), "ticks come back exactly")
        }

    @Test
    fun untickInRunTwo_keepsRunOneViewings() =
        runTest {
            val (e1) = season(1)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t2))
            val removed = dao.clearEpisodeReturningPrevious(e1.id)
            assertEquals(listOf(t2), removed.map { it.watchedAt })
            assertEquals(listOf(t1), viewings(e1.id).map { it.watchedAt })
            assertEquals(null, watchedAt(e1.id))
        }

    @Test
    fun deletingAnOlderRunsViewing_leavesTicksAlone() =
        runTest {
            val (e1) = season(1)
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            val old = viewings(e1.id).single()
            dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t2))
            dao.deleteViewing(old.id)
            assertEquals(t2, watchedAt(e1.id))
        }

    @Test
    fun restoreAll_afterItsRunWasRemoved_skipsQuietly() =
        runTest {
            val (e1) = season(1)
            val two = dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodesWatchedAt(mapOf(e1.id to t1))
            val previous = dao.clearEpisodeReturningPrevious(e1.id)
            assertTrue(dao.removeRun(two))
            assertEquals(0, dao.restoreAll(previous))
            assertEquals(null, watchedAt(e1.id))
        }

    @Test
    fun backfill_writesIntoTheLatestRun() =
        runTest {
            val (e1) = season(1)
            val two = dao.startNextRun(e1.mediaId, newId())
            dao.markEpisodeWatchedIfUnwatched(e1.id, t1) // watched with no viewing, as an old library can be
            dao.ensureViewingsForWatchedEpisodes()
            assertEquals(two, viewings(e1.id).single().watchThroughId)
        }
```

Also change `assertInvariant` to the latest run: `assertEquals(viewings(id).filter { it.watchThroughId == latestRun(mediaId) }.maxOfOrNull { it.watchedAt }, …)`. Pass the show's id from each caller; `season()` returns episodes carrying `mediaId`.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests "*WatchHistoryDaoTest*"`. Expected: compile FAIL (`startNextRun`, `removeRun` missing).

- [ ] **Step 3: Scope the viewing transactions to the latest run.** In `TVWriteDao`, update the section comment to "latest viewing *in the show's latest watch-through*" and change:

```kotlin
    /** Re-derives `watchedAt` from the episode's viewings in its show's latest run, or `null` with none (#231). */
    @Query(
        "UPDATE episodes SET watchedAt = (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.episodeId = :episodeId " +
            "AND l.watchThroughId = (SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId " +
            "ORDER BY w.number DESC LIMIT 1)) WHERE id = :episodeId",
    )
    suspend fun recomputeWatchedAt(episodeId: String): Int

    /** [recomputeWatchedAt] for every episode of a show: after Watch again or Remove this watch-through. */
    @Query(
        "UPDATE episodes SET watchedAt = (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.episodeId = episodes.id " +
            "AND l.watchThroughId = (SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId " +
            "ORDER BY w.number DESC LIMIT 1)) WHERE mediaId = :mediaId",
    )
    suspend fun recomputeWatchedAtForShow(mediaId: String): Int

    @Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId AND watchThroughId = :runId")
    suspend fun viewingsInRun(
        episodeId: String,
        runId: String,
    ): List<WatchLogEntity>

    @Query("DELETE FROM watch_logs WHERE episodeId = :episodeId AND watchThroughId = :runId")
    suspend fun deleteViewingsInRun(
        episodeId: String,
        runId: String,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_throughs WHERE id = :runId")
    suspend fun runExists(runId: String): Int

    @Query("SELECT COALESCE(MAX(number), 0) FROM watch_throughs WHERE mediaId = :mediaId")
    suspend fun maxRunNumber(mediaId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: WatchThroughEntity)

    @Query(
        "DELETE FROM watch_throughs WHERE id = :id AND number > 1 " +
            "AND number = (SELECT MAX(w.number) FROM watch_throughs w WHERE w.mediaId = watch_throughs.mediaId) " +
            "AND NOT EXISTS (SELECT 1 FROM watch_logs l WHERE l.watchThroughId = :id)",
    )
    suspend fun deleteRunIfRemovable(id: String): Int

    @Query("SELECT mediaId FROM watch_throughs WHERE id = :id")
    suspend fun runMediaId(id: String): String?

    /**
     * Watch again (#231): run *n+1*, and every episode unticked in the same transaction. A second call
     * racing the first fails on the unique (mediaId, number) index, which the repository reports.
     *
     * @return the new run's id.
     */
    @Transaction
    suspend fun startNextRun(
        mediaId: String,
        newRunId: String,
    ): String {
        latestRunId(mediaId)
        insertRun(WatchThroughEntity(id = newRunId, mediaId = mediaId, number = maxRunNumber(mediaId) + 1))
        recomputeWatchedAtForShow(mediaId)
        return newRunId
    }

    /** Remove this watch-through (#231): the latest, not #1, no viewings. The previous run's ticks come back. */
    @Transaction
    suspend fun removeRun(runId: String): Boolean {
        val mediaId = runMediaId(runId) ?: return false
        if (deleteRunIfRemovable(runId) == 0) return false
        recomputeWatchedAtForShow(mediaId)
        return true
    }
```

Then:
- **`clearEpisodeReturningPrevious`:** `val episode = getByIds(listOf(episodeId)).singleOrNull() ?: return emptyList(); val run = latestRunId(episode.mediaId); val previous = viewingsInRun(episodeId, run); deleteViewingsInRun(episodeId, run); recomputeWatchedAt(episodeId); return previous`. This replaces `clearWatchedAtRow`, which now has no caller and is deleted.
- **`restoreAll`:**
  - skip the episode when `episode.watchedAt != null`;
  - skip it when any of its logs' runs is gone (`logs.any { it.watchThroughId != null && runExists(it.watchThroughId) == 0 }`);
  - skip it when the latest run already has a viewing of it;
  - otherwise insert and recompute.
- **`restoreViewing`:** return `false` when `log.watchThroughId` is set and `runExists(...) == 0`.
- **`watchedWithoutViewings`:** becomes "watched with no viewing in the latest run":
  `"SELECT * FROM episodes WHERE watchedAt IS NOT NULL AND NOT EXISTS (SELECT 1 FROM watch_logs l WHERE l.episodeId = episodes.id AND l.watchThroughId = (SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId ORDER BY w.number DESC LIMIT 1))"`
- **`ImportWriteDao`:** `recomputeEpisodeWatchedAt` gets the same latest-run SQL as `recomputeWatchedAt`, and `viewingCountAt` adds `AND watchThroughId = (latest run subquery)`.

- [ ] **Step 4: Run the DAO tests.** Run: `./gradlew :shared:jvmTest --tests "*WatchHistoryDaoTest*" --tests "*UntickRestoreTest*"`. Expected: PASS. Then sabotage: drop `AND l.watchThroughId = …` from `recomputeWatchedAt`, and expect `watchAgain_clearsTicks…` and `deletingAnOlderRunsViewing…` to fail. Restore it.

- [ ] **Step 5: Write the failing repository tests** in `WatchThroughRepositoryTest` (jvmTest):
  - `watchAgain_onlyWhenLatestIsCompleteOrAbandoned`:
    - one episode unticked: `watchAgain` returns `Resource.Error`;
    - tick it: Success, number 2;
    - abandon a fresh show with unticked episodes: `watchAgain` succeeds.
  - `watchAgain_twice_createsOneRun`: complete the show, then `coroutineScope { listOf(async { repo.watchAgain(id) }, async { repo.watchAgain(id) }).awaitAll() }`. One Success, one Error, and `getForMedia(id).size == 2`. Use `StandardTestDispatcher` per AGENTS.md §7 so both calls are pending before `runCurrent()`.
  - `removeRun_refusedForOne_forRunWithViewings_andForOlderRun`: three Errors, then one Success on an empty #2.
  - `setAbandoned_actsOnTheLatestRunOnly`: run #1 complete, Watch again, abandon. #1 is not abandoned and #2 is.
  - `setRating_validates`: `setRating(run, 0)` and `(run, 11)` are Errors; `(run, 7)` stores 7; `(run, null)` clears.

  "Complete" means every episode counted by `TVProgressCounts.of(mediaId, episodes).counted(settings.includeSpecials)` is ticked. The repository takes `includeSpecials: suspend () -> Boolean` from `SettingsRepository::getIncludeSpecials`. Construct it as `WatchThroughRepository(db, includeSpecials = { false })` in tests.

- [ ] **Step 6: Implement `WatchThroughRepository`:**

```kotlin
package com.hub.media.features.tv.data

/** A show's watch-throughs (#231): Watch again, Remove this watch-through, Abandon/Resume, and ratings. */
public class WatchThroughRepository(
    private val db: AppDatabase,
    private val includeSpecials: suspend () -> Boolean,
    private val logger: Logger = AppLogger,
) {
    public fun observeRuns(mediaId: String): Flow<List<WatchThroughEntity>> = db.watchThroughDao().observeForMedia(mediaId)

    /** Run *n+1*, offered once the latest is complete or abandoned. */
    public suspend fun watchAgain(mediaId: String): Resource<WatchThroughEntity> =
        logger.guardResource(TAG, "Couldn't start another watch-through", { "watchAgain $mediaId" }) {
            val latest = db.watchThroughDao().getLatest(mediaId)
            val episodes = db.episodeDao().getByMediaId(mediaId)
            val counted = TVProgressCounts.of(mediaId, episodes).counted(includeSpecials())
            when {
                latest == null -> Resource.Error("TV show with id=$mediaId not found")
                !latest.abandoned && !isComplete(counted) ->
                    Resource.Error("Finish or abandon this watch-through first")
                else -> {
                    val id = db.tvWriteDao().startNextRun(mediaId, newId())
                    Resource.Success(checkNotNull(db.watchThroughDao().getById(id)))
                }
            }
        }

    public suspend fun removeRun(runId: String): Resource<Unit> =
        logger.guardResource(TAG, "Couldn't remove the watch-through", { "removeRun $runId" }) {
            if (db.tvWriteDao().removeRun(runId)) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Only an empty latest watch-through after #1 can be removed")
            }
        }

    public suspend fun setAbandoned(
        mediaId: String,
        abandoned: Boolean,
    ): Resource<Unit> =
        logger.guardResource(TAG, "Failed to update show status", { "setAbandoned $mediaId" }) {
            val latest = db.watchThroughDao().getLatest(mediaId) ?: return@guardResource Resource.Error("TV show with id=$mediaId not found")
            db.watchThroughDao().setAbandoned(latest.id, abandoned)
            Resource.Success(Unit)
        }

    public suspend fun setRating(
        runId: String,
        halves: Int?,
    ): Resource<Unit> =
        logger.guardResource(TAG, "Failed to save the rating", { "setRating $runId" }) {
            when {
                halves != null && !isValidRating(halves) -> Resource.Error("A rating is half a star to five stars")
                db.watchThroughDao().updateRating(runId, halves) == 0 -> Resource.Error("Watch-through not found")
                else -> Resource.Success(Unit)
            }
        }

    private companion object {
        const val TAG = "WatchThroughRepository"
    }
}
```

Add `isComplete` in `features/tv/data/WatchThroughState.kt` (Task 5 adds the rest of this file). Check the exact parameter order of `guardResource` in `core/util/Cancellation.kt` and match it; the named `tag =` / `failure =` / `logMessage =` form used in `TVShowRepository` is the safe one:

```kotlin
/** Every counted episode ticked in the latest run (#231); a show with none is never complete. */
public fun isComplete(counted: TVProgressRow): Boolean =
    counted.totalEpisodes > 0 && counted.watchedEpisodes >= counted.totalEpisodes
```

Then:
- add `EpisodeDao.getByMediaId(mediaId): List<EpisodeEntity>` if it is missing (`SELECT * FROM episodes WHERE mediaId = :mediaId ORDER BY seasonNumber, episodeNumber`);
- wire `watchThroughRepository` in `AppContainer` beside `readThroughRepository`, passing `settingsRepository::getIncludeSpecials`.

- [ ] **Step 7: Move abandonment readers onto the latest run.**
  - **`MediaWithDetails.TVShow`:** add `val runs: List<WatchThroughEntity> = emptyList()`, `val latestRun get() = runs.maxByOrNull { it.number }`, `val isAbandoned: Boolean get() = latestRun?.abandoned == true`, and `val rating: TitleRating? get() = titleRating(runs.sortedBy { it.number }.map { it.rating })`. That `List<Int?>` overload is Task 4 Step 3's: do that self-contained change (with its `RatingTest`) first, here.
  - **Observers attach runs:**
    - `TVShowRepository.observeAllShowsWithDetails` and `observeShowDetail` combine `db.watchThroughDao().observeAll()` / `observeForMedia(id)` and pass `runs`;
    - `MediaRepository`'s TV branch takes `runsByMediaId` from `observeAll()`.
  - **The library filter:**
    - `LibraryStatusFilter.ofShow(abandoned: Boolean, tvProgress, airingStatus)` replaces `storedStatus == WatchStatus.ABANDONED` with `abandoned`;
    - `matches` passes `media.isAbandoned` (still gated on `media.details != null`);
    - update `LibraryStatusFilterTest` call sites.
  - **`ShowStateRow` and `observeShowStates`:**
    ```kotlin
    data class ShowStateRow(
        val mediaId: String,
        val airingStatus: AiringStatus?,
        /** The latest watch-through's flag (#231). */
        val abandoned: Boolean,
        /** An earlier, non-abandoned watch-through exists: the show was finished before this rewatch began. */
        val finishedEarlierRun: Boolean,
    )
    ```
    ```kotlin
    @Query(
        "SELECT t.mediaId, t.airingStatus, " +
            "COALESCE((SELECT w.abandoned FROM watch_throughs w WHERE w.mediaId = t.mediaId ORDER BY w.number DESC LIMIT 1), 0) AS abandoned, " +
            "EXISTS (SELECT 1 FROM watch_throughs w WHERE w.mediaId = t.mediaId AND w.abandoned = 0 AND w.number < " +
            "(SELECT MAX(x.number) FROM watch_throughs x WHERE x.mediaId = t.mediaId)) AS finishedEarlierRun " +
            "FROM tv_details t",
    )
    fun observeShowStates(): Flow<List<ShowStateRow>>
    ```
  - **`countFinishedShows`:** a show counts when `ofShow(show.abandoned, …) == FINISHED`, **or** when `show.finishedEarlierRun && show.airingStatus != AiringStatus.CONTINUING`. A show being rewatched stays counted, so the existing stats tests keep passing. Add a test `rewatchInProgress_stillCountsAsFinished` beside the existing `countFinishedShows` tests.
  - **`TVShowDetailViewModel`:**
    - it takes `watchThroughRepository: WatchThroughRepository` (update the factory in `app/.../ui/ViewModelFactories.kt` and the VM test constructors);
    - `isAbandoned = show.isAbandoned`;
    - `setAbandoned` calls `watchThroughRepository.setAbandoned(showId, abandoned)`;
    - delete `TVShowRepository.updateWatchStatus` and `TVWriteDao.updateStatusFields` once nothing calls them.

- [ ] **Step 8: Run the check and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest ktlintCheck`. Expected: PASS. Commit: `Watch-throughs: ticks follow the latest run; watch again, remove, abandon (#231)`.

---

### Task 3: Drop `tv_details.status`

**Files:**
- Modify:
  - `TVDetailsEntity.kt` (remove `status` and its KDoc paragraph, and say abandonment lives on `WatchThroughEntity`)
  - `Migrations.kt` (step 6)
  - `TVWriteDao.kt` (`updateShowMetadataAtomically`'s repair comment)
  - `ImportWriteDao.kt`, `LibraryCsvImporter.kt` / `ImportDataUseCase.kt` (any `TVDetailsEntity(status = …)`)
  - every `TVDetailsEntity(` with a `status` argument (about 20, found by grep)
- Test: `MigrationTest` (assert the column is gone)

- [ ] **Step 1: Extend the failing assertion.** In `migrate7To8_everyShowGetsRunOne_carryingAbandoned`, add:

```kotlin
            db.prepare("PRAGMA table_info(tv_details)").use { s ->
                val cols = mutableListOf<String>()
                while (s.step()) cols += s.getText(1)
                assertFalse("status" in cols, "tv_details keeps $cols")
                assertTrue("airingStatus" in cols && "lastAirDate" in cols, "every other column survives: $cols")
            }
```

- [ ] **Step 2: Run it to see it fail.** Run: `./gradlew :shared:jvmTest --tests "*migrate7To8_everyShow*"`. Expected: FAIL (`tv_details keeps [... status ...]`).

- [ ] **Step 3: Add the rebuild** as step 6 of `MIGRATION_7_8`, after the `watch_logs` rebuild:

```kotlin
                // 6. A show's abandoned flag now lives on its watch-through, copied there in step 4.
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tv_details_new` (`mediaId` TEXT NOT NULL, `totalSeasons` INTEGER, " +
                        "`airingStatus` TEXT, `firstAirDate` INTEGER, `lastAirDate` INTEGER, PRIMARY KEY(`mediaId`), " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `tv_details_new` (`mediaId`, `totalSeasons`, `airingStatus`, `firstAirDate`, " +
                        "`lastAirDate`) SELECT `mediaId`, `totalSeasons`, `airingStatus`, `firstAirDate`, `lastAirDate` " +
                        "FROM `tv_details`",
                )
                connection.execSQL("DROP TABLE `tv_details`")
                connection.execSQL("ALTER TABLE `tv_details_new` RENAME TO `tv_details`")
```

Remove `status` from `TVDetailsEntity`. Fix each compile error: drop the `status =` argument; any test asserting a show's `status` asserts `watchThroughDao().getLatest(id)!!.abandoned` instead.

- [ ] **Step 4: Run the check and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug ktlintCheck`. Expected: PASS. `git grep -n "WatchStatus" shared/src/commonMain/kotlin/com/hub/media/features/tv` finds nothing that writes a show status. Commit: `Watch-throughs: tv_details drops status (#231)`.

---

### Task 4: Film viewings carry a rating; `titleRating` takes any ratings

**Files:**
- Modify:
  - `features/books/data/Rating.kt` (generalise `titleRating`, rename `ratedReads` to `rated`)
  - `MovieWriteDao.kt` (`changeStatus`, `addFilmViewing` take `rating: Int?`; `setFilmViewingRating`)
  - `MovieRepository.kt` (`updateWatchStatus(..., rating: Int? = null)`, `addViewing(..., rating: Int? = null)`, `setViewingRating`)
  - `MovieDetailViewModel.kt` (`confirmWatched(watchedWhen, rating)`, `confirmWatchedAgain(watchedWhen, rating)`, `rateViewing(logId, halves)`, `Ready.titleRating`)
  - app: every `ratedReads` reader (`BookReads.kt` `YourRatingRow`)
- Test:
  - `commonTest/.../features/books/data/RatingTest.kt`
  - `jvmTest/.../features/movies/...` (wherever film viewings are tested; grep `addFilmViewing`)
  - `commonTest` or `jvmTest` `MovieDetailViewModelTest`

**Interfaces:**
- Produces:
  - `titleRating(ratings: List<Int?>): TitleRating?`, where the list is oldest first and the last rated is "latest"
  - `TitleRating(averageStars, latestStars, rated: Int)`
  - `MovieDetailUiState.Ready.titleRating: TitleRating?`, derived from `viewings` (newest first, so reverse before calling)

- [ ] **Step 1: Write the failing tests.**

`RatingTest`:

```kotlin
    @Test
    fun titleRating_overAnyList_ignoresUnrated_latestIsTheLastRated() {
        val r = titleRating(listOf(8, null, 10, null))!!
        assertEquals(4.5, r.averageStars)
        assertEquals(5.0, r.latestStars)
        assertEquals(2, r.rated)
        assertNull(titleRating(listOf(null, null)))
    }
```

In the film viewing DAO test (the class holding `addFilmViewing` tests), add:

```kotlin
    @Test
    fun aFilmViewing_keepsItsRating_andRatingIsValidated() =
        runTest {
            val id = addWatchedFilm() // existing helper in that class, or MovieRepository.addMovie(..., status = WATCHED)
            assertIs<Resource.Success<Unit>>(repo.addViewing(id, WatchedWhen.Now, rating = 9))
            assertEquals(9, db.watchLogDao().observeForFilm(id).first().first().rating)
            val log = db.watchLogDao().observeForFilm(id).first().first()
            assertIs<Resource.Error>(repo.setViewingRating(log.id, 11))
            assertIs<Resource.Success<Unit>>(repo.setViewingRating(log.id, null))
            assertNull(db.watchLogDao().observeForFilm(id).first().first().rating)
        }

    @Test
    fun anEpisodeViewing_cannotBeRatedAsAFilm() =
        runTest {
            val show = (TVShowRepository(db).addShow("S", listOf(SeasonQuickFill(1, 1))) as Resource.Success).data
            val e = db.episodeDao().getByMediaIdAndSeason(show, 1).single()
            db.tvWriteDao().markEpisodesWatchedAt(mapOf(e.id to Clock.System.now()))
            val log = db.watchLogDao().observeForEpisode(e.id).first().single()
            assertIs<Resource.Error>(repo.setViewingRating(log.id, 8))
        }
```

`MovieDetailViewModelTest`:
- `confirmWatched_withStars_savesTheRatingOnTheViewing`: request Watched, then `confirmWatched(Now, 7)`, then `uiState.first { it is Ready && it.viewings.firstOrNull()?.rating == 7 }`;
- `rateViewing_changesIt_andTitleRatingAverages`: two viewings rated 6 and 10, then `uiState.first { (it as? Ready)?.titleRating?.averageStars == 4.0 }`.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests "*Rating*" --tests "*MovieDetailViewModel*" --tests "*FilmViewing*"`. Expected: compile FAIL.

- [ ] **Step 3: Implement.**

```kotlin
/** A title's rating from its viewings' ratings, oldest first (#230, #231): average and latest, in stars. */
public fun titleRating(ratings: List<Int?>): TitleRating? {
    val rated = ratings.filterNotNull()
    if (rated.isEmpty()) return null
    return TitleRating(averageStars = rated.average() / 2.0, latestStars = rated.last().toStars(), rated = rated.size)
}

/** [reads]' rating; "latest" is the highest-numbered rated read. */
public fun titleRating(reads: List<ReadThroughEntity>): TitleRating? = titleRating(reads.sortedBy { it.number }.map { it.rating })
```

The two overloads clash on JVM erasure (`List<Int?>` vs `List<ReadThroughEntity>`). Annotate the reads one `@JvmName("titleRatingOfReads")`, or rename it `readsRating` and update its callers (`MediaWithDetails.Book.rating`, `BookDetailUiState`). Pick the rename if `@JvmName` is unavailable in commonMain without `kotlin.jvm.JvmName` (it is available as `kotlin.jvm.JvmName` in common code).

`MovieWriteDao`:
- `filmViewing(mediaId, at, rating: Int? = null)`;
- `changeStatus(mediaId, status, at, rating: Int? = null)` passes `rating` to the viewing it inserts;
- `addFilmViewing(mediaId, at, rating: Int? = null)`;
- add:

```kotlin
    @Query("UPDATE watch_logs SET rating = :rating WHERE id = :logId AND episodeId IS NULL")
    suspend fun setFilmViewingRating(
        logId: String,
        rating: Int?,
    ): Int
```

`MovieRepository.setViewingRating(logId, halves)`:
- Error when `halves != null && !isValidRating(halves)`;
- Error ("Viewing not found") when the DAO returns 0;
- otherwise Success.

`updateWatchStatus` and `addViewing` validate the rating the same way before writing.

`MovieDetailViewModel`:
- `confirmWatched(watchedWhen: WatchedWhen, rating: Int? = null)` and `confirmWatchedAgain(watchedWhen, rating: Int? = null)` pass it through;
- add `fun rateViewing(logId: String, halves: Int?)`, which launches `setViewingRating` and sets `errorMessage` on Error;
- `Ready` gains `val titleRating: TitleRating? get() = titleRating(viewings.reversed().map { it.rating })`.

- [ ] **Step 4: Run the check and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest ktlintCheck`. Expected: PASS. Commit: `Films: each viewing carries a rating (#231)`.

---

### Task 5: The show ViewModel — run state, Watch again, Remove, rate on finish

**Files:**
- Modify:
  - `features/tv/data/WatchThroughState.kt` (from Task 2: add `RunState`, `runState`, `asksToRateRun`)
  - `ui/TVShowDetailViewModel.kt`
  - `core/database/dao/WatchLogDao.kt` (`observeRunSummaries`)
- Test:
  - `commonTest/.../features/tv/data/WatchThroughStateTest.kt` (new)
  - `TVShowDetailViewModelTest` (jvmTest or commonTest, wherever it lives)

**Interfaces:**
- Produces:
  - `enum class RunState { NOT_STARTED, WATCHING, FINISHED, UP_TO_DATE, ABANDONED }`
  - `runState(run: WatchThroughEntity, counted: TVProgressRow, airingStatus: AiringStatus?): RunState`
  - `asksToRateRun(wasComplete: Boolean, nowComplete: Boolean, rating: Int?): Boolean`
  - `data class RunSummary(val runId: String, val firstAt: Instant, val lastAt: Instant, val episodes: Int, val perSeason: Map<Int, Int>)`
  - `TVShowDetailUiState.Ready` gains:
    - `runs: List<WatchThroughEntity>`
    - `latestRunState: RunState`
    - `runSummaries: Map<String, RunSummary>`
    - `ratePromptRunId: String?`
    - derived `latestRun`, `titleRating`, `canWatchAgain: Boolean` (latest is FINISHED, UP_TO_DATE or ABANDONED) and `canRemoveLatestRun: Boolean` (number > 1 and no summary for it)
  - VM methods: `watchAgain()`, `removeRun(runId)`, `rateRun(runId, halves: Int?)`, `dismissRatePrompt()`

- [ ] **Step 1: Write the failing pure tests** (`WatchThroughStateTest`, commonTest):

```kotlin
class WatchThroughStateTest {
    private val run = WatchThroughEntity("r", "s", 1)

    private fun counts(
        watched: Int,
        total: Int,
    ) = TVProgressRow(watchedEpisodes = watched, totalEpisodes = total) // match TVProgressRow's real constructor

    @Test
    fun labels() {
        assertEquals(RunState.NOT_STARTED, runState(run, counts(0, 3), null))
        assertEquals(RunState.WATCHING, runState(run, counts(1, 3), null))
        assertEquals(RunState.FINISHED, runState(run, counts(3, 3), AiringStatus.ENDED))
        assertEquals(RunState.UP_TO_DATE, runState(run, counts(3, 3), AiringStatus.CONTINUING))
        assertEquals(RunState.ABANDONED, runState(run.copy(abandoned = true), counts(3, 3), null))
        assertEquals(RunState.NOT_STARTED, runState(run, counts(0, 0), null), "no episodes is never complete")
    }

    @Test
    fun asksOnce_whenARunCompletes_unrated() {
        assertTrue(asksToRateRun(wasComplete = false, nowComplete = true, rating = null))
        assertFalse(asksToRateRun(wasComplete = true, nowComplete = true, rating = null))
        assertFalse(asksToRateRun(wasComplete = false, nowComplete = true, rating = 8))
        assertFalse(asksToRateRun(wasComplete = false, nowComplete = false, rating = null))
    }
}
```

Before writing `counts`, read `TVProgressRow`'s and `TVProgressCounts.counted`'s real shapes in `EpisodeDao.kt` / `TVProgressCounts.kt` and use their constructor.

- [ ] **Step 2: Run them to see them fail**, then implement:

```kotlin
/** What a watch-through reads as (#231), derived from its ticks like a show's state always was. */
public enum class RunState { NOT_STARTED, WATCHING, FINISHED, UP_TO_DATE, ABANDONED }

public fun runState(
    run: WatchThroughEntity,
    counted: TVProgressRow,
    airingStatus: AiringStatus?,
): RunState =
    when {
        run.abandoned -> RunState.ABANDONED
        isComplete(counted) -> if (airingStatus == AiringStatus.CONTINUING) RunState.UP_TO_DATE else RunState.FINISHED
        counted.watchedEpisodes == 0 -> RunState.NOT_STARTED
        else -> RunState.WATCHING
    }

/** "Rate this watch-through?" opens when an action completes the latest run and it is unrated -- once. */
public fun asksToRateRun(
    wasComplete: Boolean,
    nowComplete: Boolean,
    rating: Int?,
): Boolean = !wasComplete && nowComplete && rating == null
```

- [ ] **Step 3: Add the run summaries query** to `WatchLogDao`, for the folded cards and the date line:

```kotlin
    /** Per run and season: distinct episodes viewed, and the run's first and last viewing (#231). */
    @Query(
        "SELECT l.watchThroughId AS runId, e.seasonNumber AS seasonNumber, COUNT(DISTINCT l.episodeId) AS episodes, " +
            "MIN(l.watchedAt) AS firstAt, MAX(l.watchedAt) AS lastAt FROM watch_logs l " +
            "JOIN episodes e ON e.id = l.episodeId WHERE l.mediaId = :mediaId AND l.watchThroughId IS NOT NULL " +
            "GROUP BY l.watchThroughId, e.seasonNumber",
    )
    fun observeRunSeasonRows(mediaId: String): Flow<List<RunSeasonRow>>
```

Here `RunSeasonRow(runId: String, seasonNumber: Int, episodes: Int, firstAt: Instant, lastAt: Instant)` is a plain projection in `core/database/dao/`. `TVShowRepository.observeRunSummaries(mediaId): Flow<Map<String, RunSummary>>` folds the rows per run:
- `firstAt = min`;
- `lastAt = max`;
- `episodes = sum`;
- `perSeason = seasonNumber -> episodes`.

- [ ] **Step 4: Write the failing VM tests** in `TVShowDetailViewModelTest`, using its existing construction helper plus a real `WatchThroughRepository(db) { false }`:
  - `tickingTheLastEpisode_asksToRateTheRun`: a 2-episode show with e1 ticked; `setEpisodeWatched(e2, true)` then `confirmTick(...)` (as the existing tick tests do); `uiState.first { (it as? Ready)?.ratePromptRunId == run1.id }`.
  - `tickingTheLastEpisode_ofARatedRun_doesNotAsk`: rate run #1 first, finish it, then `runCurrent()`; `ratePromptRunId == null` and `latestRunState == FINISHED`.
  - `abandoning_asksToRate`: `setAbandoned(true)`, then `first { ratePromptRunId != null && latestRunState == ABANDONED }`.
  - `watchAgain_startsRunTwo_withAnEmptyChecklist`: complete the show, then `watchAgain()`, then `first { it.runs.size == 2 && it.watchedEpisodes == 0 }`.
  - `removeRun_bringsTheTicksBack`: after the above, `removeRun(run2.id)`, then `first { it.runs.size == 1 && it.watchedEpisodes == 2 }`.
  - `rateRun_storesIt_andClosesThePrompt`.

- [ ] **Step 5: Implement in `TVShowDetailViewModel`.**
  - **Constructor:** it already takes `watchThroughRepository` from Task 2.
  - **Local state:** add `private val ratePromptRunId = MutableStateFlow<String?>(null)` and `private var watchAgainInFlight = false`.
  - **Base flow:** the five-source inner `combine` is full, so add `tvShowRepository.observeRunSummaries(showId)` and `ratePromptRunId` to the `Overlay` (it is a data class; add fields `summaries` and `ratePrompt`, combining them into the `combine(openViewings, observeViewingCounts)` pair as a nested `combine`). `show.runs` arrives with `observeShowDetail`.
  - **`Ready` fields, set in the outer combine:**
    - `runs = show.runs`;
    - `latestRunState = show.latestRun?.let { runState(it, counted, show.details?.airingStatus) } ?: RunState.NOT_STARTED`, using the `counted` the outer block already computes;
    - `runSummaries = extra.summaries`;
    - `ratePromptRunId = extra.ratePrompt`.
  - **Prompt on completion:**
    - private `suspend fun latestComplete(): Boolean` reads `tvShowRepository.observeEpisodes(showId).first()` and `settingsRepository.getIncludeSpecials()`, then returns `isComplete(TVProgressCounts.of(showId, eps).counted(inc))`;
    - in `confirmTick` (and in Mark season watched's confirm, if it is a separate function), capture `val was = latestComplete()` before the write. After a successful write, `val run = watchThroughRepository.observeRuns(showId).first().maxBy { it.number }` and `if (asksToRateRun(was, latestComplete(), run.rating)) ratePromptRunId.value = run.id`.
  - **`setAbandoned(true)`:** after Success, if the latest run is unrated, `ratePromptRunId.value = it.id`.
  - **`watchAgain()`:** `if (watchAgainInFlight) return; watchAgainInFlight = true`, launch `watchThroughRepository.watchAgain(showId)`, set `errorMessage` on Error, and reset the flag in `finally`.
  - **`removeRun(runId)`, `rateRun(runId, halves)`:** call the repository; `rateRun` clears `ratePromptRunId`. `dismissRatePrompt()` clears it.
  - **The episode sheet's labels:** `OpenEpisode` gains `runNumbers: Map<String, Int>` (runId → number), filled from `show.runs`. The screen labels a viewing when `runs.size > 1`.

- [ ] **Step 6: Run the check, sabotage, and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest ktlintCheck`. Expected: PASS. Sabotage `asksToRateRun` to ignore `wasComplete`; `tickingTheLastEpisode_ofARatedRun…` must still pass, but a re-tick test (untick and re-tick a finished, unrated run without completing the run anew) must fail. Add that test if none fails. Commit: `Watch-throughs: the show screen's state and actions (#231)`.

---

### Task 6: Export and import carry watch-throughs and film ratings (CSV 8)

**Files:**
- Create:
  - `shared/src/commonMain/.../features/portability/csv/WatchThroughCsvExporter.kt`
  - `.../csv/WatchThroughCsvImporter.kt`
- Modify:
  - `csv/CsvSchemaVersion.kt` (7 → 8, KDoc entry)
  - `csv/WatchLogCsvExporter.kt` (`HEADER` + `watch_through_number`, `rating`; `HEADER_V7 = HEADER.dropLast(2)`; `export(viewings, runNumberById: Map<String, Int>)`)
  - `csv/WatchLogCsvImporter.kt` (`ParsedWatchLogRow.runNumber: Int?`, `rating: Int?`; `padLegacyV7Row`)
  - `csv/ExportArchive.kt` (`WATCH_THROUGHS`)
  - `domain/ExportDataUseCase.kt` / `CsvExportBundle` (`watchThroughsCsv`)
  - `domain/ExportArchiveUseCase.kt`, `domain/ImportArchiveUseCase.kt` (archive entry; lone-file refusal)
  - `domain/ImportDataUseCase.kt` and the `ImportUseCase` interface (`watchThroughsCsv: String? = null`), `FakeImportDataUseCase`
  - `ImportRowSource` (`WATCH_THROUGH`)
  - `core/database/dao/ImportWriteDao.kt` (`ImportDetails.TVShow.runOne`, `runUpserts`)
- Test:
  - `commonTest/.../csv/WatchThroughCsvTest.kt` (new)
  - `jvmTest/.../domain/ImportDataUseCaseTest.kt`
  - `jvmTest/.../domain/ZipImportExportTest.kt`

**Interfaces:**
- Produces:
  - `WatchThroughCsvExporter.HEADER = listOf(CSV_SCHEMA_VERSION_COLUMN, "media_id", "number", "abandoned", "rating")`
  - `WatchThroughCsvExporter.export(runs: List<WatchThroughEntity>): String`, with ratings written as stars (`formatStarsCsv` from `ReadThroughCsvExporter`; reuse it)
  - `ParsedWatchThrough(mediaId, number, abandoned, rating: Int?)`
  - `WatchThroughRowParseResult.Parsed / Rejected`
  - `ImportDetails.TVShow(details, runOne: WatchThroughEntity? = null)`

- [ ] **Step 1: Write the failing CSV tests** (`WatchThroughCsvTest`, the same shape as `ReadThroughCsvTest`):
  - a run round-trips, abandoned and rated 9, with "4.5" in the file;
  - off-grid ratings (`4.3`, `0`, `6`, `abc`, `NaN`, `Infinity`) are rejected with "rating" in the reason;
  - number 0 or blank, `abandoned` not `true`/`false`, and a blank media_id are rejected.

  In the `WatchLogCsv` tests:
  - a film viewing round-trips its rating;
  - an episode viewing round-trips its run number;
  - an episode row with a rating is rejected ("an episode is rated per watch-through");
  - a v7-width row parsed through `padLegacyV7Row` has `runNumber = null` and `rating = null`.

- [ ] **Step 2: Write the failing import tests** in `ImportDataUseCaseTest`:
  - `v8RoundTrip_keepsAbandonedRun_andFilmRatings`:
    - **source library:** show A completed in run #1 rated 8, Watch again, one tick in #2, #2 abandoned; film F with viewings rated 6 and 10;
    - **round trip:** export with `ExportDataUseCase`, import into a fresh db;
    - **assert** runs `[1:false:8, 2:true:null]`, each episode viewing's run number, ticks equal to run #2's, and F's viewing ratings.
  - `oldArchive_eachShowGetsRunOne`: the v7 fixture (an existing v7 test fixture, or a v8 export with the watch columns stripped and the version column set to 7) imports with every episode viewing in run #1 and no abandoned run.
  - `unknownRunNumber_isRejected`: a viewing row with `watch_through_number = 3` and no runs file is rejected with a reason naming the run.
  - `mergeFillsAnEmptyRunRating_neverOverwrites` and `replaceOverwritesRunRating`, plus `skipLeavesRunsAlone`.

  In `ZipImportExportTest`, add `aLoneWatchThroughsCsv_isRefusedWithAHint`, copying `aLoneReadsCsv_isRefusedWithAHint` with `.watchThroughsCsv`.

- [ ] **Step 3: Run them to see them fail.** Run: `./gradlew :shared:jvmTest --tests "*ImportDataUseCaseTest*" --tests "*ZipImportExportTest*" :shared:testDebugUnitTest --tests "*WatchThroughCsv*" --tests "*WatchLogCsv*"`. Expected: compile FAIL.

- [ ] **Step 4: Implement the files,** following #230's read-throughs files line for line.

  **The CSV classes:**
  - `WatchThroughCsvImporter.parseRow` rejects as above. `abandoned` parses `true`/`false` case-insensitively.
  - `WatchLogCsvImporter.buildRow` adds:
    ```kotlin
            runNumber = row[COL_RUN_NUMBER].ifBlank { null }?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: reject("watch_through_number must be 1 or more") },
            rating = row[COL_RATING].ifBlank { null }?.let { it.toDoubleOrNull()?.let(::starsToHalves) ?: reject("rating '$it' is not a half-star value from 0.5 to 5") },
    ```
    After building, it rejects `episodeId != null && rating != null` and `episodeId == null && runNumber != null`.
  - `padLegacyV7Row(row) = row + "" + ""`.

  **Wiring:**
  - `ImportDataUseCase` reads the watch-logs table with `legacyHeaders = mapOf(WatchLogCsvExporter.HEADER_V7 to WatchLogCsvImporter::padLegacyV7Row)`.
  - `ExportDataUseCase` builds `runNumberById` from `watchThroughDao().getAll()` and writes `watchThroughsCsv`.

  **Planning the runs** in `ImportDataUseCase`, after the reads block and mirroring it:
  1. `existingRunsByKey` from `existingMedia.filterIsInstance<MediaWithDetails.TVShow>().flatMap { it.runs }`.
  2. Inserted shows get `runOne = WatchThroughEntity(newId(), mediaId, 1)`, set in `buildInsert` for `ImportDetails.TVShow`.
  3. `runIdByKey` = existing runs, plus inserted shows' `runOne`, plus new rows from the runs file (`getOrPut { newId() }`).
  4. The duplicate policy on an existing run:
     - SKIP: nothing;
     - REPLACE: the file's `abandoned` and `rating`;
     - MERGE: `rating = existing.rating ?: row.rating`, `abandoned` kept.
  5. Each viewing row:
     - film: carries `rating`;
     - episode: `watchThroughId = runIdByKey[resolvedMediaId to (row.runNumber ?: 1)]`;
     - when that is null and `runNumber > 1`, reject `"watch-through ${row.runNumber} of this show is not in your library or the watch-throughs file -- viewing skipped"`;
     - when it is null and the number is 1, leave the field null for the DAO's `latestRunId` fill;
     - for an old file (`runNumber == null`), use run #1, not the latest.
  6. Dedup per episode compares `(watchedAt, watchThroughId)`.

  **`ImportWriteDao.importAtomically(..., runUpserts: List<WatchThroughEntity> = emptyList())`:**
  - the TV insert branch writes `details.runOne` when present, else the `insertFirstRunIfMissing` fallback from Task 1;
  - `runUpserts` are applied after media (update by `(mediaId, number)`, else insert, as `upsertRead` does) and before viewings;
  - after the viewings, every touched show gets `recomputeWatchedAtForShow`, so ticks follow the latest run the file defines. Add that DAO query here, copied from Task 2's.

  **`ImportArchiveUseCase`'s lone-CSV check:** a lone CSV whose column 1 is `media_id` and column 2 is `number` is the runs file (as with reads); it is refused with `NOT_LIBRARY_CSV`. The existing check (column 1 `media_id` **and** column 2 `type`) already refuses it. Confirm with the new test rather than adding code.

- [ ] **Step 5: Run the check and commit.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest ktlintCheck`. Expected: PASS. Sabotage: make old rows use the latest run instead of #1; `oldArchive…` must fail. Commit: `Watch-throughs: CSV 8 exports and imports runs and film ratings (#231)`.

---

### Task 7: Shared rating pieces move to `ui/components` (books unchanged)

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/RatingRows.kt`
- Modify:
  - `ui/screens/BookReads.kt` (keeps `BookReadActions`, `readTitle`, `readDateLine` and the book cards, which call the shared pieces)
  - `ui/screens/BookDetailsTab.kt`, `BookDetailScreen.kt`
  - `res/values/strings.xml`
- Test: `app/src/test/.../ui/screens/BookDetailScreenTest.kt` (unchanged and must pass); the book goldens (must not change)

**Interfaces:**
- Produces:
  - `@Composable fun YourRatingRow(rating: TitleRating?, @PluralsRes averageOf: Int)`, where `averageOf` is a plural with `%1$s average of %2$d reads · latest %3$s`
  - `@Composable fun RateDialog(title: String, body: String, initial: Int?, onSave: (Int) -> Unit, onSkip: () -> Unit)`
  - `@Composable fun RunRatingRow(halves: Int?, onRate: (Int?) -> Unit)` ("Your rating" plus 28dp stars)
  - plurals:
    - `rating_average_of_reads`: replaces the string, `one`/`other`, same text;
    - `rating_average_of_watch_throughs`: "%1$s average of %2$d watch-throughs · latest %3$s";
    - `rating_average_of_viewings`: "%1$s average of %2$d viewings · latest %3$s".

- [ ] **Step 1: Move the code without changing what it draws.**
  - `YourRatingRow` moves as is, reading `rating.rated` and `pluralStringResource(averageOf, rating.rated, …)`.
  - `RateReadDialog` becomes `RateDialog`, with the title and body passed in. The book call site passes `stringResource(R.string.rate_read_title)` and `stringResource(R.string.rate_read_body, bookTitle, read.number)`, and keeps `TestTags.BookDetail.RATE_STARS` by taking `starsTag: String` as a parameter.
  - `ReadHeaderCard`'s inner "Your rating" row becomes `RunRatingRow`. `formatAverage` moves with `YourRatingRow`.

- [ ] **Step 2: Verify the books are untouched.** Run: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug`. Expected: PASS with **no** golden diffs. A diff means the move changed layout; fix the code, never re-record.

- [ ] **Step 3: Commit:** `UI: rating row and dialog shared across books, films and shows (#231)`.

---

### Task 8: Show screen — your rating, runs, Watch again, Rate this watch-through

**Files:**
- Create: `app/src/main/java/.../ui/screens/ShowRuns.kt` (`ShowRunActions`, `LatestRunCard`, `FoldedRunCard`)
- Modify:
  - `ui/screens/TVShowDetailScreen.kt` (route maps the VM; stateless screen takes `runActions: ShowRunActions`; header `yourRating`; status chip; cards around the seasons; rate dialog; the episode sheet's run labels)
  - `ui/TestTags.kt` (`TVShowDetail.RATE_STARS`, `TVShowDetail.LATEST_RUN`)
  - `res/values/strings.xml`
  - every `TVShowDetailScreen(` call in `app/src/test` and `app/src/androidTest` (pass `runActions`)
- Test: `app/src/test/.../ui/screens/TVShowDetailScreenTest.kt`

**Interfaces:**
- Consumes: Task 5's `Ready` fields and VM methods, and Task 7's components.
- Produces: `data class ShowRunActions(val onWatchAgain: () -> Unit, val onRemoveRun: (String) -> Unit, val onRateRun: (String, Int?) -> Unit, val onDismissRatePrompt: () -> Unit)`

**Strings:**
- `run_number_state` "Watch-through #%1$d · %2$s"
- `run_state_not_started` "Not started", `run_state_watching` "Watching", `run_state_finished` "Finished", `run_state_up_to_date` "Up to date", `run_state_abandoned` "Abandoned"
- `run_started_on` "Started %1$s", `run_finished_on` "Finished %1$s"
- `run_watch_again` "Watch again", `run_remove` "Remove this watch-through"
- `run_menu` "Options for watch-through %1$d", `run_expand` "Show watch-through %1$d", `run_collapse` "Hide watch-through %1$d"
- plural `run_episodes_summary` "%1$d episode" / "%1$d episodes"
- `run_season_summary` "Season %1$d: %2$d"
- `rate_run_title` "Rate this watch-through?", `rate_run_body` "%1$s, watch-through #%2$d"
- `viewing_in_run` "Watch-through #%1$d"

- [ ] **Step 1: Write the failing Robolectric tests** in `TVShowDetailScreenTest`, using its existing `show(...)` helper extended with `runs`, `latestRunState`, `runSummaries` and `ratePromptRunId`:
  - **`header_showsYourRating_averageOfWatchThroughs`:** runs rated 8 and 10 → text "4.5 average of 2 watch-throughs · latest 5".
  - **`latestRunCard_showsItsStateAndStars`:**
    - one run, state WATCHING → "Watch-through #1 · Watching";
    - tapping `RATE`-tagged stars at 9.5/10 → `onRateRun(run1, 10)` recorded.
  - **`olderRuns_areFolded_andExpandToSeasonSummary`:**
    - two runs, #1 summarised `perSeason = {1: 7}` → "Watch-through #1 · Finished …" is shown and "Season 1: 7" is not;
    - after clicking the card's expand action, "Season 1: 7" is displayed.
    - Scroll first: the LazyColumn lesson from #230.
  - **`watchAgain_onTheChip_whenFinished_absentWhileWatching`**: two states, asserting the action exists and does not exist (`assertDoesNotExist` after scrolling to the chip).
  - **`removeThisWatchThrough_onlyInTheMenuOfAnEmptyLatestRun`.**
  - **`ratePrompt_opensAndSaves`:** `ratePromptRunId = run2`, then tap stars, then Save → `onRateRun(run2, n)`.

- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :app:testDebugUnitTest --tests "*TVShowDetailScreenTest*"`. Expected: compile FAIL.

- [ ] **Step 3: Implement.**
  - **Header:** `DetailHeader(yourRating = { YourRatingRow(ready.titleRating, R.plurals.rating_average_of_watch_throughs) })`.
  - **Status chip** (`showStatusControl`):
    - the label comes from `latestRunState` via the new strings;
    - the action is Abandon/Resume as now;
    - add `menuAction = DetailStatus...` in the shape #230 used for "Read again", shown when `canWatchAgain`. If `DetailStatus.ReadOnly` has no second action slot, add `secondaryAction: StatusAction? = null` to it and render it beside the first, keeping the book and film call sites unchanged.
  - **Above the seasons:**
    - `LatestRunCard(run, state, summary, canRemove, onRate, onRemove)` tagged `TestTags.TVShowDetail.LATEST_RUN`;
    - its date line: `run_finished_on(lastAt)` when FINISHED or UP_TO_DATE, else `run_started_on(firstAt)`, else nothing.
  - **Below the seasons:**
    - for each older run, newest first, a `FoldedRunCard`;
    - its title is `run_number_state` with "Finished" or "Abandoned", plus " · <date of lastAt>";
    - stars at 16dp, then `run_episodes_summary`;
    - expansion is tracked by `rememberSaveable` of a `Set<String>`, as `expandedReadIds` does, and an expanded card lists `run_season_summary` lines.
  - **The dialog:** `ratePromptRunId?.let { id -> RateDialog(stringResource(R.string.rate_run_title), stringResource(R.string.rate_run_body, title, number), initial = run.rating, onSave = { runActions.onRateRun(id, it) }, onSkip = runActions.onDismissRatePrompt, starsTag = TestTags.TVShowDetail.RATE_STARS) }`.
  - **The episode sheet:** each viewing row appends " · Watch-through #n" when `runNumbers.size > 1`.

- [ ] **Step 4: Run the lanes.** Run: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug`. The show goldens will now differ (new header row and run card). Record in Task 10, not here. Until then, confirm the diff is only the show goldens, then continue. Commit: `Shows: watch-throughs on the detail screen (#231)`.

---

### Task 9: Film screen — your rating, stars in the Watched dialog and History

**Files:**
- Modify:
  - `ui/components/FilmWatchedDialog.kt` (`rateable: Boolean = false`; `onConfirm: (WatchedWhen, Int?) -> Unit`)
  - `ui/screens/MovieDetailScreen.kt` (header row; dialogs pass `rateable = true`; route maps `confirmWatched`/`confirmWatchedAgain`)
  - `ui/screens/MovieHistorySection.kt` (stars per row, `onRateViewing`)
  - `AddMovieScreen.kt` and `EditMovieScreen.kt` (adapt to the new lambda and ignore the rating; their dialogs stay without stars)
  - `ui/TestTags.kt` (`MovieDetail.WATCHED_STARS`)
- Test: `app/src/test/.../ui/screens/MovieDetailScreenTest.kt`, plus a dialog test in the same file

- [ ] **Step 1: Write the failing tests:**
  - `watchedDialog_withStars_passesTheRating`: tap stars at 7.5/10 (→ 8), then Now → `onConfirm(Now, 8)`.
  - `watchedDialog_withoutStars_passesNull`.
  - `watchedDialog_pickADate_keepsTheChosenStars`: tap stars, then Pick a date, then confirm the picker → the rating is still passed. The date picker is a dialog *without* a text field, so it runs here; if it hangs, move just this test to `androidTest` with a comment, per AGENTS.md §7.
  - `history_rowStars_rateThatViewing`.
  - `header_showsAverageOfViewings`.

- [ ] **Step 2: Run them to see them fail**, then implement:
  - **The dialog:** `var halves by rememberSaveable { mutableStateOf<Int?>(null) }`. When `rateable`, the `text` slot shows `RatingStars(halves, starSize = 40.dp, onRate = { halves = it }, modifier = Modifier.testTag(TestTags.MovieDetail.WATCHED_STARS))` with a `formatStars` readout. Both confirm paths call `onConfirm(when, halves)`.
  - **History rows:** `RatingStars(log.rating, starSize = 20.dp, onRate = { onRateViewing(log.id, it) })`.
  - **The header:** `YourRatingRow(ready.titleRating, R.plurals.rating_average_of_viewings)`.

- [ ] **Step 3: Run the lanes and commit.** Run: `./gradlew :app:testDebugUnitTest ktlintCheck :app:lintDebug :app:assembleDebug`. Expected: PASS (goldens are recorded in Task 10). Commit: `Films: rate each viewing (#231)`.

---

### Task 10: Goldens, docs, changelog, device

**Files:**
- `app/src/test/.../ui/goldens/TVShowDetailScreenGoldenTest.kt` (a two-run fixture), `MovieDetailScreenGoldenTest.kt` (rated viewings)
- `app/src/test/screenshots/*.png`
- `CHANGELOG.md`, `ROADMAP.md`

- [ ] **Step 1: Add the golden fixtures.**
  - **`tv-show-detail-runs`:**
    - two runs: #1 Finished, rated 9, summary 7 episodes; #2 Watching, unrated, 3 ticks;
    - fixture consistency: #2's ticks equal the checklist's ticked count, and #1's summary equals its episode count;
    - paired assertion: "Watch-through #2 · Watching" is displayed.
  - **`movie-detail-rated`:** two viewings rated 8 and 10; assertion "4.5 average of 2 viewings · latest 5".

- [ ] **Step 2: Record, and look at every changed PNG.** Run: `./gradlew :app:recordRoborazziDebug`, then `git status app/src/test/screenshots`. Open each changed PNG. Only show and film images may change; **no book image**. Commit the PNGs alone: `Goldens: watch-throughs and rated film viewings (#231)`.

- [ ] **Step 3: Update the docs.**
  - **CHANGELOG `[Unreleased]`:**
    - *Added:* "Re-watch a show: start another watch-through, with its own ticks and rating. Rate each film viewing. A film's and a show's header shows your average and latest rating."
    - *Internal:* "Schema v8 extended: `watch_throughs`, `watch_logs.watchThroughId` and `rating`; `tv_details.status` removed. CSV schema 8."
  - **ROADMAP Task 10:** books, films and shows are done (#230, #231); rating stats are #232.
  - Commit: `Docs: watch-throughs in CHANGELOG, ROADMAP (#231)`.

- [ ] **Step 4: Run the full gate.** Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`. Expected: PASS.

- [ ] **Step 5: Run the device steps, after the owner says the phone is free.**
  1. Back up the phone's library: Settings → Export, or `adb exec-out run-as … cat databases/…`. Copy it off the device.
  2. `adb devices`, then `./gradlew :app:connectedDebugAndroidTest`. It uninstalls the app, which clears the v8-from-#230 database that this v8 cannot open.
  3. `./gradlew :app:seedDebugDevice`.
  4. Hand check:
     - finish a show, so "Rate this watch-through?" appears, and rate it;
     - Watch again: the checklist is empty;
     - tick through, rate #2, and check the header average;
     - abandon a rewatch: #1 stays Finished;
     - rate two viewings of a film;
     - Stats' shows-finished count is unchanged by the rewatch.

  Report the tally in the PR.

- [ ] **Step 6: Review, then the PR.**
  - Run a fresh Opus whole-branch review, then local CodeRabbit (`coderabbit review --agent --committed --base origin/main -c AGENTS.md`). Fix the findings test-first.
  - Push and open the PR, ending with the Claude Code footer and session link. Summarise and stop; do not poll CI.

---

## Self-review notes (done while writing)

- **Spec coverage:**
  - §1 data model: Tasks 1 and 3.
  - §2 migration: Tasks 1 and 3.
  - §3 behaviour:
    - shows: Tasks 2 and 5;
    - films: Task 4;
    - title rating: Task 4.
  - §4 screens: Tasks 7, 8 and 9.
  - §5 stats: Task 2 (`ShowStateRow`, `countFinishedShows`); the existing suites run in every task.
  - §6 CSV: Task 6.
  - §8 testing: spread across tasks; device in Task 10.
  - §9 docs: Tasks 1 and 10.
- **A spec gap found and closed:** the spec's §5 says stats "read the same as before". "Shows finished" is derived from the ticks, which now reset on Watch again, so a show being rewatched would have dropped out of the count. Task 2 counts a show as finished when an earlier non-abandoned run exists and the show isn't still airing. **Flag this to the owner** as a rule the spec did not state.
- **Add and Edit film screens** keep their Watched dialog without stars. The spec's "the Watched dialog" is read as the detail screen's, where viewings are recorded and rated; a film added as Watched can be rated from its History. **Flag this to the owner.**
- **Type names are consistent:**
  - `WatchThroughEntity`, `watchThroughId`, `startNextRun`, `removeRun`, `latestRunId`, `recomputeWatchedAtForShow`;
  - `RunState`, `runState`, `asksToRateRun`, `isComplete`;
  - `TitleRating.rated` (renamed from `ratedReads` in Task 4; Task 7 reads `rated`).
