package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.features.media.domain.DeleteMediaUseCase
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.domain.WatchedWhen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import com.hub.media.ui.MovieDetailUiState.Ready as Ready

/**
 * Film watch history on the detail screen (#192): becoming Watched asks when, leaving it keeps the
 * history with an Undo, and viewings can be added, edited and deleted (merged Undo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MovieRepository
    private lateinit var tempDir: String
    private val viewModels = ViewModelRegistry()
    private val now = Instant.parse("2026-06-01T18:00:00Z")

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repository =
            MovieRepository(
                db,
                object : Clock {
                    override fun now(): Instant = this@MovieDetailViewModelTest.now
                },
                zone = TimeZone.UTC,
            )
        tempDir = runBlocking { createTestTempDir() }
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        runBlocking { cleanupTestTempDir(tempDir) }
        Dispatchers.resetMain()
    }

    private suspend fun ready(watched: Boolean = false): MovieDetailViewModel {
        val status = if (watched) WatchStatus.WATCHED else WatchStatus.WATCHLIST
        val id = (repository.addMovie(title = "Heat", status = status) as Resource.Success).data
        val vm =
            viewModels.track(
                MovieDetailViewModel(id, repository, DeleteMediaUseCase(db, LocalImageStorageManager(tempDir))),
            )
        vm.uiState.first { it is Ready }
        return vm
    }

    private suspend fun MovieDetailViewModel.awaitReady(predicate: (Ready) -> Boolean): Ready =
        uiState.first { it is Ready && predicate(it) } as Ready

    @Test
    fun becomingWatched_prompts_thenConfirmAddsViewing() =
        runTest {
            val vm = ready()
            vm.requestStatus(WatchStatus.WATCHED)
            vm.awaitReady { it.pendingWatched }
            vm.confirmWatched(WatchedWhen.Now)
            // The film and its viewings are separate flows: wait for both, not the first to land.
            val s = vm.awaitReady { it.viewings.size == 1 && it.movie.details?.status == WatchStatus.WATCHED }
            assertFalse(s.pendingWatched)
            assertEquals(now, s.movie.details?.watchedAt)
        }

    @Test
    fun dismissWatched_changesNothing() =
        runTest {
            val vm = ready()
            vm.requestStatus(WatchStatus.WATCHED)
            vm.awaitReady { it.pendingWatched }
            vm.dismissWatched()
            val s = vm.awaitReady { !it.pendingWatched }
            assertEquals(WatchStatus.WATCHLIST, s.movie.details?.status)
            assertEquals(0, s.viewings.size)
        }

    @Test
    fun leavingWatched_keepsHistory_andOffersUndo() =
        runTest {
            val vm = ready(watched = true)
            vm.requestStatus(WatchStatus.WATCHING)
            val s = vm.awaitReady { it.statusUndo != null && it.movie.details?.status == WatchStatus.WATCHING }
            assertEquals(1, s.viewings.size)
            assertEquals(WatchStatus.WATCHED, s.statusUndo!!.previous)
        }

    @Test
    fun undoLeavingWatched_restoresWithoutNewViewing() =
        runTest {
            val vm = ready(watched = true)
            vm.requestStatus(WatchStatus.WATCHING)
            vm.awaitReady { it.statusUndo != null }
            vm.undoStatus()
            val s = vm.awaitReady { it.movie.details?.status == WatchStatus.WATCHED && it.statusUndo == null }
            assertEquals(1, s.viewings.size)
            assertEquals(now, s.movie.details?.watchedAt)
        }

    @Test
    fun watchedAgain_addsSecondViewing() =
        runTest {
            val vm = ready(watched = true)
            vm.requestWatchedAgain()
            vm.awaitReady { it.pendingWatchedAgain }
            vm.confirmWatchedAgain(WatchedWhen.On(LocalDate(2024, 3, 3)))
            val s = vm.awaitReady { it.viewings.size == 2 }
            assertFalse(s.pendingWatchedAgain)
        }

    @Test
    fun deletingLast_movesToWatchlist_andUndoRestoresBoth() =
        runTest {
            val vm = ready(watched = true)
            val log = vm.awaitReady { it.viewings.size == 1 }.viewings.single()
            vm.deleteViewing(log)
            vm.awaitReady { it.movie.details?.status == WatchStatus.WATCHLIST && it.viewingUndo != null }
            vm.undoViewing()
            val s = vm.awaitReady { it.movie.details?.status == WatchStatus.WATCHED && it.viewings.size == 1 }
            assertNull(s.viewingUndo)
        }

    @Test
    fun twoDeletes_mergeIntoOneUndo() =
        runTest {
            val vm = ready(watched = true)
            vm.requestWatchedAgain()
            vm.awaitReady { it.pendingWatchedAgain }
            vm.confirmWatchedAgain(WatchedWhen.On(LocalDate(2024, 3, 3)))
            val logs = vm.awaitReady { it.viewings.size == 2 }.viewings
            vm.deleteViewing(logs[0])
            vm.awaitReady { it.viewings.size == 1 && it.viewingUndo != null }
            vm.deleteViewing(logs[1])
            val s = vm.awaitReady { it.viewings.isEmpty() && it.viewingUndo?.logs?.size == 2 }
            assertEquals(WatchStatus.WATCHED, s.viewingUndo!!.statusBefore)
            vm.undoViewing()
            vm.awaitReady { it.viewings.size == 2 && it.movie.details?.status == WatchStatus.WATCHED }
        }

    @Test
    fun editViewingDate_movesWatchedAt() =
        runTest {
            val vm = ready(watched = true)
            val log = vm.awaitReady { it.viewings.size == 1 }.viewings.single()
            vm.editViewingDate(log, WatchedWhen.On(LocalDate(2024, 3, 3)))
            vm.awaitReady { it.movie.details?.watchedAt == Instant.parse("2024-03-03T12:00:00Z") }
        }
}
