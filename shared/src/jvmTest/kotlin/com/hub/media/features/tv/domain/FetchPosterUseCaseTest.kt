package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.movies.data.MovieRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [FetchPosterUseCase] against real image storage and a real database.
 *
 * The theme of these tests is that **every failure is survivable**. A poster is decoration, the
 * title is already in the library by the time this runs, and a row without artwork is complete.
 */
class FetchPosterUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var storage: LocalImageStorageManager

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        tempDir = runBlocking { createTestTempDir() }
        storage = LocalImageStorageManager(tempDir)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        runBlocking { cleanupTestTempDir(tempDir) }
    }

    private fun useCase(
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): FetchPosterUseCase {
        val engine =
            MockEngine {
                if (status == HttpStatusCode.OK) respond(bytes) else respondError(status)
            }
        return FetchPosterUseCase(
            coverDownloader = CoverImageDownloader(createHttpClient(engine)),
            imageStorage = storage,
            mediaRepository = MediaRepository(db),
        )
    }

    private suspend fun aFilm(): String {
        val result = MovieRepository(db).addMovie(title = "The Matrix")
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    @Test
    fun downloadsThePosterAndRecordsItsHashAgainstTheRow() =
        runTest {
            val mediaId = aFilm()

            val result = useCase().execute(mediaId, "/abc123.jpg")

            assertIs<Resource.Success<Unit>>(result)
            val hash = db.mediaItemDao().getById(mediaId)?.coverImageHash
            assertNotNull(hash, "the row must record the stored file")
            assertTrue(hash.endsWith(".jpg"), hash)
        }

    @Test
    fun itStoresAHashRatherThanAUrl() =
        runTest {
            // AGENTS.md section 4: this app is offline-first. A stored remote URL would make every
            // poster vanish the moment the device lost signal.
            val mediaId = aFilm()

            useCase().execute(mediaId, "/abc123.jpg")

            val hash = db.mediaItemDao().getById(mediaId)?.coverImageHash
            assertTrue(hash != null && !hash.contains("http"), "stored value must not be a URL: $hash")
        }

    @Test
    fun aTitleWithNoPosterIsAnsweredWithoutSpendingARequest() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(byteArrayOf(1))
                }
            val useCase =
                FetchPosterUseCase(
                    coverDownloader = CoverImageDownloader(createHttpClient(engine)),
                    imageStorage = storage,
                    mediaRepository = MediaRepository(db),
                )
            val mediaId = aFilm()

            val result = useCase.execute(mediaId, null)

            assertIs<Resource.Error>(result)
            assertEquals(0, requests, "a title with no artwork must not cost a request")
            assertNull(db.mediaItemDao().getById(mediaId)?.coverImageHash)
        }

    @Test
    fun aFailedDownloadLeavesTheRowWithoutACoverRatherThanBreakingIt() =
        runTest {
            // The row is already a complete, usable library entry. Losing its artwork is a non-event.
            val mediaId = aFilm()

            val result = useCase(status = HttpStatusCode.InternalServerError).execute(mediaId, "/abc.jpg")

            assertIs<Resource.Error>(result)
            assertNull(db.mediaItemDao().getById(mediaId)?.coverImageHash)
            assertEquals("The Matrix", db.mediaItemDao().getById(mediaId)?.title, "the title survives")
        }

    @Test
    fun twoTitlesSharingArtworkShareOneFile() =
        runTest {
            // Content addressing: identical bytes hash to the same name, so a re-run rewrites
            // nothing and a shared image is stored once.
            val first = aFilm()
            val second = aFilm()

            useCase().execute(first, "/abc.jpg")
            useCase().execute(second, "/def.jpg")

            assertEquals(
                db.mediaItemDao().getById(first)?.coverImageHash,
                db.mediaItemDao().getById(second)?.coverImageHash,
            )
        }
}
