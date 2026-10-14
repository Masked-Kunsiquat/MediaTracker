package com.hub.media.features.portability.domain

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #162: staged backup snapshots and copied restore files that outlived their flow -- a process
 * death inside a picker, or a restore rejected before staging -- are removed on the next launch,
 * and nothing else in either directory is touched.
 */
class SweepStaleTempFilesUseCaseTest {
    private val root = Files.createTempDirectory("sweep-test").toFile()
    private val databases = File(root, "databases").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }
    private val dbFile = File(databases, "media_tracker.db")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun touch(
        dir: File,
        name: String,
    ) = File(dir, name).apply { writeText("x") }

    @Test
    fun removesStaleTempFiles_andLeavesEverythingElse() =
        runTest {
            val live = touch(databases, "media_tracker.db")
            val wal = touch(databases, "media_tracker.db-wal")
            val staleBackup = touch(databases, "media_tracker.db.backup-5a5e1adf.tmp")
            val otherDbsBackup = touch(databases, "other.db.backup-1.tmp")
            val staleRestore = touch(cache, "restore-incoming-1760390000000.tmp")
            val unrelatedCache = touch(cache, "cover-cache.tmp")

            val removed = SweepStaleTempFilesUseCase(dbFile.path, cache.path).execute(startedAtMillis = Long.MAX_VALUE)

            assertEquals(2, removed)
            assertFalse(staleBackup.exists())
            assertFalse(staleRestore.exists())
            // Positive controls: the live database and anything not ours are untouched.
            assertTrue(live.exists())
            assertTrue(wal.exists())
            assertTrue(otherDbsBackup.exists())
            assertTrue(unrelatedCache.exists())
        }

    @Test
    fun missingDirectories_removeNothing() =
        runTest {
            val gone = File(root, "nope")
            assertEquals(
                0,
                SweepStaleTempFilesUseCase(
                    File(gone, "media_tracker.db").path,
                    gone.path,
                ).execute(startedAtMillis = Long.MAX_VALUE),
            )
        }

    @Test
    fun filesCreatedSinceTheProcessStarted_belongToAnActiveFlow_andAreKept() =
        runTest {
            // CodeRabbit (#198): the sweep runs asynchronously at launch, so a backup started at once
            // could stage its snapshot before the sweep lists the directory.
            val startedAt = 1_000_000_000_000L
            val leftover =
                touch(databases, "media_tracker.db.backup-old.tmp").apply {
                    setLastModified(
                        startedAt - 60_000,
                    )
                }
            val active =
                touch(
                    databases,
                    "media_tracker.db.backup-new.tmp",
                ).apply { setLastModified(startedAt + 1_000) }
            val activeRestore = touch(cache, "restore-incoming-2.tmp").apply { setLastModified(startedAt + 1_000) }

            assertEquals(1, SweepStaleTempFilesUseCase(dbFile.path, cache.path).execute(startedAtMillis = startedAt))
            assertFalse(leftover.exists(), "positive control: a file from before this process is removed")
            assertTrue(active.exists())
            assertTrue(activeRestore.exists())
        }
}
