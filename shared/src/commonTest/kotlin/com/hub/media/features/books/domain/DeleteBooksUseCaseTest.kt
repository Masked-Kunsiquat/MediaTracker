package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.newId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Covers [DeleteBooksUseCase] (ROADMAP Task 14 Phase B), against a real database and real files.
 *
 * ### Why both directions are tested, deliberately
 * The ROADMAP calls this out and it is worth restating: a cleanup that only proves "unreferenced
 * files are removed" passes while silently breaking every book that shares a cover, and a cleanup
 * that only proves "shared files survive" passes while never deleting anything at all. Each failure
 * mode is invisible in the other's test, so neither test is sufficient alone.
 *
 * Real files rather than a fake storage layer, because the thing under test is whether a file is
 * still on disk afterwards -- a fake would only prove the use case called the method it was always
 * going to call.
 */
class DeleteBooksUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var imageStorage: LocalImageStorageManager
    private lateinit var useCase: DeleteBooksUseCase

    @BeforeTest
    fun setUp() = runTest {
        db = testAppDatabase()
        tempDir = createTestTempDir()
        imageStorage = LocalImageStorageManager(tempDir)
        useCase = DeleteBooksUseCase(db, imageStorage)
    }

    @AfterTest
    fun tearDown() = runTest {
        db.close()
        cleanupTestTempDir(tempDir)
    }

    /** Inserts a book, optionally pointing at [coverHash], and returns its media id. */
    private suspend fun insertBook(title: String, coverHash: String? = null): String {
        val id = newId()
        db.mediaItemDao().insert(
            sampleMediaItem(id = id, type = MediaType.BOOK, title = title).copy(coverImageHash = coverHash),
        )
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = id))
        return id
    }

    /** Writes real bytes through the storage manager and returns the content-addressed filename. */
    private suspend fun storeCover(bytes: ByteArray): String =
        imageStorage.saveImage(bytes).getOrThrow()

    private suspend fun coverExists(fileName: String): Boolean =
        com.hub.media.core.database.fileExists("$tempDir/$fileName")

    @Test
    fun execute_coverReferencedOnlyByTheDeletedBook_removesTheFile() = runTest {
        val hash = storeCover(byteArrayOf(1, 2, 3))
        val id = insertBook("Solo", coverHash = hash)
        // Positive control: the file is genuinely there first, so its absence below means it was
        // deleted rather than never written.
        assertTrue(coverExists(hash), "cover must exist before the delete")

        val summary = useCase.execute(listOf(id))

        assertEquals(1, summary.booksDeleted)
        assertEquals(1, summary.coversRemoved)
        assertFalse(coverExists(hash), "nothing references this cover any more, so it must be gone")
    }

    @Test
    fun execute_coverSharedWithASurvivingBook_keepsTheFile() = runTest {
        // The failure this exists to catch: identical artwork is stored once, so deleting one of
        // two books that share it must not blank the other's cover. Invisible in the test above.
        val hash = storeCover(byteArrayOf(9, 9, 9))
        val doomed = insertBook("Doomed", coverHash = hash)
        insertBook("Survivor", coverHash = hash)

        val summary = useCase.execute(listOf(doomed))

        assertEquals(1, summary.booksDeleted)
        assertEquals(0, summary.coversRemoved)
        assertEquals(1, summary.coversKept)
        assertTrue(coverExists(hash), "a surviving book still shows this cover")
    }

    @Test
    fun execute_deletingEveryBookSharingACover_removesTheFileOnceTheLastOneGoes() = runTest {
        // The other half of sharing: keeping the file forever would be just as wrong. Deleting all
        // referencing books in one call must still reclaim it.
        val hash = storeCover(byteArrayOf(4, 5, 6))
        val first = insertBook("First", coverHash = hash)
        val second = insertBook("Second", coverHash = hash)

        val summary = useCase.execute(listOf(first, second))

        assertEquals(2, summary.booksDeleted)
        assertEquals(1, summary.coversRemoved, "one shared file, counted once")
        assertFalse(coverExists(hash))
    }

    @Test
    fun execute_mixedSelection_removesOnlyTheCoversThatBecameUnreferenced() = runTest {
        val sharedHash = storeCover(byteArrayOf(1))
        val loneHash = storeCover(byteArrayOf(2))
        val doomedShared = insertBook("Doomed shared", coverHash = sharedHash)
        insertBook("Surviving shared", coverHash = sharedHash)
        val doomedLone = insertBook("Doomed lone", coverHash = loneHash)

        val summary = useCase.execute(listOf(doomedShared, doomedLone))

        assertEquals(2, summary.booksDeleted)
        assertEquals(1, summary.coversRemoved)
        assertEquals(1, summary.coversKept)
        assertTrue(coverExists(sharedHash), "still referenced by the survivor")
        assertFalse(coverExists(loneHash), "nothing references this one")
    }

    @Test
    fun execute_bookWithNoCover_deletesCleanlyWithNothingToCleanUp() = runTest {
        val id = insertBook("Coverless", coverHash = null)

        val summary = useCase.execute(listOf(id))

        assertEquals(DeleteBooksSummary(booksDeleted = 1, coversRemoved = 0, coversKept = 0), summary)
    }

    @Test
    fun execute_emptySelection_isANoOpRatherThanAnError() = runTest {
        // A selection can be emptied between opening the confirmation and confirming it.
        insertBook("Untouched")

        val summary = useCase.execute(emptyList())

        assertEquals(DeleteBooksSummary(0, 0, 0), summary)
        assertEquals(1, db.mediaItemDao().getAllByType(MediaType.BOOK).size, "nothing was deleted")
    }

    @Test
    fun execute_idThatNoLongerExists_isReportedInTheCountRatherThanFailing() = runTest {
        val real = insertBook("Real")

        val summary = useCase.execute(listOf(real, newId()))

        assertEquals(1, summary.booksDeleted, "the requested end state holds; this is not an error")
    }

    @Test
    fun execute_deletesTheBookRowsThemselves() = runTest {
        // Guards against a cover-cleanup regression that quietly stops deleting books: every other
        // assertion here is about files, and would still pass if the rows survived.
        val keep = insertBook("Keep")
        val drop = insertBook("Drop")

        useCase.execute(listOf(drop))

        val remaining = db.mediaItemDao().getAllByType(MediaType.BOOK)
        assertEquals(listOf(keep), remaining.map { it.id })
    }
}
