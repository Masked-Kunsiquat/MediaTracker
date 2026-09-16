package com.github.maskedkunisquat.mediatracker.ui.goldens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowDetailScreen
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.time.Instant

/**
 * Baseline golden for TV show detail — the screen issue #83 was filed about.
 *
 * A season overflow button was present, enabled and unreachable there, and it shipped past a fully
 * green suite. #99 then moved this screen's padding onto its `LazyColumn`'s `contentPadding`, so
 * the episode list passes under the navigation bar and only its last row is required to clear it.
 * `TVShowDetailScreenOcclusionTest` asserts that reachability; this golden covers what it cannot —
 * whether a season header with a two-digit episode count still lays out as a header rather than
 * wrapping into its own checkbox row.
 *
 * The fixture is a **partially watched** season rather than an empty or complete one. Both extremes
 * render uniformly and would hide the state this screen exists to show; a mixed one puts ticked and
 * unticked rows in the same image, which is where a checkbox-alignment or strikethrough mistake
 * becomes visible.
 *
 * Titles are mixed for the same reason, and were added late: this fixture predates episodes having
 * titles at all, so it rendered ten identical untitled rows and could not show a number sitting
 * beside a title -- which is precisely the layout that then went wrong twice, once by repeating a
 * placeholder word down every row and once by leaving the number stranded between the checkbox and
 * the title it belongs to. One image showing a long title, a short one, a two-digit number and an
 * episode with no title at all covers those without adding a second golden to a set that is
 * deliberately small.
 *
 * A **second season is deliberately not added**, tempting as it is now that seasons fold: a
 * multi-season show opens folded, so the golden would capture two shut headers and none of the
 * episode rows this image exists for. The fold is behaviour, and belongs in the behaviour lane.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries none.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TVShowDetailScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvShowDetail() {
        composeRule.captureGolden(
            name = "tv-show-detail",
            alsoAssert = { assertTextIsShown("Chernobyl", "Season 1") },
        ) { Fixture() }
    }

    /**
     * A show **with** a poster (#141) -- the state [tvShowDetail] does NOT cover: that fixture's
     * `coverImageHash` is null too, so `tv-show-detail.png` already is the no-poster golden. See
     * [MovieDetailScreenGoldenTest.filmDetail_withPosterAndSparseMetadata] for the same pairing on
     * film, including why a real file on disk (via `writeFakeCoverFile`) is what it takes to reach
     * `CoverImage`'s decoded-image branch rather than its placeholder.
     *
     * `releaseYear` is also null here, pinning that the year row is omitted (not "Year:") alongside
     * the poster, since both are branches a header refactor could disturb together.
     */
    @Test
    fun tvShowDetail_withPosterAndNoYear() {
        val coverDir = createTempDirectory("tv-show-detail-golden").toFile()
        val coverHash = "poster.jpg"
        writeFakeCoverFile(coverDir, coverHash)

        composeRule.captureGolden(
            name = "tv-show-detail-with-poster",
            alsoAssert = { assertTextIsShown("Twin Peaks", "Season 1") },
        ) { WithPosterFixture(coverDir.absolutePath, coverHash) }
    }

    /**
     * The same fixture at the largest system font scale (#163). Every other golden renders at 1.0,
     * which is how a season header that broke at 1.1 got past all of them. This one records what
     * the header gives up at large text: the season title shrinks to an ellipsis before the other
     * controls do. It is not the regression guard. At full width the header still fits with the fix
     * removed, so the guard lives in `TVShowDetailScreenOcclusionTest` on a narrow row.
     */
    @Test
    fun tvShowDetail_largestFontScale() {
        composeRule.captureGolden(
            name = "tv-show-detail-font-scale-2",
            fontScale = 2f,
            alsoAssert = { onNodeWithContentDescription("Season 1 options").assertIsDisplayed() },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        val episodes = (1..EPISODE_COUNT).map { episode(it) }
        TVShowDetailScreen(
            coverStorageDir = NO_COVERS,
            uiState =
                TVShowDetailUiState.Ready(
                    show = show(),
                    seasons =
                        listOf(
                            SeasonGroup(
                                seasonNumber = 1,
                                episodes = episodes,
                                watchedCount = WATCHED_COUNT,
                            ),
                        ),
                    watchedEpisodes = WATCHED_COUNT,
                    totalEpisodes = EPISODE_COUNT,
                    isAbandoned = false,
                ),
            onEpisodeWatchedChange = { _, _ -> },
            onSeasonWatchedChange = { _, _ -> },
            onSetSeasonLength = { _, _ -> },
            onRemoveSeason = {},
            onAbandonedChange = {},
            onRefreshMetadata = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    @Composable
    private fun WithPosterFixture(
        coverStorageDir: String,
        coverImageHash: String,
    ) {
        val episodes = (1..EPISODE_COUNT).map { episode(it, showId = "show-2") }
        TVShowDetailScreen(
            coverStorageDir = coverStorageDir,
            uiState =
                TVShowDetailUiState.Ready(
                    show =
                        show(
                            id = "show-2",
                            title = "Twin Peaks",
                            releaseYear = null,
                            coverImageHash = coverImageHash,
                            communityRating = 8.8,
                            synopsis =
                                "An FBI agent investigates the murder of a homecoming queen in a " +
                                    "small logging town full of secrets.",
                        ),
                    seasons =
                        listOf(
                            SeasonGroup(
                                seasonNumber = 1,
                                episodes = episodes,
                                watchedCount = WATCHED_COUNT,
                            ),
                        ),
                    watchedEpisodes = WATCHED_COUNT,
                    totalEpisodes = EPISODE_COUNT,
                    isAbandoned = false,
                ),
            onEpisodeWatchedChange = { _, _ -> },
            onSeasonWatchedChange = { _, _ -> },
            onSetSeasonLength = { _, _ -> },
            onRemoveSeason = {},
            onAbandonedChange = {},
            onRefreshMetadata = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private fun show(
        id: String = "show-1",
        title: String = "Chernobyl",
        releaseYear: Int? = 2019,
        coverImageHash: String? = null,
        communityRating: Double? = null,
        synopsis: String? = null,
    ) = MediaWithDetails.TVShow(
        item =
            MediaItemEntity(
                id = id,
                type = MediaType.TV_SHOW,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = coverImageHash,
                communityRating = communityRating,
                synopsis = synopsis,
            ),
        details = TVDetailsEntity(mediaId = id, totalSeasons = 1, status = WatchStatus.WATCHING),
    )

    private fun episode(
        episodeNumber: Int,
        showId: String = "show-1",
    ) = EpisodeEntity(
        title = EPISODE_TITLES[episodeNumber],
        id = "ep-$showId-1-$episodeNumber",
        mediaId = showId,
        seasonNumber = 1,
        episodeNumber = episodeNumber,
        watchedAt =
            if (episodeNumber <= WATCHED_COUNT) {
                Instant.fromEpochMilliseconds(1_700_000_000_000L)
            } else {
                null
            },
    )

    private companion object {
        /**
         * A directory holding no images. Every fixture row has a null cover hash, so
         * [CoverImage] draws its placeholder and never reads from disk.
         */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /** Two digits, so the season header renders the count this screen has been wrong about. */
        const val EPISODE_COUNT = 10

        /** Mixed rather than all or nothing -- see this class's KDoc. */
        const val WATCHED_COUNT = 4

        /**
         * Titles by episode number, deliberately incomplete.
         *
         * Episodes 3 and 7 are absent so the golden carries the untitled case beside titled ones --
         * a real state, not a contrived one: TMDB returns untitled episodes inside otherwise titled
         * seasons, which Judy Justice's season 4 does. Episode 5's title is long enough to test what
         * happens when it meets the number column rather than merely sitting near it.
         */
        val EPISODE_TITLES =
            mapOf(
                1 to "1:23:45",
                2 to "Please Remain Calm",
                4 to "The Happiness of All Mankind",
                5 to "Vichnaya Pamyat, and a Title Long Enough to Wrap Onto a Second Line",
                6 to "Open Wide, O Earth",
                8 to "Short",
                9 to "Vichnaya Pamyat",
                10 to "A Two-Digit Row",
            )

        /**
         * Writes a tiny, real, `BitmapFactory`-decodable JPEG to `<dir>/<fileName>` -- see
         * `MovieDetailScreenGoldenTest`'s copy of this helper for why this is what it takes to
         * reach `CoverImage`'s decoded-image branch in a Robolectric test.
         */
        fun writeFakeCoverFile(
            dir: File,
            fileName: String,
        ) {
            val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            File(dir, fileName).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
    }
}
