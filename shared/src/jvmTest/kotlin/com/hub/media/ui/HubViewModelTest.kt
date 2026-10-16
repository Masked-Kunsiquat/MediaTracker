package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.observeIncludeSpecials
import com.hub.media.features.settings.data.setIncludeSpecials
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [HubViewModel] (#126) against a real in-memory database: the three numbers on the hub's cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var books: BookRepository
    private lateinit var films: MovieRepository
    private lateinit var tv: TVShowRepository
    private lateinit var settings: SettingsRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        books = BookRepository(db)
        films = MovieRepository(db)
        tv = TVShowRepository(db)
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = viewModels.track(HubViewModel(MediaRepository(db), tv, settings))

    private suspend fun book(
        title: String,
        status: ReadingStatus,
    ): String {
        val added = books.addBook(title = title, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(added)
        books.updateReadingStatus(added.data, status)
        return added.data
    }

    private suspend fun film(
        title: String,
        status: WatchStatus,
    ) {
        assertIs<Resource.Success<String>>(films.addMovie(title = title, status = status))
    }

    /** A started show, as in [UpNextViewModelTest]: one special, [regular] episodes, episode 1 watched. */
    private suspend fun startedShow(regular: Int): String {
        val added =
            tv.addShow(
                title = "Severance",
                seasons = listOf(SeasonQuickFill(0, episodeCount = 1), SeasonQuickFill(1, episodeCount = regular)),
            )
        assertIs<Resource.Success<String>>(added)
        val first = db.episodeDao().getByMediaIdAndSeason(added.data, 1).first()
        tv.setEpisodeWatched(first.id, watched = true)
        return added.data
    }

    @Test
    fun counts_readingBooks_watchlistFilms_andShowsWithAnEpisodeUpNext() =
        runTest {
            book("Dune", ReadingStatus.READING)
            book("Piranesi", ReadingStatus.READING)
            book("Emma", ReadingStatus.FINISHED)
            film("Solaris", WatchStatus.WATCHLIST)
            film("Alien", WatchStatus.WATCHED)
            startedShow(regular = 3)

            val state = newViewModel().uiState.first { !it.isLoading && it.showsUpNext == 1 }

            assertEquals(2, state.readingBooks)
            assertEquals(1, state.watchlistFilms)
            assertEquals(1, state.showsUpNext)
        }

    @Test
    fun anEmptyLibrary_isZeroEverywhere_andNotLoading() =
        runTest {
            val state = newViewModel().uiState.first { !it.isLoading }

            assertEquals(HubUiState(isLoading = false), state)
        }

    @Test
    fun aStatusChange_movesTheCount() =
        runTest {
            val id = book("Dune", ReadingStatus.TO_READ)
            val vm = newViewModel()
            vm.uiState.first { !it.isLoading && it.readingBooks == 0 }

            books.updateReadingStatus(id, ReadingStatus.READING)

            assertEquals(1, vm.uiState.first { it.readingBooks == 1 }.readingBooks)
        }

    @Test
    fun showsUpNext_followsIncludeSpecials_likeTheUpNextScreen() =
        runTest {
            // Season 1's only episode watched, the special not: up next exists only with specials on.
            val added =
                tv.addShow(
                    title = "Specials only",
                    seasons = listOf(SeasonQuickFill(0, episodeCount = 1), SeasonQuickFill(1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(added)
            tv.setEpisodeWatched(
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(added.data, 1)
                    .first()
                    .id,
                watched = true,
            )
            val vm = newViewModel()
            val before = vm.uiState.first { !it.isLoading }.showsUpNext

            settings.setIncludeSpecials(!settings.observeIncludeSpecials().first())

            val after = vm.uiState.first { it.showsUpNext != before }.showsUpNext
            assertEquals(setOf(0, 1), setOf(before, after))
        }

    @Test
    fun aFailedRead_isLoadFailed_notACrash() =
        runTest {
            val vm = newViewModel()
            db.close()

            assertTrue(vm.uiState.first { it.loadFailed }.loadFailed)
        }
}
