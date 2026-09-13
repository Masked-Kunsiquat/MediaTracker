package com.github.maskedkunisquat.mediatracker.ui.goldens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieDetailScreen
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.MovieDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.time.Instant

/**
 * Baseline golden for film detail — the screen #99 deliberately did not change.
 *
 * That is the whole reason it is here. It has no scrolling container, so moving padding inside one
 * was not available: the inset stays real padding, and a future refactor that "makes it consistent
 * with the other detail screens" would put its last row under the navigation bar with no way to
 * scroll it clear. Nothing in the repository would fail. This golden is the record of a decision
 * that currently exists only as a comment in the file.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries none.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovieDetailScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun filmDetail() {
        composeRule.captureGolden(
            name = "film-detail",
            alsoAssert = { assertTextIsShown("Stalker") },
        ) { Fixture() }
    }

    /**
     * A film **with** a poster (#141) -- the state [filmDetail] does NOT cover: that fixture's
     * `coverImageHash` is null too, so `film-detail.png` already is the no-poster golden, and a
     * second one recorded the same way would be a byte-for-byte duplicate. This is the branch that
     * actually exercises [CoverImage][com.github.maskedkunisquat.mediatracker.ui.components.CoverImage]'s
     * disk-read path -- see [writeFakeCoverFile] for how a real, `BitmapFactory`-decodable file gets
     * onto disk for a Robolectric test (native graphics is what makes this possible; see the
     * `@GraphicsMode` comment on `LibraryScreenGoldenTest`, the only other golden that needs it).
     *
     * Also a sparser fixture than [filmDetail] on purpose: no release year and no runtime, so this
     * golden simultaneously pins the null-year row being omitted (rather than rendering "Year:")
     * and the "Runtime unknown" branch, both of which a refactor could just as easily disturb while
     * moving the header around.
     */
    @Test
    fun filmDetail_withPosterAndSparseMetadata() {
        val coverDir = createTempDirectory("film-detail-golden").toFile()
        val coverHash = "poster.jpg"
        writeFakeCoverFile(coverDir, coverHash)

        composeRule.captureGolden(
            name = "film-detail-with-poster",
            alsoAssert = {
                assertTextIsShown("Primer")
                assertTextIsShown("Runtime unknown")
            },
        ) { WithPosterFixture(coverDir.absolutePath, coverHash) }
    }

    @Composable
    private fun Fixture() {
        MovieDetailScreen(
            coverStorageDir = NO_COVERS,
            uiState =
                MovieDetailUiState.Ready(
                    movie =
                        MediaWithDetails.Movie(
                            item =
                                MediaItemEntity(
                                    id = "film-1",
                                    type = MediaType.MOVIE,
                                    title = "Stalker",
                                    releaseYear = 1979,
                                    purchasePrice = 14.99,
                                    createdAt = Instant.fromEpochMilliseconds(0),
                                    coverImageHash = null,
                                ),
                            details =
                                MovieDetailsEntity(
                                    mediaId = "film-1",
                                    runtimeMinutes = 162,
                                    status = WatchStatus.WATCHED,
                                    watchedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                                ),
                        ),
                ),
            onStatusChange = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
            onNavigateToEditMovie = {},
        )
    }

    @Composable
    private fun WithPosterFixture(
        coverStorageDir: String,
        coverImageHash: String,
    ) {
        MovieDetailScreen(
            coverStorageDir = coverStorageDir,
            uiState =
                MovieDetailUiState.Ready(
                    movie =
                        MediaWithDetails.Movie(
                            item =
                                MediaItemEntity(
                                    id = "film-2",
                                    type = MediaType.MOVIE,
                                    title = "Primer",
                                    releaseYear = null,
                                    purchasePrice = null,
                                    createdAt = Instant.fromEpochMilliseconds(0),
                                    coverImageHash = coverImageHash,
                                ),
                            details =
                                MovieDetailsEntity(
                                    mediaId = "film-2",
                                    runtimeMinutes = null,
                                    status = WatchStatus.WATCHLIST,
                                    watchedAt = null,
                                ),
                        ),
                ),
            onStatusChange = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
            onNavigateToEditMovie = {},
        )
    }

    private companion object {
        /**
         * A directory holding no images. [filmDetail]'s fixture has a null cover hash, so
         * [CoverImage] draws its placeholder and never reads from disk.
         */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /**
         * Writes a tiny, real, `BitmapFactory`-decodable JPEG to `<dir>/<fileName>` -- what
         * [filmDetail_withPosterAndSparseMetadata] needs on disk for
         * [CoverImage][com.github.maskedkunisquat.mediatracker.ui.components.CoverImage]'s
         * `produceState` disk-read branch to resolve to an actual [android.graphics.ImageBitmap]
         * rather than fall through to its placeholder. Works only because this class runs under
         * `@GraphicsMode(NATIVE)`; Robolectric's default legacy pipeline returns a null [Bitmap]
         * here (see `LibraryScreenGoldenTest`'s comment on the same annotation).
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
