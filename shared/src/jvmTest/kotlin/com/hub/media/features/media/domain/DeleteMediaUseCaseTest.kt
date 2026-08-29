package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [DeleteMediaUseCase] against a real database and real files.
 * Consolidated and generalized per Issue #67.
 */
class DeleteMediaUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var imageStorage: LocalImageStorageManager
    private lateinit var useCase: DeleteMediaUseCase

    @BeforeTest
    fun setUp() =
        runTest {
            db = testAppDatabase()
            tempDir = createTestTempDir()
            imageStorage = LocalImageStorageManager(tempDir)
            useCase = DeleteMediaUseCase(db, imageStorage)
        }

    @AfterTest
    fun tearDown() =
        runTest {
            db.close()
            cleanupTestTempDir(tempDir)
        }

    /** Inserts a book, optionally pointing at [coverHash], and returns its media id. */
    private suspend fun insertMedia(
        title: String,
        coverHash: String? = null,
    ): String {
        val id = newId()
        db.mediaItemDao().insert(
            sampleMediaItem(id = id, type = MediaType.BOOK, title = title).copy(coverImageHash = coverHash),
        )
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = id))
        return id
    }

    /** Unwraps a successful result, failing loudly rather than silently skipping assertions. */
    private fun requireSuccess(result: Resource<DeleteMediaSummary>): DeleteMediaSummary {
        assertIs<Resource.Success<DeleteMediaSummary>>(result, "expected a successful delete")
        return result.data
    }

    @Test
    fun execute_againstAClosedDatabase_propagatesCancellationRatherThanSwallowingIt() =
        runTest {
            val id = insertMedia("Doomed")
            db.close()

            assertFailsWith<CancellationException> { useCase.execute(listOf(id)) }
        }

    /** Writes real bytes through the storage manager and returns the content-addressed filename. */
    private suspend fun storeCover(bytes: ByteArray): String = imageStorage.saveImage(bytes).getOrThrow()

    private suspend fun coverExists(fileName: String): Boolean =
        com.hub.media.core.database
            .fileExists("$tempDir/$fileName")

    @Test
    fun execute_coverReferencedOnlyByTheDeletedItem_removesTheFile() =
        runTest {
            val hash = storeCover(byteArrayOf(1, 2, 3))
            val id = insertMedia("Solo", coverHash = hash)
            assertTrue(coverExists(hash), "cover must exist before the delete")

            val summary = requireSuccess(useCase.execute(listOf(id)))

            assertEquals(1, summary.itemsDeleted)
            assertEquals(1, summary.coversRemoved)
            assertFalse(coverExists(hash), "nothing references this cover any more, so it must be gone")
        }

    @Test
    fun execute_coverSharedWithASurvivingItem_keepsTheFile() =
        runTest {
            val hash = storeCover(byteArrayOf(9, 9, 9))
            val doomed = insertMedia("Doomed", coverHash = hash)
            insertMedia("Survivor", coverHash = hash)

            val summary = requireSuccess(useCase.execute(listOf(doomed)))

            assertEquals(1, summary.itemsDeleted)
            assertEquals(0, summary.coversRemoved)
            assertEquals(1, summary.coversKept)
            assertTrue(coverExists(hash), "a surviving item still shows this cover")
        }

    @Test
    fun execute_deletingEveryItemSharingACover_removesTheFileOnceTheLastOneGoes() =
        runTest {
            val hash = storeCover(byteArrayOf(4, 5, 6))
            val first = insertMedia("First", coverHash = hash)
            val second = insertMedia("Second", coverHash = hash)

            val summary = requireSuccess(useCase.execute(listOf(first, second)))

            assertEquals(2, summary.itemsDeleted)
            assertEquals(1, summary.coversRemoved, "one shared file, counted once")
            assertFalse(coverExists(hash))
        }

    @Test
    fun execute_mixedSelection_removesOnlyTheCoversThatBecameUnreferenced() =
        runTest {
            val sharedHash = storeCover(byteArrayOf(1))
            val loneHash = storeCover(byteArrayOf(2))
            val doomedShared = insertMedia("Doomed shared", coverHash = sharedHash)
            insertMedia("Surviving shared", coverHash = sharedHash)
            val doomedLone = insertMedia("Doomed lone", coverHash = loneHash)

            val summary = requireSuccess(useCase.execute(listOf(doomedShared, doomedLone)))

            assertEquals(2, summary.itemsDeleted)
            assertEquals(1, summary.coversRemoved)
            assertEquals(1, summary.coversKept)
            assertTrue(coverExists(sharedHash), "still referenced by the survivor")
            assertFalse(coverExists(loneHash), "nothing references this one")
        }

    @Test
    fun execute_itemWithNoCover_deletesCleanlyWithNothingToCleanUp() =
        runTest {
            val id = insertMedia("Coverless", coverHash = null)

            val summary = requireSuccess(useCase.execute(listOf(id)))

            assertEquals(DeleteMediaSummary(itemsDeleted = 1, coversRemoved = 0, coversKept = 0), summary)
        }

    @Test
    fun execute_emptySelection_isANoOpRatherThanAnError() =
        runTest {
            insertMedia("Untouched")

            val summary = requireSuccess(useCase.execute(emptyList()))

            assertEquals(DeleteMediaSummary(0, 0, 0), summary)
            assertEquals(1, db.mediaItemDao().getAllByType(MediaType.BOOK).size, "nothing was deleted")
        }

    @Test
    fun execute_idThatNoLongerExists_isReportedInTheCountRatherThanFailing() =
        runTest {
            val real = insertMedia("Real")

            val summary = requireSuccess(useCase.execute(listOf(real, newId())))

            assertEquals(1, summary.itemsDeleted, "the requested end state holds; this is not an error")
        }

    @Test
    fun execute_deletesTheItemRowsThemselves() =
        runTest {
            val keep = insertMedia("Keep")
            val drop = insertMedia("Drop")

            useCase.execute(listOf(drop))

            val remaining = db.mediaItemDao().getAllByType(MediaType.BOOK)
            assertEquals(listOf(keep), remaining.map { it.id })
        }
}
