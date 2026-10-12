package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.settings.data.SettingsRepository
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
import kotlin.test.assertNotNull

/** [UpNextViewModel] (#89) against a real in-memory database. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpNextViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var tv: TVShowRepository
    private lateinit var settings: SettingsRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        tv = TVShowRepository(db)
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    /** A started show: [regular] episodes in season 1 plus one special, episode 1 watched. */
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

    private fun newViewModel() = viewModels.track(UpNextViewModel(tv, settings))

    @Test
    fun markWatched_advancesTheShow_andUndoPutsItBack() =
        runTest {
            val showId = startedShow(regular = 3)
            val viewModel = newViewModel()

            val offered =
                viewModel.uiState
                    .first { it.items.any { row -> row.episodeNumber == 2 } }
                    .items
                    .single()
            assertEquals(showId, offered.mediaId)

            viewModel.markWatched(offered)
            val advanced =
                viewModel.uiState.first {
                    it.items.singleOrNull()?.episodeNumber == 3 &&
                        it.markedEvent != null
                }
            val event = assertNotNull(advanced.markedEvent)
            assertEquals(offered, event.row)

            viewModel.undo(event)
            val restored = viewModel.uiState.first { it.items.singleOrNull()?.episodeNumber == 2 }
            assertEquals(null, restored.markedEvent, "undo acknowledges the event it reversed")
        }

    @Test
    fun includeSpecialsSetting_decidesWhetherASpecialIsOffered() =
        runTest {
            startedShow(regular = 1) // every regular episode watched; only the special is left
            val viewModel = newViewModel()

            val withSpecial = viewModel.uiState.first { !it.isLoading && it.items.isNotEmpty() }
            assertEquals(0, withSpecial.items.single().seasonNumber)

            settings.setIncludeSpecials(false)
            viewModel.uiState.first { !it.isLoading && it.items.isEmpty() }
        }
}
