# Read-throughs, Each With Its Own Rating — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group a book's reading sessions into numbered read-throughs, each with its own status, dates and half-star rating. A book's status becomes its latest read's status; re-reads count as books finished, but only first finishes count toward the reading goal.

**Architecture:**
- A new `read_throughs` table (schema v8) becomes the only home of a book's status, `finishedAt` and rating.
- `reading_sessions` gains `readThroughId`.
- The change is staged so every task compiles and passes on its own:
  1. Add the table and migration alongside the old columns.
  2. Move readers and writers onto it.
  3. Move the stats queries.
  4. Move import and export.
  5. Drop `book_details.status` / `finishedAt`. v8 is unreleased, so its schema JSON is edited in place.
- Then the stars component, the screens, goldens and docs.

**Tech Stack:** Kotlin Multiplatform, Room KMP (bundled SQLite driver in jvmTest), kotlinx-datetime/`kotlin.time.Instant`, Jetpack Compose + Material 3, Robolectric + Roborazzi.

**Spec:** `docs/superpowers/specs/2026-10-17-read-throughs-design.md` (approved 2026-10-17). Read it before Task 1.

## Global Constraints

- Ratings are half-steps stored as `Int` **1..10** (7 = 3.5 stars); `null` = unrated. Anything else is rejected by the repository and the importers.
- Only read #1 may be `TO_READ`. Every book always has at least one read.
- Primary keys are UUID strings (`newId()`, AGENTS.md §3), including reads created by the migration.
- `APP_DATABASE_VERSION` 7 → 8. Add the AGENTS.md §8 ledger row `v8 | not yet frozen | MIGRATION_7_8 — …` **in the same commit** as the bump. Migration through `loggedMigration`, with a `MigrationTest` case.
- Catch around suspending code with `guardResource` / `catchingNonCancellation` (`core/util/Cancellation.kt`), never a bare `catch (e: Exception)`.
- Room tests go in `shared/src/jvmTest`; pure tests in `commonTest`; VM tests await `uiState.first { … }`, never `.value` after an action.
- No new dependencies. Star icons are Material Symbols (Outlined) vector drawables: `star` fill 0, `star` fill 1, `star_half` fill 1, from `google/material-design-icons` `symbols/android/…/materialsymbolsoutlined/*_24px.xml` (Apache 2.0).
- `ktlintFormat` before each commit. Max line 120.
- Every commit ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01X3dhMjqKB1M3voUhWUrqxJ
  ```
- The gate before push: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.

## Review Focus

1. **A book with sessions but no `book_details` row** (the known data-integrity edge case) must still get a read #1 in the migration. Otherwise its sessions have no read and the `NOT NULL` FK fails, and the app cannot open its database. *Test: Task 1 `migrate7To8_bookWithoutDetails_stillGetsReadOne`.*
2. **Double-tapping "Read again"** must not create two reads or crash. The unique `(mediaId, number)` index makes the second insert fail, and the repository returns `Resource.Error` rather than throwing. *Test: Task 2 `readAgain_twiceConcurrently_createsOneRead`.*
3. **Changing a re-read back to To read** must be refused (only read #1 may be To read). Otherwise the menu could leave read #2 in an impossible state. *Test: Task 2 `updateReadingStatus_toReadOnRereadIsRefused`.*
4. **An old export (CSV schema ≤ 6) imported over a book that now has two reads** must not attach its sessions to read #2 or overwrite read #2's status. Old files mean "read #1". *Test: Task 4 `oldArchive_overBookWithTwoReads_writesReadOne`.*
5. **A rating in a CSV that is not on the half-star grid** (`4.3`, `0`, `6`, `abc`) must reject that row with a message, not import a rating of 8.6 half-steps or crash. *Test: Task 4 `readThroughsCsv_offGridRating_isRejected`.*

---

### Task 1: The reads table, `readThroughId` on sessions, and `MIGRATION_7_8` (additive)

Nothing reads the new table yet. Every write path that creates a book or a session keeps it consistent, so Task 2 can switch readers with no data repair.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/entities/ReadThroughEntity.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/ReadThroughDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/entities/ReadingSessionEntity.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/AppDatabase.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/Migrations.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/DatabaseFactory.kt:48`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/ReadingSessionDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/BookWriteDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/ImportWriteDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/books/data/ReadingSessionRepository.kt:99`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ImportDataUseCase.kt:1340`
- Modify: `shared/src/commonTest/kotlin/com/hub/media/core/database/TestFixtures.kt`
- Create: `shared/src/jvmTest/kotlin/com/hub/media/core/database/TestBooks.kt`
- Create: `shared/src/jvmTest/kotlin/com/hub/media/core/database/ReadThroughDaoTest.kt`
- Modify: `shared/src/jvmTest/kotlin/com/hub/media/core/database/MigrationTest.kt`
- Modify: `AGENTS.md` §8 ledger
- Generated: `shared/schemas/com.hub.media.core.database.AppDatabase/8.json`

**Interfaces:**
- Produces:
  - `ReadThroughEntity(id: String, mediaId: String, number: Int, status: ReadingStatus, startedAt: Instant? = null, finishedAt: Instant? = null, rating: Int? = null)`.
  - `ReadThroughDao` with:
    - `insert(read)`
    - `observeForMedia(mediaId): Flow<List<ReadThroughEntity>>`, ordered by number
    - `observeAll(): Flow<List<ReadThroughEntity>>`
    - `getAll(): List<ReadThroughEntity>`
    - `getLatest(mediaId): ReadThroughEntity?`
    - `getById(id): ReadThroughEntity?`
    - `updateStatus(id, status, finishedAt): Int`
    - `updateRating(id, rating: Int?): Int`
    - `sessionCount(id): Int`
    - `deleteById(id): Int`
  - `AppDatabase.readThroughDao()`.
  - `ReadingSessionEntity.readThroughId: String`.
  - `ReadingSessionDao.insertIntoLatestRead(session)`.
  - The jvmTest helper `AppDatabase.insertTestBook(mediaId, status = TO_READ, finishedAt = null, rating = null, totalPages = 300, title = "Book $mediaId"): String`, which returns read #1's id.

- [ ] **Step 1: Write the failing migration tests**

Add to `MigrationTest` (jvmTest). Seed with raw v7 SQL (the v7 `createSql` is in `7.json`).

```kotlin
    private fun androidx.sqlite.SQLiteConnection.seedV7Book(
        id: String,
        status: String?,
        finishedAt: Long?,
    ) {
        execSQL(
            "INSERT INTO media_items (id, type, title, releaseYear, purchasePrice, createdAt, coverImageHash, " +
                "communityRating, synopsis) VALUES ('$id', 'BOOK', 'Book $id', NULL, NULL, 1700000000000, NULL, NULL, NULL)",
        )
        if (status != null) {
            execSQL(
                "INSERT INTO book_details (mediaId, isbn, format, totalPages, status, finishedAt, trackingMode, authors) " +
                    "VALUES ('$id', NULL, 'PHYSICAL', 300, '$status', ${finishedAt ?: "NULL"}, 'PAGES', NULL)",
            )
        }
    }

    private fun androidx.sqlite.SQLiteConnection.seedV7Session(
        id: String,
        mediaId: String,
        start: Long,
    ) {
        execSQL(
            "INSERT INTO reading_sessions (id, mediaId, timestampStart, timestampEnd, durationSeconds, startUnit, " +
                "endUnit, deltaPages, notes) VALUES ('$id', '$mediaId', $start, ${start + 600_000}, 600, 1.0, 10.0, 9, NULL)",
        )
    }

    /** Every book gets read #1 carrying its status and finish date; started = its first session (#230). */
    @Test
    fun migrate7To8_everyBookGetsReadOne_withItsStatusAndDates() {
        helper.createDatabase(7).use { db ->
            db.seedV7Book("finished", "FINISHED", 1_700_500_000_000)
            db.seedV7Book("reading", "READING", null)
            db.seedV7Book("toread", "TO_READ", null)
            db.seedV7Book("dnf", "DNF", null)
            db.seedV7Session("s1", "reading", 1_700_100_000_000)
            db.seedV7Session("s2", "reading", 1_700_000_000_000)
        }

        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
            val reads = mutableMapOf<String, List<Any?>>()
            db.prepare("SELECT mediaId, number, status, startedAt, finishedAt, rating, id FROM read_throughs").use { s ->
                while (s.step()) {
                    reads[s.getText(0)] =
                        listOf(
                            s.getLong(1),
                            s.getText(2),
                            if (s.isNull(3)) null else s.getLong(3),
                            if (s.isNull(4)) null else s.getLong(4),
                            if (s.isNull(5)) null else s.getLong(5),
                        )
                    assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(s.getText(6)))
                }
            }
            assertEquals(
                mapOf(
                    "finished" to listOf(1L, "FINISHED", null, 1_700_500_000_000L, null),
                    "reading" to listOf(1L, "READING", 1_700_000_000_000L, null, null),
                    "toread" to listOf(1L, "TO_READ", null, null, null),
                    "dnf" to listOf(1L, "DNF", null, null, null),
                ),
                reads,
            )
        }
    }

    /** Every session joins its book's read #1, and nothing is lost (#230). */
    @Test
    fun migrate7To8_everySessionJoinsReadOne() {
        helper.createDatabase(7).use { db ->
            db.seedV7Book("a", "READING", null)
            db.seedV7Book("b", "FINISHED", 1_700_500_000_000)
            db.seedV7Session("s1", "a", 1_700_000_000_000)
            db.seedV7Session("s2", "a", 1_700_100_000_000)
            db.seedV7Session("s3", "b", 1_700_200_000_000)
        }

        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
            db.prepare(
                "SELECT s.id, r.mediaId, r.number FROM reading_sessions s JOIN read_throughs r ON r.id = s.readThroughId " +
                    "ORDER BY s.id",
            ).use { s ->
                val rows = mutableListOf<String>()
                while (s.step()) rows += "${s.getText(0)}:${s.getText(1)}:${s.getLong(2)}"
                assertEquals(listOf("s1:a:1", "s2:a:1", "s3:b:1"), rows)
            }
        }
    }

    /** A book whose details row is missing still gets read #1, so its sessions have a read (#230 Review Focus 1). */
    @Test
    fun migrate7To8_bookWithoutDetails_stillGetsReadOne() {
        helper.createDatabase(7).use { db ->
            db.seedV7Book("orphan", status = null, finishedAt = null)
            db.seedV7Book("never", status = null, finishedAt = null)
            db.seedV7Session("s1", "orphan", 1_700_000_000_000)
        }

        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
            db.prepare("SELECT mediaId, status FROM read_throughs ORDER BY mediaId").use { s ->
                val rows = mutableListOf<String>()
                while (s.step()) rows += "${s.getText(0)}:${s.getText(1)}"
                // Same derivation MIGRATION_2_3 used: sessions mean Reading, none means To read.
                assertEquals(listOf("never:TO_READ", "orphan:READING"), rows)
            }
        }
    }

    @Test
    fun migrate7To8_emptyDatabase_validatesCleanly() {
        helper.createDatabase(7).use { }
        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "*MigrationTest.migrate7To8*"`
Expected: a compile FAIL, because `MIGRATION_7_8` is unresolved.

- [ ] **Step 3: Add the entity, DAO and session column**

`ReadThroughEntity.kt`:

```kotlin
package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * One read of a book (#230, ROADMAP Task 10). A book's reading sessions are grouped into numbered
 * reads, and each read has its own [status], dates and [rating], so a re-read is its own history
 * rather than a status flip that overwrites the first finish. A book's status is its latest read's
 * (highest [number]). Every book has at least one read; only read #1 may be [ReadingStatus.TO_READ].
 *
 * @property number 1, 2, 3… per book, unique per [mediaId].
 * @property finishedAt When this read became [ReadingStatus.FINISHED]. It follows the rules
 *   `BookRepository.resolveFinishedAt` applies: set on the transition into Finished, kept when saved
 *   again while Finished, and cleared on the way out.
 * @property rating Half-steps 1..10 (7 = 3.5 stars), or `null` when unrated. Validated in code,
 *   since Room cannot express a `CHECK`.
 */
@Entity(
    tableName = "read_throughs",
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
public data class ReadThroughEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val number: Int,
    val status: ReadingStatus,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val rating: Int? = null,
)
```

`ReadThroughDao.kt`:

```kotlin
package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Reads of a book (#230). See [ReadThroughEntity]. */
@Dao
public interface ReadThroughDao {
    /** ABORT: a second read with the same (mediaId, number) -- a double-tapped Read again -- throws. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(read: ReadThroughEntity)

    @Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number")
    public fun observeForMedia(mediaId: String): Flow<List<ReadThroughEntity>>

    @Query("SELECT * FROM read_throughs ORDER BY mediaId, number")
    public fun observeAll(): Flow<List<ReadThroughEntity>>

    @Query("SELECT * FROM read_throughs ORDER BY mediaId, number")
    public suspend fun getAll(): List<ReadThroughEntity>

    @Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    public suspend fun getLatest(mediaId: String): ReadThroughEntity?

    @Query("SELECT * FROM read_throughs WHERE id = :id")
    public suspend fun getById(id: String): ReadThroughEntity?

    @Query("UPDATE read_throughs SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    public suspend fun updateStatus(
        id: String,
        status: ReadingStatus,
        finishedAt: Instant?,
    ): Int

    @Query("UPDATE read_throughs SET rating = :rating WHERE id = :id")
    public suspend fun updateRating(
        id: String,
        rating: Int?,
    ): Int

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE readThroughId = :id")
    public suspend fun sessionCount(id: String): Int

    @Query("DELETE FROM read_throughs WHERE id = :id")
    public suspend fun deleteById(id: String): Int
}
```

In `ReadingSessionEntity`:
- Add a second `ForeignKey(entity = ReadThroughEntity::class, parentColumns = ["id"], childColumns = ["readThroughId"], onDelete = ForeignKey.CASCADE)`.
- Change `indices` to `[Index(value = ["mediaId"]), Index(value = ["readThroughId"])]`.
- Add the field `val readThroughId: String,` after `notes`, with a KDoc line: "The read this session belongs to (#230); new sessions go to the book's latest read."

In `AppDatabase`:
- Add `ReadThroughEntity::class` to `entities`.
- Set `APP_DATABASE_VERSION = 8`.
- Add `abstract fun readThroughDao(): ReadThroughDao`.
- Add a KDoc paragraph: "Version 8 (#230) adds `read_throughs` and groups sessions by read via [MIGRATION_7_8]."

- [ ] **Step 4: Write `MIGRATION_7_8`**

Build once (`./gradlew :shared:compileKotlinJvm`) so Room writes `8.json`. Copy the `createSql` for `read_throughs` and `reading_sessions` from it into the migration; the DDL below is what it must match. Append to `Migrations.kt`, and register it in `DatabaseFactory.kt:48` after `MIGRATION_6_7`.

```kotlin
/** A version-4 UUID built in SQL, one per row: the migration has no Kotlin to call newId() from. */
private const val SQL_UUID: String =
    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

/**
 * Schema v7 -> v8 (#230, ROADMAP Task 10): read-throughs.
 *
 * 1. Creates `read_throughs`, and gives every book read #1:
 *    - status and finish date come from `book_details`;
 *    - a book with no details row (the edge case MediaWithDetails' nullable `details` allows) gets
 *      `READING` when it has sessions and `TO_READ` when it has none, the rule MIGRATION_2_3 used;
 *    - `startedAt` is its earliest session.
 * 2. Rebuilds `reading_sessions` with a `NOT NULL` `readThroughId` (a foreign key SQLite cannot
 *    `ALTER` in), pointing every existing session at its book's read #1.
 *
 * Nothing is lost: every session keeps its values and now belongs to read #1, and every book's status
 * and finish date are unchanged, read through its only read. `book_details.status`/`finishedAt` are
 * left in place here; Task 5 of the plan removes them.
 */
public val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) =
            loggedMigration(7, 8) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `read_throughs` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, " +
                        "`number` INTEGER NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, " +
                        "`rating` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_read_throughs_mediaId_number` " +
                        "ON `read_throughs` (`mediaId`, `number`)",
                )
                connection.execSQL(
                    "INSERT INTO `read_throughs` (`id`, `mediaId`, `number`, `status`, `startedAt`, `finishedAt`, `rating`) " +
                        "SELECT $SQL_UUID, m.`id`, 1, " +
                        "COALESCE(b.`status`, CASE WHEN EXISTS (SELECT 1 FROM `reading_sessions` s " +
                        "WHERE s.`mediaId` = m.`id`) THEN 'READING' ELSE 'TO_READ' END), " +
                        "(SELECT MIN(s.`timestampStart`) FROM `reading_sessions` s WHERE s.`mediaId` = m.`id`), " +
                        "b.`finishedAt`, NULL " +
                        "FROM `media_items` m LEFT JOIN `book_details` b ON b.`mediaId` = m.`id` " +
                        "WHERE m.`type` = 'BOOK' OR EXISTS (SELECT 1 FROM `reading_sessions` s WHERE s.`mediaId` = m.`id`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_sessions_new` (`id` TEXT NOT NULL, `mediaId` TEXT NOT NULL, " +
                        "`timestampStart` INTEGER NOT NULL, `timestampEnd` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`startUnit` REAL NOT NULL, `endUnit` REAL NOT NULL, `deltaPages` INTEGER, `notes` TEXT, " +
                        "`readThroughId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`readThroughId`) REFERENCES `read_throughs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `reading_sessions_new` (`id`, `mediaId`, `timestampStart`, `timestampEnd`, " +
                        "`durationSeconds`, `startUnit`, `endUnit`, `deltaPages`, `notes`, `readThroughId`) " +
                        "SELECT s.`id`, s.`mediaId`, s.`timestampStart`, s.`timestampEnd`, s.`durationSeconds`, " +
                        "s.`startUnit`, s.`endUnit`, s.`deltaPages`, s.`notes`, r.`id` FROM `reading_sessions` s " +
                        "JOIN `read_throughs` r ON r.`mediaId` = s.`mediaId` AND r.`number` = 1",
                )
                connection.execSQL("DROP TABLE `reading_sessions`")
                connection.execSQL("ALTER TABLE `reading_sessions_new` RENAME TO `reading_sessions`")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_mediaId` ON `reading_sessions` (`mediaId`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_readThroughId` ON `reading_sessions` (`readThroughId`)",
                )
            }
    }
```

If `runMigrationsAndValidate` reports a mismatch, diff the expected `createSql` in `8.json` against the strings above and correct the strings, never the JSON.

- [ ] **Step 5: Keep every write path consistent**

`ReadingSessionDao`:

```kotlin
    @Query("SELECT id FROM read_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun latestReadId(mediaId: String): String?

    /** Inserts [session] into its book's latest read (#230): new sessions always belong to the current read. */
    @Transaction
    suspend fun insertIntoLatestRead(session: ReadingSessionEntity) {
        val readId = checkNotNull(latestReadId(session.mediaId)) { "Book '${session.mediaId}' has no read-through" }
        insert(session.copy(readThroughId = readId))
    }
```

(Import `androidx.room.Transaction`.) `ReadingSessionRepository.logSession`: build the entity with `readThroughId = ""`, which `insertIntoLatestRead` replaces, and call `db.readingSessionDao().insertIntoLatestRead(session)` instead of `insert`.

`BookWriteDao` and `ImportWriteDao` each get the same query. Room's transaction pattern needs it on the calling DAO.

```kotlin
    /** Read #1 for a book that has none yet (#230); a no-op when it exists, via the unique (mediaId, number) index. */
    @Query(
        "INSERT OR IGNORE INTO read_throughs (id, mediaId, number, status, startedAt, finishedAt, rating) " +
            "VALUES (:id, :mediaId, 1, :status, NULL, :finishedAt, NULL)",
    )
    suspend fun insertFirstReadIfMissing(
        id: String,
        mediaId: String,
        status: ReadingStatus,
        finishedAt: Instant?,
    )
```

Call it:
- `BookWriteDao.insertBookAtomically`, after `insertBookDetails(bookDetails)`:
  `insertFirstReadIfMissing(newId(), mediaItem.id, bookDetails.status, bookDetails.finishedAt)`.
- In `updateBookMetadataAtomically`'s self-heal branch, after `insertBookDetails(...)`:
  `insertFirstReadIfMissing(newId(), mediaId, status, finishedAt)`.
- `ImportWriteDao.importAtomically`, in the media-insert loop's `ImportDetails.Book` branch:
  `insertBookDetails(d.details); insertFirstReadIfMissing(newId(), d.details.mediaId, d.details.status, d.details.finishedAt)`.
- In the update loop's book self-heal, the same after `insertBookDetails`.
- In `importAtomically`, replace `insertSession(session)` with:

```kotlin
            val readId = checkNotNull(latestReadId(session.mediaId)) { "Book '${session.mediaId}' has no read-through" }
            insertSession(session.copy(readThroughId = readId))
```

with `latestReadId` declared on `ImportWriteDao` exactly as on `ReadingSessionDao`. `ImportDataUseCase.kt:1340` builds `ReadingSessionEntity(... readThroughId = "")`. Session *updates* in an import keep the existing row's `readThroughId`: where the use case builds the update entity from the existing session, copy `readThroughId = existing.readThroughId`.

- [ ] **Step 6: Test fixtures and the test-book helper**

`TestFixtures.sampleReadingSession` gains `readThroughId: String = ""` and passes it through. KDoc: "Insert with `ReadingSessionDao.insertIntoLatestRead`, which fills it."

Create `shared/src/jvmTest/kotlin/com/hub/media/core/database/TestBooks.kt`:

```kotlin
package com.hub.media.core.database

import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.newId
import kotlin.time.Instant

/**
 * A book with its read #1, the way every write path creates one since #230. Tests that seeded a
 * book's status on `book_details` seed it here instead. Returns read #1's id.
 */
internal suspend fun AppDatabase.insertTestBook(
    mediaId: String,
    status: ReadingStatus = ReadingStatus.TO_READ,
    finishedAt: Instant? = null,
    rating: Int? = null,
    totalPages: Int? = 300,
    title: String = "Book $mediaId",
): String {
    mediaItemDao().insert(sampleMediaItem(id = mediaId, title = title))
    bookDetailsDao().insert(
        sampleBookDetails(mediaId = mediaId, totalPages = totalPages, status = status, finishedAt = finishedAt),
    )
    val readId = newId()
    readThroughDao().insert(
        ReadThroughEntity(id = readId, mediaId = mediaId, number = 1, status = status, finishedAt = finishedAt, rating = rating),
    )
    return readId
}
```

Check `sampleMediaItem`'s parameter names in `TestFixtures.kt`, and that `mediaItemDao().insert` exists, before relying on them. Adjust the call to match.

Then make every existing jvmTest that inserts a session directly still compile and pass:
- Replace `readingSessionDao().insert(` with `readingSessionDao().insertIntoLatestRead(` in `ReadingSessionDaoTest`, `PeriodStatsDaoTest`, `StatsDaoTest`, `TimedSessionsDaoTest`, `ImportWriteDaoTest`, `StatsRepositoryTest`, `StatsViewModelTest`, `YearReviewViewModelTest` and `ImportDataUseCaseTest`.
- Where such a test inserted the book through `bookDetailsDao().insert(...)` alone (no read), add `db.readThroughDao().insert(ReadThroughEntity(newId(), mediaId, 1, <the status it set>, finishedAt = <the finishedAt it set>))` right after it, or switch it to `insertTestBook`. Anchor each edit on that test's own literal (AGENTS.md §7), never a shared setup line.

- [ ] **Step 7: DAO tests for the reads table**

`ReadThroughDaoTest.kt` (jvmTest, `testAppDatabase()` as in the other DAO tests):

```kotlin
class ReadThroughDaoTest {
    private lateinit var db: AppDatabase

    @BeforeTest fun setUp() { db = testAppDatabase() }

    @AfterTest fun tearDown() { db.close() }

    @Test
    fun addBook_createsReadOne_withTheBooksStatus() =
        runTest {
            val id = BookRepository(db).addBook(title = "Dune", format = BookFormat.PHYSICAL, status = ReadingStatus.READING)
            val mediaId = (id as Resource.Success).data

            val reads = db.readThroughDao().observeForMedia(mediaId).first()
            assertEquals(listOf(1 to ReadingStatus.READING), reads.map { it.number to it.status })
        }

    @Test
    fun aSecondReadWithTheSameNumber_isRejected() =
        runTest {
            db.insertTestBook("b1")
            assertFailsWith<Exception> {
                db.readThroughDao().insert(ReadThroughEntity(newId(), "b1", 1, ReadingStatus.READING))
            }
        }

    @Test
    fun aLoggedSession_joinsTheLatestRead() =
        runTest {
            db.insertTestBook("b1", status = ReadingStatus.FINISHED)
            val readTwo = newId()
            db.readThroughDao().insert(ReadThroughEntity(readTwo, "b1", 2, ReadingStatus.READING))

            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b1"))

            assertEquals(1, db.readThroughDao().sessionCount(readTwo))
        }

    @Test
    fun deletingTheBook_deletesItsReadsAndSessions() =
        runTest {
            val readOne = db.insertTestBook("b1")
            db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b1"))

            db.mediaItemDao().deleteById("b1")

            assertNull(db.readThroughDao().getById(readOne))
            assertEquals(0, db.readThroughDao().sessionCount(readOne))
        }
}
```

Check that `mediaItemDao().deleteById` exists. Otherwise use the delete the repository uses.

- [ ] **Step 8: Run the tests**

Run: `./gradlew :shared:jvmTest`
Expected: PASS, every existing test included. A failure in an untouched test means a write path is still missing a read: fix the path, not the test.

- [ ] **Step 9: Ledger and commit**

Append to the AGENTS.md §8 table: `| v8 | not yet frozen | MIGRATION_7_8 — read_throughs; sessions grouped by read (#230) |`. Change "Current: `APP_DATABASE_VERSION = 7`" to 8.

```bash
./gradlew ktlintFormat
git add -A shared AGENTS.md
git commit -m "Read-throughs: the reads table and MIGRATION_7_8 (#230)"
```

---

### Task 2: A book's status, re-reads and ratings live on its reads

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/books/data/Rating.kt`
- Create: `shared/src/commonTest/kotlin/com/hub/media/features/books/data/RatingTest.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/books/data/ReadThroughRepository.kt`
- Create: `shared/src/jvmTest/kotlin/com/hub/media/features/books/data/ReadThroughRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/media/data/MediaWithDetails.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/books/data/BookRepository.kt` (`observeBookDetail`, `observeAllBooksWithDetails`, `getBookWithDetails`, `getAllBooksWithDetails`, `updateReadingStatus`, `updateBookMetadata`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/MediaRepository.kt:86`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/BookWriteDao.kt`
- Modify readers:
  - `ui/EditBookViewModel.kt:132`
  - `ui/HubViewModel.kt:71`
  - `ui/LibraryStatusFilter.kt:54`
  - `app/.../LibraryScreen.kt:737`
  - `app/.../BookDetailsTab.kt:79,83`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/AppContainer.kt` (wire `ReadThroughRepository`)

**Interfaces:**
- Consumes: Task 1's `ReadThroughDao` and `ReadThroughEntity`.
- Produces:
  - `Rating.kt`:
    - `const val MIN_RATING = 1`, `const val MAX_RATING = 10`
    - `fun isValidRating(halves: Int): Boolean`
    - `data class TitleRating(val averageStars: Double, val latestStars: Double, val ratedReads: Int)`
    - `fun titleRating(reads: List<ReadThroughEntity>): TitleRating?`
    - `fun Int.toStars(): Double` (halves / 2.0)
    - `fun starsToHalves(stars: Double): Int?` (null unless on the 0.5 grid in 0.5..5)
  - `MediaWithDetails.Book(item, details, reads: List<ReadThroughEntity> = emptyList())`, with `latestRead`, `status: ReadingStatus`, `finishedAt: Instant?` and `rating: TitleRating?`.
  - `ReadThroughRepository(db, clock)`:
    - `observeReads(mediaId): Flow<List<ReadThroughEntity>>`
    - `readAgain(mediaId): Resource<String>`
    - `removeRead(readId): Resource<Unit>`
    - `setRating(readId, halves: Int?): Resource<Unit>`
  - `BookRepository.updateReadingStatus(mediaId, status)` now writes the latest read.

- [ ] **Step 1: Failing pure tests for ratings**

`RatingTest.kt` (commonTest):

```kotlin
class RatingTest {
    private fun read(n: Int, rating: Int?) = ReadThroughEntity("r$n", "b", n, ReadingStatus.FINISHED, rating = rating)

    @Test fun validRatings_areOneToTen() {
        assertEquals((1..10).toList(), (-1..12).filter { isValidRating(it) })
    }

    @Test fun noRatedReads_isNull() {
        assertNull(titleRating(listOf(read(1, null), read(2, null))))
    }

    @Test fun averageAndLatest_ignoreUnratedReads() {
        // 4.0 then unrated then 5.0: average 4.5, latest 5.0, from 2 rated reads.
        assertEquals(TitleRating(4.5, 5.0, 2), titleRating(listOf(read(1, 8), read(2, null), read(3, 10))))
    }

    @Test fun latest_isTheHighestNumberedRatedRead_notListOrder() {
        assertEquals(3.0, titleRating(listOf(read(2, 6), read(1, 10)))!!.latestStars)
    }

    @Test fun starsToHalves_acceptsOnlyTheHalfGrid() {
        assertEquals(9, starsToHalves(4.5))
        assertEquals(1, starsToHalves(0.5))
        assertNull(starsToHalves(4.3))
        assertNull(starsToHalves(0.0))
        assertNull(starsToHalves(5.5))
    }
}
```

Run: `./gradlew :shared:jvmTest --tests "*RatingTest"`. Expected: a compile FAIL.

- [ ] **Step 2: Implement `Rating.kt`**

```kotlin
package com.hub.media.features.books.data

import com.hub.media.core.database.entities.ReadThroughEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rating in half-steps (#230): 1 = half a star, 10 = five stars. */
public const val MIN_RATING: Int = 1
public const val MAX_RATING: Int = 10

public fun isValidRating(halves: Int): Boolean = halves in MIN_RATING..MAX_RATING

public fun Int.toStars(): Double = this / 2.0

/** [stars] as half-steps, or `null` when it is not on the half-star grid in 0.5..5 (an import's `4.3`). */
public fun starsToHalves(stars: Double): Int? {
    val halves = (stars * 2).roundToInt()
    return if (abs(stars * 2 - halves) < 1e-9 && isValidRating(halves)) halves else null
}

/**
 * A title's rating from its rated reads (#230): the average and the latest, in stars. Unrated reads
 * are ignored; `null` when none is rated.
 */
public data class TitleRating(
    val averageStars: Double,
    val latestStars: Double,
    val ratedReads: Int,
)

public fun titleRating(reads: List<ReadThroughEntity>): TitleRating? {
    val rated = reads.filter { it.rating != null }.sortedBy { it.number }
    if (rated.isEmpty()) return null
    return TitleRating(
        averageStars = rated.map { it.rating!! }.average() / 2.0,
        latestStars = rated.last().rating!!.toStars(),
        ratedReads = rated.size,
    )
}
```

Run `RatingTest`. Expected: PASS. Commit: `git commit -m "Ratings: half-step values and a title's average (#230)"`.

- [ ] **Step 3: Failing repository tests**

`ReadThroughRepositoryTest.kt` (jvmTest). Use a fixed clock: `object : Clock { override fun now() = Instant.fromEpochMilliseconds(1_800_000_000_000) }`.

```kotlin
class ReadThroughRepositoryTest {
    private lateinit var db: AppDatabase
    private val now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val clock = object : Clock { override fun now() = this@ReadThroughRepositoryTest.now }
    private lateinit var reads: ReadThroughRepository
    private lateinit var books: BookRepository

    @BeforeTest fun setUp() {
        db = testAppDatabase()
        reads = ReadThroughRepository(db, clock)
        books = BookRepository(db, clock)
    }

    @AfterTest fun tearDown() { db.close() }

    @Test fun readAgain_afterFinishing_startsReadTwoAtReading() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1))
        assertIs<Resource.Success<String>>(reads.readAgain("b"))
        val all = reads.observeReads("b").first()
        assertEquals(listOf(1 to ReadingStatus.FINISHED, 2 to ReadingStatus.READING), all.map { it.number to it.status })
        assertEquals(now, all[1].startedAt)
        assertEquals(Instant.fromEpochMilliseconds(1), all[0].finishedAt, "read #1 keeps its finish date")
    }

    @Test fun readAgain_whileReading_isRefused() = runTest {
        db.insertTestBook("b", status = ReadingStatus.READING)
        assertIs<Resource.Error>(reads.readAgain("b"))
        assertEquals(1, reads.observeReads("b").first().size)
    }

    @Test fun readAgain_twiceConcurrently_createsOneRead() = runTest {
        db.insertTestBook("b", status = ReadingStatus.DNF)
        val results = withContext(Dispatchers.Default) {
            listOf(async { reads.readAgain("b") }, async { reads.readAgain("b") }).awaitAll()
        }
        assertEquals(1, results.count { it is Resource.Success })
        assertEquals(2, reads.observeReads("b").first().size)
    }

    @Test fun removeRead_emptyReread_returnsTheBookToItsPreviousRead() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED)
        val two = (reads.readAgain("b") as Resource.Success).data
        assertIs<Resource.Success<Unit>>(reads.removeRead(two))
        assertEquals(ReadingStatus.FINISHED, books.getBookWithDetails("b").let { (it as Resource.Success).data!!.status })
    }

    @Test fun removeRead_refusesReadOne_andAReadWithSessions() = runTest {
        val one = db.insertTestBook("b", status = ReadingStatus.FINISHED)
        assertIs<Resource.Error>(reads.removeRead(one))
        val two = (reads.readAgain("b") as Resource.Success).data
        db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b"))
        assertIs<Resource.Error>(reads.removeRead(two))
    }

    @Test fun setRating_storesClearsAndRejectsOffGrid() = runTest {
        val one = db.insertTestBook("b", status = ReadingStatus.FINISHED)
        assertIs<Resource.Success<Unit>>(reads.setRating(one, 9))
        assertEquals(9, db.readThroughDao().getById(one)!!.rating)
        assertIs<Resource.Success<Unit>>(reads.setRating(one, null))
        assertNull(db.readThroughDao().getById(one)!!.rating)
        assertIs<Resource.Error>(reads.setRating(one, 11))
        assertIs<Resource.Error>(reads.setRating(one, 0))
    }

    @Test fun updateReadingStatus_writesTheLatestRead_withFinishedAtRules() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1))
        reads.readAgain("b")
        books.updateReadingStatus("b", ReadingStatus.FINISHED)
        val all = reads.observeReads("b").first()
        assertEquals(Instant.fromEpochMilliseconds(1), all[0].finishedAt)
        assertEquals(now, all[1].finishedAt)
        assertEquals(ReadingStatus.FINISHED, all[1].status)
    }

    @Test fun updateReadingStatus_toReadOnRereadIsRefused() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED)
        reads.readAgain("b")
        assertIs<Resource.Error>(books.updateReadingStatus("b", ReadingStatus.TO_READ))
        assertEquals(ReadingStatus.READING, reads.observeReads("b").first().last().status)
    }

    @Test fun aBooksStatus_isItsLatestReads() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED)
        reads.readAgain("b")
        val book = books.observeBookDetail("b").first()!!
        assertEquals(ReadingStatus.READING, book.status)
        assertEquals(2, book.reads.size)
    }
}
```

Run: `./gradlew :shared:jvmTest --tests "*ReadThroughRepositoryTest"`. Expected: a compile FAIL.

- [ ] **Step 4: Implement**

`MediaWithDetails.Book`:

```kotlin
    /**
     * @property reads The book's read-throughs, in any order (#230). Its status, finish date and
     *   rating come from them -- never from [details], which no longer holds them.
     */
    public data class Book(
        override val item: MediaItemEntity,
        val details: BookDetailsEntity?,
        val reads: List<ReadThroughEntity> = emptyList(),
    ) : MediaWithDetails() {
        val latestRead: ReadThroughEntity? get() = reads.maxByOrNull { it.number }
        val status: ReadingStatus get() = latestRead?.status ?: ReadingStatus.TO_READ
        val finishedAt: Instant? get() = latestRead?.finishedAt
        val rating: TitleRating? get() = titleRating(reads)
    }
```

`BookRepository`'s four builders also fetch reads:
- `observeBookDetail` combines a third flow, `db.readThroughDao().observeForMedia(id)`.
- `observeAllBooksWithDetails` combines `db.readThroughDao().observeAll()`, grouped `groupBy { it.mediaId }`.
- The one-shot functions use `getAll()` / a `getLatest`-free `observeForMedia(id).first()` equivalent. Add `@Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number") suspend fun getForMedia(mediaId: String): List<ReadThroughEntity>` to `ReadThroughDao` for them.

Do the same in `MediaRepository.kt:86`.

`BookRepository.updateReadingStatus` becomes:

```kotlin
            val latest =
                db.readThroughDao().getLatest(mediaId)
                    ?: return Resource.Error("No read-through found for id=$mediaId")
            if (status == ReadingStatus.TO_READ && latest.number > 1) {
                return Resource.Error("A re-read can't go back to To read; remove the read instead")
            }
            val finishedAt = resolveFinishedAt(status, latest.status, latest.finishedAt, clock)
            db.readThroughDao().updateStatus(latest.id, status, finishedAt)
            Resource.Success(Unit)
```

`updateBookMetadata`: the edit form's status also goes to the latest read.
- Add `@Query("UPDATE read_throughs SET status = :status, finishedAt = :finishedAt WHERE mediaId = :mediaId AND number = (SELECT MAX(number) FROM read_throughs WHERE mediaId = :mediaId)") suspend fun updateLatestReadStatus(mediaId: String, status: ReadingStatus, finishedAt: Instant?)` to `BookWriteDao`.
- Call it inside `updateBookMetadataAtomically`, after the details write.
- Compute `finishedAt` from the latest read (`db.readThroughDao().getLatest(mediaId)`), not `existingDetails`.
- Apply the same To-read-on-a-reread refusal before any write.
- Keep writing `book_details.status`/`finishedAt` too, until Task 5 removes them. Nothing reads them after this task.

`ReadThroughRepository`:

```kotlin
public class ReadThroughRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val logger: Logger = AppLogger,
) {
    public fun observeReads(mediaId: String): Flow<List<ReadThroughEntity>> = db.readThroughDao().observeForMedia(mediaId)

    /** Starts the next read at Reading (#230); only after the latest read is Finished or Did not finish. */
    public suspend fun readAgain(mediaId: String): Resource<String> =
        logger.guardResource(TAG, failure = "Couldn't start a re-read", logMessage = { "readAgain failed: $mediaId" }) {
            val latest = db.readThroughDao().getLatest(mediaId) ?: return Resource.Error("No read-through for $mediaId")
            if (latest.status != ReadingStatus.FINISHED && latest.status != ReadingStatus.DNF) {
                return Resource.Error("Finish or stop the current read before reading again")
            }
            val id = newId()
            // A double tap's second insert hits the unique (mediaId, number) index and throws;
            // guardResource turns that into an Error, so exactly one read is created.
            db.readThroughDao().insert(
                ReadThroughEntity(id, mediaId, latest.number + 1, ReadingStatus.READING, startedAt = clock.now()),
            )
            Resource.Success(id)
        }

    /** Removes a re-read with no sessions (#230): the undo for a mistaken Read again. Never read #1. */
    public suspend fun removeRead(readId: String): Resource<Unit> =
        logger.guardResource(TAG, failure = "Couldn't remove the read", logMessage = { "removeRead failed: $readId" }) {
            val read = db.readThroughDao().getById(readId) ?: return Resource.Error("Read not found")
            if (read.number == 1) return Resource.Error("The first read can't be removed")
            if (db.readThroughDao().sessionCount(readId) > 0) return Resource.Error("This read has sessions")
            db.readThroughDao().deleteById(readId)
            Resource.Success(Unit)
        }

    /** Sets [readId]'s rating in half-steps, or clears it with `null` (#230). */
    public suspend fun setRating(
        readId: String,
        halves: Int?,
    ): Resource<Unit> {
        if (halves != null && !isValidRating(halves)) return Resource.Error("A rating is half a star to five stars")
        return logger.guardResource(TAG, failure = "Couldn't save the rating", logMessage = { "setRating failed: $readId" }) {
            if (db.readThroughDao().updateRating(readId, halves) == 0) Resource.Error("Read not found") else Resource.Success(Unit)
        }
    }

    private companion object {
        const val TAG = "ReadThroughRepository"
    }
}
```

Match `guardResource`'s real parameter list from `core/util/Cancellation.kt`. The calls above mirror `BookRepository`'s.

Wire `ReadThroughRepository` in `AppContainer` beside `BookRepository`, with the same `db` and `clock`.

Readers: replace `details?.status` with the `Book`'s `status`:
- `EditBookViewModel.kt:132`: `bookDetail.status`
- `HubViewModel.kt:71`: `it.status == ReadingStatus.READING`
- `LibraryStatusFilter.kt:54`: `of(media.status)`
- `LibraryScreen.kt:737`: `media.status`
- `BookDetailsTab.kt:79,83`: `finishedNote(book.status, book.finishedAt)` / `bookStatusControl(book.status, …)`. Thread the `MediaWithDetails.Book` or its `status`/`finishedAt` into `BookDetailHeaderSection`. Task 7 replaces this header anyway, so keep it minimal.

Update `BookDetailUiState.Ready` to carry `reads: List<ReadThroughEntity> = emptyList()`, filled by `BookDetailViewModel` from `bookRepository.observeBookDetail(bookId)` (the `Book` now carries them). Add `val status get() = reads.maxByOrNull { it.number }?.status ?: ReadingStatus.TO_READ` and `val finishedAt` the same way, so the screen reads them there.

- [ ] **Step 5: Run everything**

Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. Tests that built `MediaWithDetails.Book(item, details(status = X))` and asserted on status now need `reads = listOf(ReadThroughEntity(…, status = X))`. Update each, anchored on its own literal.

- [ ] **Step 6: Commit**

`git commit -m "Read-throughs: a book's status is its latest read; read again, remove, rate (#230)"`

---

### Task 3: Stats count reads; the goal counts first finishes

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/StatsDao.kt` (`observePeriodStats`, `observeFinishedAtInRange`, `observeFinishOutcomes`, `observeFinishedBooks`, `observeFirstActivity`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/PeriodStatsRow.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsViewModel.kt:133`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/YearReview.kt:149`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/core/database/PeriodStatsDaoTest.kt`, `StatsDaoTest.kt`, `TimedSessionsDaoTest.kt`; `shared/src/commonTest/.../YearReviewTest.kt`; `shared/src/jvmTest/.../StatsViewModelTest.kt`

**Interfaces:**
- Consumes: `read_throughs` (Task 1), `insertTestBook` (Task 1).
- Produces: `PeriodStatsRow.firstFinishes: Int` (last field, default `0`).

- [ ] **Step 1: Failing DAO tests** (in `PeriodStatsDaoTest`):

```kotlin
    /** Re-reads count as books finished in their own period; the goal's count takes first finishes only (#230). */
    @Test
    fun aRereadCountsAsFinished_butNotAsAFirstFinish() = runTest {
        db.insertTestBook("dune", status = ReadingStatus.FINISHED, finishedAt = instant(1_000))
        db.readThroughDao().insert(
            ReadThroughEntity(newId(), "dune", 2, ReadingStatus.FINISHED, finishedAt = instant(5_000)),
        )
        db.insertTestBook("phm", status = ReadingStatus.FINISHED, finishedAt = instant(5_500))

        val later = db.statsDao().observePeriodStats(instant(4_000), instant(6_000), ReadingStatus.FINISHED).first()

        assertEquals(2, later.booksFinished, "dune's re-read and phm")
        assertEquals(1, later.firstFinishes, "only phm is new")
    }

    /** DNF first, then a finish: the finish is the book's first, so it counts toward the goal (#230). */
    @Test
    fun aFinishAfterADnf_isAFirstFinish() = runTest {
        db.insertTestBook("b", status = ReadingStatus.DNF)
        db.readThroughDao().insert(ReadThroughEntity(newId(), "b", 2, ReadingStatus.FINISHED, finishedAt = instant(5_000)))

        val row = db.statsDao().observePeriodStats(instant(4_000), instant(6_000), ReadingStatus.FINISHED).first()

        assertEquals(1, row.firstFinishes)
    }
```

In `StatsDaoTest`, add the DNF-rate and longest-book cases:

```kotlin
    /** Finish outcomes count reads, so a book finished then re-read and abandoned is one of each (#230). */
    @Test
    fun finishOutcomes_countReads() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1))
        db.readThroughDao().insert(ReadThroughEntity(newId(), "b", 2, ReadingStatus.DNF))

        val o = db.statsDao().observeFinishOutcomes(ReadingStatus.FINISHED, ReadingStatus.DNF).first()

        assertEquals(1, o.finished)
        assertEquals(1, o.dnf)
    }
```

In `TimedSessionsDaoTest`, extend the `finishedBooks`/`firstActivity` tests to cover a re-read's `finishedAt` (the second read's date counts; first activity is the earliest of any read). Run: `./gradlew :shared:jvmTest --tests "*PeriodStatsDaoTest" --tests "*StatsDaoTest" --tests "*TimedSessionsDaoTest"`. Expected: FAIL (`firstFinishes` unresolved, and counts wrong).

- [ ] **Step 2: Rewrite the queries over `read_throughs`**

In `observePeriodStats`, replace the `booksFinished` subquery and add `firstFinishes` as the last selected column:

```sql
          (SELECT COUNT(*) FROM read_throughs
             WHERE status = :finished
               AND (:from IS NULL OR finishedAt >= :from)
               AND (:to IS NULL OR finishedAt < :to)) AS booksFinished,
```

```sql
          (SELECT COUNT(*) FROM read_throughs r
             WHERE r.status = :finished
               AND (:from IS NULL OR r.finishedAt >= :from)
               AND (:to IS NULL OR r.finishedAt < :to)
               AND r.number = (SELECT MIN(f.number) FROM read_throughs f
                                WHERE f.mediaId = r.mediaId AND f.status = :finished)) AS firstFinishes
```

Then:
- `observeFinishedAtInRange`: `SELECT finishedAt FROM read_throughs WHERE status = :finished AND finishedAt >= :from AND finishedAt < :to`.
- `observeFinishOutcomes`: both counts `FROM read_throughs`.
- `observeFinishedBooks`: `SELECT b.mediaId, mi.title, b.totalPages, b.authors, r.finishedAt FROM read_throughs r JOIN book_details b ON b.mediaId = r.mediaId JOIN media_items mi ON mi.id = r.mediaId WHERE r.status = :finished AND r.finishedAt >= :from AND r.finishedAt < :to`.
- `observeFirstActivity`: replace `SELECT MIN(finishedAt) FROM book_details` with `SELECT MIN(finishedAt) FROM read_throughs`.
- Update each KDoc to say "reads", citing #230.

`PeriodStatsRow`: add `val firstFinishes: Int = 0,` last, with KDoc "Finished reads that are their book's first finish (#230): the reading goal's count."

- [ ] **Step 3: The goal uses first finishes**

- `StatsViewModel.kt:133`: `goalProgress(goal = it.books, finished = yearStats.firstFinishes, today = today)`.
- `YearReview.kt:149`: `GoalResult(it.books, periodStats.firstFinishes, periodStats.firstFinishes >= it.books)`.

Add a failing case to `YearReviewTest` first:

```kotlin
    @Test fun theGoal_countsFirstFinishes_notReReads() {
        val r = review(periodStats = PeriodStatsRow(null, 0, null, 3, 0, null, null, null, firstFinishes = 2), goal = ReadingGoal(2026, 2))
        assertEquals(GoalResult(goal = 2, finished = 2, reached = true), r.goal)
        assertEquals(3, r.totals.booksFinished)
    }
```

Add one to `StatsViewModelTest` too: a book finished last year and re-read this year, with a goal of 1, shows 0 / 1. Await the state with `uiState.first { it.goal != null }`.

- [ ] **Step 4: Run, fix fixtures, commit**

Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest`. Expected: PASS. Any stats test that seeded a finished book on `book_details` alone now needs `insertTestBook(..., status, finishedAt)`.

`git commit -m "Stats count reads; the reading goal counts first finishes (#230)"`

---

### Task 4: Export and import carry reads and ratings; Goodreads ratings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/CsvSchemaVersion.kt` (6 → 7 and its history KDoc)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ExportArchive.kt` (`READ_THROUGHS = "read_throughs_export.csv"`)
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ReadThroughCsvExporter.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/portability/csv/ReadThroughCsvImporter.kt`
- Modify: `ReadingLogCsvExporter.kt` and `ReadingLogCsvImporter.kt` (`read_number`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/domain/ExportDataUseCase.kt`, `ImportDataUseCase.kt`, `ImportArchiveUseCase.kt` (archive entries)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/ImportWriteDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/portability/goodreads/GoodreadsCsvImporter.kt`
- Test: `shared/src/commonTest/.../ReadThroughCsvExporterTest.kt`, `ReadThroughCsvImporterTest.kt`, `ReadingLogCsv*Test.kt`, `GoodreadsCsvImporterTest.kt`; `shared/src/jvmTest/.../ImportDataUseCaseTest.kt`, `ZipImportExportTest.kt`

**Interfaces:**
- Consumes: `ReadThroughEntity`; `starsToHalves`, `toStars` (Task 2).
- Produces:
  - `ReadThroughCsvExporter.export(reads: List<ReadThroughEntity>): String`
  - `ReadThroughCsvImporter.parse(csv: String): List<ReadThroughRowParseResult>`, where each result is `Ok(ParsedReadThrough)` or `Rejected(line, message)`, the same shape as the other importers' result types (copy theirs)
  - `ParsedReadThrough(mediaId: String, number: Int, status: ReadingStatus, startedAt: Instant?, finishedAt: Instant?, rating: Int?)`

Before coding, read `ReadingLogCsvExporter.kt` and `ReadingLogCsvImporter.kt` end to end. The new reads exporter and importer copy their shape exactly: the header constant, the timestamp format helper, the `csv_schema_version` column, and the reject-with-line-number results.

- [ ] **Step 1: Failing CSV tests** (commonTest)

```kotlin
class ReadThroughCsvImporterTest {
    private val header = "csv_schema_version,media_id,number,status,started_at,finished_at,rating"

    @Test fun aRead_roundTripsThroughExportAndParse() {
        val read = ReadThroughEntity("r2", "b", 2, ReadingStatus.FINISHED,
            startedAt = Instant.fromEpochMilliseconds(1_000), finishedAt = Instant.fromEpochMilliseconds(2_000), rating = 9)
        val parsed = ReadThroughCsvImporter.parse(ReadThroughCsvExporter.export(listOf(read)))
        assertEquals(
            listOf(ParsedReadThrough("b", 2, ReadingStatus.FINISHED, Instant.fromEpochMilliseconds(1_000),
                Instant.fromEpochMilliseconds(2_000), 9)),
            parsed.filterIsInstance<ReadThroughRowParseResult.Ok>().map { it.read },
        )
    }

    @Test fun readThroughsCsv_offGridRating_isRejected() {
        for (bad in listOf("4.3", "0", "6", "abc")) {
            val results = ReadThroughCsvImporter.parse("$header\n7,b,1,FINISHED,,,$bad")
            assertIs<ReadThroughRowParseResult.Rejected>(results.single(), "rating '$bad'")
        }
    }

    @Test fun aRereadAtToRead_isRejected() {
        assertIs<ReadThroughRowParseResult.Rejected>(ReadThroughCsvImporter.parse("$header\n7,b,2,TO_READ,,,").single())
    }

    @Test fun anEmptyRating_isUnrated() {
        val ok = ReadThroughCsvImporter.parse("$header\n7,b,1,READING,,,").single() as ReadThroughRowParseResult.Ok
        assertNull(ok.read.rating)
    }
}
```

Match the timestamp column format the reading log uses. If it writes ISO-8601 strings, the test's empty cells stay valid. `ReadingLogCsv*Test`: an exported session carries `read_number`; a file without the column parses with `readNumber = 1`.

`GoodreadsCsvImporterTest`:
- `My Rating` `4` → `rating = 8`;
- `0` → `null`;
- the import notice no longer names `My Rating`.

- [ ] **Step 2: Failing import tests** (jvmTest `ImportDataUseCaseTest`)

```kotlin
    /** A v7 archive brings back every read with its rating, and every session in its read (#230). */
    @Test fun v7Archive_roundTripsReadsAndRatings() = runTest { /* export a library with a 2-read book
       (read 1 FINISHED rated 8, read 2 READING with one session), wipe, import, assert reads,
       ratings and each session's read number */ }

    /** Old files mean read #1: sessions and status land there even when the book now has two reads (#230 Review Focus 4). */
    @Test fun oldArchive_overBookWithTwoReads_writesReadOne() = runTest { /* existing book with reads 1 (FINISHED)
       and 2 (READING); import a v6 library + reading log for it with MERGE; assert the new session's
       readThroughId is read #1's and read #2 is untouched */ }

    /** Goodreads re-import with MERGE fills read #1's empty rating and never overwrites a set one (#230, Task 10's promise). */
    @Test fun goodreadsMerge_backfillsAnEmptyRatingOnly() = runTest { /* two books matched by ISBN, one
       rated 6 already; import My Rating 4 for both with MERGE; assert 8 on the unrated one and 6 kept */ }

    /** Each duplicate policy on a read's rating: SKIP leaves it, MERGE fills only empty, REPLACE overwrites (#230). */
    @Test fun readRatings_followTheDuplicatePolicy() = runTest { /* three runs, one per policy */ }
```

Write the bodies in full when executing, following the setup the neighbouring tests in that file use (exporter → `ImportDataUseCase` on the strings). The comments say exactly what each must seed and assert. Run: `./gradlew :shared:jvmTest --tests "*ImportDataUseCaseTest" --tests "*Goodreads*"`. Expected: FAIL.

- [ ] **Step 3: Implement**

- `CSV_SCHEMA_VERSION = 7`, with a KDoc history line: "7 (#230): `read_throughs_export.csv`; `reading_logs_export.csv` gains `read_number`."
- `ReadThroughCsvExporter` writes `media_id, number, status, started_at, finished_at, rating`, with the rating as stars (`9.toStars()` → `4.5`, formatted without a trailing `.0` only if the other exporters do so for decimals; otherwise `4.5` / `4.0`).
- `ReadThroughCsvImporter` validates:
  - `number >= 1`;
  - status is a `ReadingStatus` name;
  - `number > 1` is not `TO_READ`;
  - the rating is empty or `starsToHalves(it) != null`.
- `ReadingLogCsvExporter` appends `read_number`, taken from a `readNumberById: Map<String, Int>` the export use case passes in. `ReadingLogCsvImporter` reads it when the header has it, and defaults to `1` otherwise.
- `ImportWriteDao.importAtomically` gains `readUpserts: List<ReadThroughEntity> = emptyList()`, applied **after** media rows and **before** sessions:
  - for each, `UPDATE read_throughs SET status, startedAt, finishedAt, rating WHERE mediaId AND number`;
  - when 0 rows changed, insert it.

  Sessions carry a `readNumber`. Resolve it to the read's id inside the transaction with `@Query("SELECT id FROM read_throughs WHERE mediaId = :mediaId AND number = :number") suspend fun readIdFor(mediaId: String, number: Int): String?`. Replace Task 1's `latestReadId` session path with `readIdFor(session.mediaId, readNumber)`, failing loudly (`checkNotNull`) when missing.
- `ImportDataUseCase` builds `readUpserts` by applying the duplicate policy per `(mediaId, number)` against the existing reads:
  - `SKIP` leaves an existing read;
  - `MERGE` fills only its null fields (`rating`, `startedAt`, `finishedAt`; `status` is never null, so it keeps the existing one);
  - `REPLACE` overwrites.
- For a **v ≤ 6** file (no reads file), each library row's `status`/`finished_at` becomes a read #1 upsert under the same policy, and every session's `readNumber` is 1.
- For a v7 file, the library row's `status`/`finished_at` (latest read) is ignored in favour of the reads file.
- Goodreads: `My Rating` 1..5 → `rating = stars * 2` on the read #1 upsert, and `0`/blank → null. Remove `MY_RATING` from the "not imported" notice (it keeps `BOOKSHELVES` and `READ_COUNT`) and update that KDoc.

- [ ] **Step 4: Run and commit**

Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest`. Expected: PASS, including the existing round-trip tests and `SampleDataSeed`-style tests that import `docs/sample-data`, which is v ≤ 6.

`git commit -m "Export and import carry reads and ratings; Goodreads My Rating (#230)"`

---

### Task 5: Drop `book_details.status` and `finishedAt`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/entities/BookDetailsEntity.kt` (remove both fields and their KDoc; add a line pointing to `ReadThroughEntity`)
- Modify: `Migrations.kt` (`MIGRATION_7_8` also rebuilds `book_details`)
- Modify: every remaining construction or read of those fields: `BookWriteDao`, `BookRepository.addBook`/`updateBookMetadata`, `ImportDataUseCase.freshDetails`/merge, `ImportWriteDao`, `TestFixtures.sampleBookDetails`, `TestBooks.insertTestBook`, app previews and goldens' fixtures
- Regenerated in place: `shared/schemas/.../8.json` (unreleased, so editing is allowed)
- Test: `MigrationTest` (asserts the columns are gone)

- [ ] **Step 1: Failing migration assertion**

Add to `migrate7To8_everyBookGetsReadOne_withItsStatusAndDates`, after the reads assertion:

```kotlin
            db.prepare("PRAGMA table_info(book_details)").use { s ->
                val cols = mutableListOf<String>()
                while (s.step()) cols += s.getText(1)
                assertFalse("status" in cols || "finishedAt" in cols, "book_details keeps $cols")
            }
```

Run it. Expected: FAIL.

- [ ] **Step 2: Remove the fields and rebuild the table**

- Delete `status` and `finishedAt` from `BookDetailsEntity`.
- Rebuild (`./gradlew :shared:compileKotlinJvm`) so `8.json` regenerates.
- At the end of `MIGRATION_7_8`'s body, after the reads are created from `book_details`, append this (verify the DDL against the regenerated `8.json`):

```kotlin
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_details_new` (`mediaId` TEXT NOT NULL, `isbn` TEXT, " +
                        "`format` TEXT NOT NULL, `totalPages` INTEGER, `trackingMode` TEXT NOT NULL, `authors` TEXT, " +
                        "PRIMARY KEY(`mediaId`), FOREIGN KEY(`mediaId`) REFERENCES `media_items`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `book_details_new` (`mediaId`, `isbn`, `format`, `totalPages`, `trackingMode`, `authors`) " +
                        "SELECT `mediaId`, `isbn`, `format`, `totalPages`, `trackingMode`, `authors` FROM `book_details`",
                )
                connection.execSQL("DROP TABLE `book_details`")
                connection.execSQL("ALTER TABLE `book_details_new` RENAME TO `book_details`")
```

Update `MIGRATION_7_8`'s KDoc: step 3 removes the moved columns. Delete "left in place here".

- [ ] **Step 3: Fix every compile error by moving status to the read**

- `addBook(status = …)` passes the status to `insertBookAtomically(…, firstReadStatus = status)`, which inserts read #1 with it. Change `insertFirstReadIfMissing`'s call sites to take status and `finishedAt` from their new parameters instead of `bookDetails.status`.
- `updateBookDetailsMetadata` drops `status`/`finishedAt` from its `UPDATE`; `updateLatestReadStatus` (Task 2) is now the only status write.
- `ImportDataUseCase.freshDetails` drops them. Task 4's read upserts already carry status.
- `sampleBookDetails` drops its `status`/`finishedAt` parameters; `insertTestBook` keeps them and puts them on the read.
- The test call sites still passing `status = …` to `sampleBookDetails` move to `insertTestBook(…, status = …)`.

Run `grep -rn "sampleBookDetails(.*status\|BookDetailsEntity(.*status" shared/src app/src` until it is empty.

- [ ] **Step 4: Run and commit**

Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS.

Update the AGENTS.md ledger row to: `MIGRATION_7_8 — read_throughs; sessions grouped by read; book status and finish date move to the read (#230)`. Also update `AppDatabase`'s v8 KDoc.

`git commit -m "Read-throughs: book status and finish date live only on the read (#230)"`

---

### Task 6: The stars component and its icons

**Files:**
- Create: `app/src/main/res/drawable/ic_star_filled.xml`, `ic_star_outline.xml`, `ic_star_half.xml`, copied from Material Symbols Outlined. Set `android:autoMirrored="true"` on the half star.
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/RatingStars.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/components/RatingStarsTest.kt`

**Interfaces:**
- Produces:

  ```kotlin
  @Composable fun RatingStars(halves: Int?, modifier: Modifier = Modifier, starSize: Dp = 20.dp, onRate: ((Int?) -> Unit)? = null)
  ```

  `onRate == null` is read-only. Otherwise:
  - tapping the left half of star *i* (0-based) rates `i*2+1`;
  - the right half rates `i*2+2`;
  - tapping the current value clears it (`null`).

- [ ] **Step 1: Fetch the icons**

```bash
for f in "star/materialsymbolsoutlined/star_fill1_24px.xml:ic_star_filled" \
         "star/materialsymbolsoutlined/star_24px.xml:ic_star_outline" \
         "star_half/materialsymbolsoutlined/star_half_fill1_24px.xml:ic_star_half"; do
  src=${f%%:*}; dst=${f##*:}
  curl -fsSL "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/$src" \
    -o "app/src/main/res/drawable/$dst.xml"
done
```

Open each file:
- confirm it is a `<vector>`;
- set `android:tint="?attr/colorControlNormal"`, or remove the tint, matching the repo's existing ISBN copy icon drawable;
- add `android:autoMirrored="true"` to `ic_star_half.xml`.

- [ ] **Step 2: Failing Robolectric tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class RatingStarsTest {
    @get:Rule val rule = createComposeRule()
    private var rated: Int? = -1

    private fun show(halves: Int?, editable: Boolean = true) = rule.setContent {
        MediaTrackerTheme(dynamicColor = false) {
            RatingStars(halves = halves, starSize = 40.dp, onRate = if (editable) ({ rated = it }) else null,
                modifier = Modifier.testTag("stars"))
        }
    }

    @Test fun leftHalfOfTheFourthStar_ratesThreeAndAHalf() {
        show(null)
        rule.onNodeWithTag("stars").performTouchInput { click(Offset(width * (3 * 2 + 0.5f) / 10f, height / 2f)) }
        assertEquals(7, rated)
    }

    @Test fun rightHalfOfTheFifthStar_ratesFive() {
        show(null)
        rule.onNodeWithTag("stars").performTouchInput { click(Offset(width * 9.5f / 10f, height / 2f)) }
        assertEquals(10, rated)
    }

    @Test fun tappingTheCurrentValue_clearsIt() {
        show(9)
        rule.onNodeWithTag("stars").performTouchInput { click(Offset(width * 8.5f / 10f, height / 2f)) }
        assertEquals(null, rated)
    }

    @Test fun talkBack_readsTheValue_andCanStepIt() {
        show(9)
        rule.onNodeWithTag("stars")
            .assert(hasContentDescription("Your rating, 4.5 of 5"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        assertEquals(10, rated)
    }

    @Test fun readOnly_hasNoActions_andSaysNotRated() {
        show(null, editable = false)
        rule.onNodeWithTag("stars").assert(hasContentDescription("Not rated")).assert(hasNoClickAction())
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*RatingStarsTest"`. Expected: a compile FAIL.

- [ ] **Step 3: Implement `RatingStars`**

```kotlin
/**
 * Five stars for a rating in half-steps (#230). It draws the Material Symbols star drawables; the
 * half star mirrors in right-to-left languages. With [onRate], a tap on a star's left half rates a
 * half, on its right half a whole, and on the current value clears it. TalkBack reads one node
 * ("Your rating, 4.5 of 5") that steps in half stars through progress semantics.
 */
@Composable
fun RatingStars(
    halves: Int?,
    modifier: Modifier = Modifier,
    starSize: Dp = 20.dp,
    onRate: ((Int?) -> Unit)? = null,
) {
    val value = halves ?: 0
    val description =
        if (halves == null) {
            stringResource(R.string.rating_none)
        } else {
            stringResource(R.string.rating_value, formatStars(halves))
        }
    val edit =
        if (onRate == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(halves) {
                    detectTapGestures { offset ->
                        val tapped = (offset.x / size.width * 10).toInt().coerceIn(0, 9) + 1
                        onRate(if (tapped == halves) null else tapped)
                    }
                }.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value / 2f, 0f..5f, steps = 9)
                    setProgress { target ->
                        onRate((target * 2).roundToInt().coerceIn(0, 10).takeIf { it > 0 })
                        true
                    }
                }
        }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description }.then(edit),
        horizontalArrangement = Arrangement.spacedBy(starSize / 8),
    ) {
        repeat(5) { i ->
            val icon =
                when ((value - i * 2).coerceIn(0, 2)) {
                    2 -> R.drawable.ic_star_filled
                    1 -> R.drawable.ic_star_half
                    else -> R.drawable.ic_star_outline
                }
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (value > i * 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** "4.5" / "4" in the viewer's locale. */
@Composable
internal fun formatStars(halves: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (halves % 2 == 0) (halves / 2).toString() else String.format(locale, "%.1f", halves / 2.0)
}
```

`clearAndSetSemantics` replaces the semantics of the modifiers *before* it in the chain. Apply `edit`'s `.semantics` *after* it, as shown. If the TalkBack test still finds no `SetProgress`, merge `contentDescription` into the same `semantics` block instead. Keep the touch target at least 48dp tall: `starSize` 40.dp in editable uses, plus `Modifier.heightIn(min = 48.dp)` when `onRate != null`.

Strings:
- `rating_none`: "Not rated"
- `rating_value`: "Your rating, %1$s of 5"
- `rating_yours`: "Yours"
- `rating_not_yet`: "Not rated yet"
- `rating_average_of_reads`: "%1$s average of %2$d reads · latest %3$s"

- [ ] **Step 4: Run and commit**

Run: `./gradlew :app:testDebugUnitTest --tests "*RatingStarsTest"`. Expected: PASS.

Sabotage check: make the left-half tap rate a whole star, see `leftHalfOfTheFourthStar_ratesThreeAndAHalf` fail, then revert.

`git commit -m "Rating stars: Material Symbols, half taps, TalkBack steps (#230)"`

---

### Task 7: Book detail — your rating, reads in the history tab, Read again, Rate this read

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/BookDetailViewModel.kt` (add `readAgain()`, `removeRead(readId)`, `rateRead(readId, halves)`, `pendingRatePrompt`, `dismissRatePrompt()`; `updateStatus` sets the prompt when moving to Finished or Did not finish)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/BookDetailUiState.kt` (`reads`, `latestReadSessions`, `currentProgress` from the latest read only, `ratePrompt: ReadThroughEntity?`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/ViewModelFactories` (or wherever `BookDetailViewModel` is constructed in `:app` `ui/ViewModelFactories.kt`), passing `ReadThroughRepository`
- Modify: `app/.../ui/components/DetailHeader.kt` (an optional `yourRating: @Composable (() -> Unit)? = null` slot under the community rating row)
- Modify: `app/.../ui/screens/BookDetailsTab.kt` (header: the "Yours" row; the status menu gains Read again)
- Modify: `app/.../ui/screens/BookReadingHistoryTab.kt` (sections per read)
- Modify: `app/.../ui/screens/BookSessionDialogs.kt` (`RateReadDialog`)
- Modify: `app/.../ui/screens/BookDetailScreen.kt` (wire the callbacks)
- Modify: `app/.../ui/screens/BookDetailScreenPreviews.kt`
- Modify: `app/.../ui/TestTags.kt` (`BookDetail.READ_CARD(number)`, `RATE_DIALOG`, `READ_AGAIN`)
- Test: `shared/src/jvmTest/.../BookDetailViewModelTest.kt`; `app/src/test/.../BookDetailScreenTest.kt`

**Interfaces:**
- Consumes: Task 2's `ReadThroughRepository`, `Book.status`/`finishedAt`/`rating`, and Task 6's `RatingStars`.
- Produces:
  - `BookDetailUiState.Ready`:
    - `reads: List<ReadThroughEntity>`
    - `ratePrompt: ReadThroughEntity?`
    - derived `latestRead`, `status`, `finishedAt`, `titleRating`, `latestReadSessions` (sessions whose `readThroughId == latestRead.id`) and `currentProgress = latestReadSessions.firstOrNull()?.endUnit`
  - The VM functions listed above.

- [ ] **Step 1: Failing VM tests** (jvmTest `BookDetailViewModelTest`; follow that file's setup)

```kotlin
    @Test fun finishingAsks_rateThisRead_forThatRead() = runTest {
        val readOne = db.insertTestBook("b", status = ReadingStatus.READING)
        val vm = newViewModel("b")
        vm.uiState.first { it is BookDetailUiState.Ready }

        vm.updateStatus(ReadingStatus.FINISHED)

        val s = vm.uiState.first { (it as? BookDetailUiState.Ready)?.ratePrompt != null } as BookDetailUiState.Ready
        assertEquals(readOne, s.ratePrompt!!.id)
    }

    @Test fun dnfAlsoAsks_butReadingDoesNot() = runTest { /* DNF → prompt; FINISHED→READING → no prompt, via runCurrent() */ }

    @Test fun rateRead_savesAndClosesThePrompt() = runTest {
        val readOne = db.insertTestBook("b", status = ReadingStatus.READING)
        val vm = newViewModel("b")
        vm.uiState.first { it is BookDetailUiState.Ready }
        vm.updateStatus(ReadingStatus.FINISHED)
        vm.uiState.first { (it as? BookDetailUiState.Ready)?.ratePrompt != null }

        vm.rateRead(readOne, 9)

        val s = vm.uiState.first { (it as? BookDetailUiState.Ready)?.let { r -> r.ratePrompt == null && r.titleRating != null } == true }
            as BookDetailUiState.Ready
        assertEquals(4.5, s.titleRating!!.latestStars)
    }

    @Test fun readAgain_progressStartsFresh() = runTest {
        db.insertTestBook("b", status = ReadingStatus.FINISHED)
        db.readingSessionDao().insertIntoLatestRead(sampleReadingSession(mediaId = "b", endUnit = 300.0))
        val vm = newViewModel("b")
        vm.uiState.first { (it as? BookDetailUiState.Ready)?.currentProgress == 300.0 }

        vm.readAgain()

        val s = vm.uiState.first { (it as? BookDetailUiState.Ready)?.reads?.size == 2 } as BookDetailUiState.Ready
        assertNull(s.currentProgress)
        assertEquals(ReadingStatus.READING, s.status)
    }
```

Run: `./gradlew :shared:jvmTest --tests "*BookDetailViewModelTest"`. Expected: FAIL.

- [ ] **Step 2: Implement the VM and state**

- `uiState` combines `bookRepository.observeBookDetail(bookId)` (now carrying `reads`), the sessions, and `localState`.
- `LocalState` gains `ratePromptReadId: String?`; `Ready.ratePrompt` is the read with that id.
- `updateStatus(status)`: on `Resource.Success`, when `status` is `FINISHED` or `DNF`, set `ratePromptReadId` to the latest read's id, read from the last `Ready` state.
- `rateRead(id, halves)`: calls `readThroughRepository.setRating`, then clears the prompt.
- `dismissRatePrompt()`: clears the prompt.
- `readAgain()` / `removeRead(id)`: surface a `Resource.Error` into `errorMessage`, the same as the other actions.

- [ ] **Step 3: Failing screen tests** (Robolectric `BookDetailScreenTest`; stateless screen with fabricated state)

Cover each of these:
- the header shows "Yours" with "4.5 average of 2 reads · latest 5.0" for reads rated 8 and 10, nothing extra for one rated read, and "Not rated yet" for none;
- the history tab with two reads shows read #2's card open with its sessions, and read #1 folded ("Read #1 · Finished …") without its sessions. Tapping it expands to show them, asserted with `assertIsDisplayed` (not `assertExists`, Graphban `m_21e0e1df6d`);
- the status menu offers "Read again" when the status is Finished or Did not finish and not when Reading, and choosing it calls `onReadAgain`;
- on read #2, the menu does not offer "To read";
- "Remove this read" sits in read #2's card menu when it has no sessions, and is absent on read #1;
- the `RateReadDialog`, with a `ratePrompt` in the state, shows "Rate this read?". Tapping the stars, then Save, calls `onRateRead(id, halves)`; Skip calls `onDismissRatePrompt`.

- [ ] **Step 4: Implement the screens**

- **`DetailHeader`:** an optional `yourRating` slot. `BookDetailHeaderSection` passes a `Column` holding:
  - a `Row` with the "Yours" label and `RatingStars(halves = titleRating?.let { (it.averageStars * 2).roundToInt() })`, read-only;
  - the text line: `rating_average_of_reads` when `ratedReads >= 2`, nothing when it is 1, and `rating_not_yet` when it is null.
- **`readingHistorySection`:** takes `reads` and all sessions.
  - For the latest read: a header item (a `Card`) with:
    - "Read #n" (`R.string.read_number`);
    - its status and dates;
    - an editable `RatingStars` calling `onRateRead(read.id, it)`;
    - an overflow menu with "Remove this read" when `number > 1` and it has no sessions.

    Then that read's sessions through the existing timeline code, unchanged.
  - For each earlier read (descending): a folded card ("Read #n · Finished <date>", read-only stars, "N sessions · total time"), keyed by read id, with an expanded state held in `rememberSaveable`. Expanded, it renders the same header plus the timeline for that read's sessions.
  - Keep `TimelineEntry` keys unique across reads by prefixing them with the read id.
- **Status menu (`bookStatusControl`):** add "Read again" (a separate entry after a divider) when the status is Finished or Did not finish. Hide "To read" when `latestRead.number > 1`.
- **`RateReadDialog`:** an `AlertDialog` with the "Rate this read?" title, the book title and "read #n" line, `RatingStars(starSize = 40.dp)` holding a local value, a "4.5" readout (`formatStars`), and Skip / Save. Save is enabled only once a value is chosen.

Strings:
- `read_number`: "Read #%1$d"
- `read_again`: "Read again"
- `read_remove`: "Remove this read"
- `rate_read_title`: "Rate this read?"
- `rate_read_body`: "%1$s, read #%2$d"
- `rate_read_skip`: "Skip"
- `rate_read_save`: "Save"
- `read_sessions_summary`: plurals "%1$d session · %2$s" / "%1$d sessions · %2$s"

Add the previews: a two-read book, and the dialog.

- [ ] **Step 5: Run and commit**

Run: `./gradlew :shared:jvmTest :app:testDebugUnitTest`. Expected: PASS.

Sabotage check: render read #1 unfolded by default and see the folded-card test fail, then revert.

`git commit -m "Book detail: your rating, reads in history, Read again, Rate this read (#230)"`

---

### Task 8: Goldens, docs, changelog, device

**Files:**
- Modify: `app/src/test/java/.../ui/goldens/BookDetailScreenGoldenTest.kt` (a two-read, rated fixture)
- Re-record: `app/src/test/screenshots/book-detail*.png`, plus a new `book-detail-reads.png`
- Modify: `CHANGELOG.md` `[Unreleased]`
- Modify: `ROADMAP.md` Task 10
- Modify: `AGENTS.md` if anything there still says a book's status lives on `book_details`

- [ ] **Step 1: Goldens**

Give `BookDetailScreenGoldenTest` a fixture with:
- read #1: Finished, rated 8, 3 sessions;
- read #2: Reading, unrated, 2 sessions.

Make it self-consistent (Graphban `m_ab00cbec8a`): the header says "4.0" with one rated read, and the sessions belong to their reads. Add `bookDetail_reads`, which switches to the history tab inside `alsoAssert` and asserts "Read #2" and "Read #1 · Finished" are shown.

Record: `./gradlew :app:recordRoborazziDebug --tests "*BookDetailScreenGoldenTest*"`. Open every PNG it wrote and check it. Commit the PNGs alone: `git commit -m "Goldens: book detail with reads and your rating (#230)"`.

- [ ] **Step 2: Docs and changelog**

`CHANGELOG.md` `[Unreleased]`:
- `### Added`: "Re-reads: start reading a book again from its status menu. Each read keeps its own dates, sessions and rating, and earlier reads fold away in the reading history. (#230)"
- `### Added`: "Rate each read of a book in half stars, when you finish it or any time after. The book shows your average and your latest rating. Goodreads imports bring your star ratings in. (#230)"
- `### Changed`: "Your reading goal now counts books new to you; re-reads still count as books finished. (#230)"
- `### Internal`: "Schema v8: `read_throughs`; sessions belong to a read; book status and finish date moved onto the read. CSV schema 7: `read_throughs_export.csv`, and `read_number` on the reading log. (#230)"

`ROADMAP.md` Task 10: mark read-throughs and per-read ratings delivered in #230 (part of #220). Point film and show watch-throughs at #231 and the rating stats at #232.

`git commit -m "Docs: read-throughs in CHANGELOG, ROADMAP (#230)"`

- [ ] **Step 3: Gate, device, hand check**

Run the gate, from Global Constraints. Expected: all green.

Then `adb devices`. With the phone attached:
- `./gradlew :app:connectedDebugAndroidTest`; record the tally;
- `./gradlew :app:seedDebugDevice`.

Hand check on the phone:
1. Open a finished book and rate it from the history card.
2. Choose Read again. Read #2 is Reading, progress is empty, and read #1 is folded.
3. Log a session, choose Finished, and rate 5 in the prompt.
4. The header shows the average and the latest.
5. Stats' Year: books finished includes the re-read, and the goal does not.
6. The year in review shows the same.
7. Choose Read again, then Remove this read; the book returns to Finished.

---

## Self-review notes (done while writing)

- **Spec coverage:**
  - §1 → Tasks 1 and 5; §2 → Tasks 1 and 5.
  - §3 → Tasks 2 and 7: status menu, Read again, Remove this read, sessions, progress, `finishedAt`, rating.
  - §4 → Tasks 6 and 7; §5 → Task 3; §6 → Task 4; §8 → every task.
  - §9 → Tasks 1, 5 and 8.
- **Ruling:** read #1's foreign key targets `media_items` rather than `book_details` (the spec's wording). Sessions already reference `media_items`, deleting a book cascades the same way, and a book missing its details row (Review Focus 1) can still own reads. Cost if wrong: a one-line FK change before v8 ships.
- **Type names used across tasks:**
  - `ReadThroughEntity`, `ReadThroughDao`, `ReadThroughRepository`, `TitleRating`, `titleRating`, `starsToHalves`, `toStars`, `isValidRating`;
  - `insertIntoLatestRead`, `insertFirstReadIfMissing`, `updateLatestReadStatus`, `readIdFor`, `insertTestBook`;
  - `PeriodStatsRow.firstFinishes`, `RatingStars`, `formatStars`.
