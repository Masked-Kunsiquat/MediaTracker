package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.MismatchReviewViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.media.domain.MismatchReviewRow
import com.hub.media.ui.AppContainer
import com.hub.media.ui.MismatchReviewUiState
import com.hub.media.ui.MismatchReviewViewModel

/**
 * Route wrapper: builds the ViewModel and hands [MismatchReviewScreen] plain state.
 *
 * Same split every other screen here uses — the stateless half is what a test and a golden can
 * render at an arbitrary set of findings without a database behind it.
 */
@Composable
fun MismatchReviewScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MismatchReviewViewModel =
        viewModel(factory = remember(appContainer) { MismatchReviewViewModelFactory(appContainer) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MismatchReviewScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onAddMissing = viewModel::addMissingEpisodes,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Seasons where the library and TMDB disagree about how many episodes exist (#123).
 *
 * ## One direction only, and the screen has to show why
 * Adding is offered; removing is not, and never will be here. Deleting an episode row takes its
 * `watchedAt` with it irrecoverably, and — the deciding argument — **TMDB is not reliably right about
 * counts**: #88 found Judy Justice reporting 446 episodes while its four seasons sum to 458. A
 * button that "corrected" a library down to that figure would destroy real history to match a wrong
 * number.
 *
 * So an over-count gets a sentence rather than a control, and the sentence points at the show's own
 * season dialog — which already shrinks a season and already warns first. That is the right home for
 * a decision the user is making deliberately, rather than accepting from a list.
 *
 * ## Two shapes, two sentences
 * A season recorded with no episodes at all reads "Add all 6"; a partial one reads "Add 3 missing".
 * Both are real: a library was found holding one of each (Fleabag S2 at 0 against 6, Judy Justice S4
 * at 82 against 90). Wording them identically would make the empty case sound like a top-up.
 *
 * ## Reached from the backfill, not from a show
 * The findings are produced by the library-wide pass, so the pass is where they are offered. A
 * per-show banner was considered on #123 and deferred: more discoverable, twice the surface, and two
 * entry points to keep saying the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MismatchReviewScreen(
    uiState: MismatchReviewUiState,
    coverStorageDir: String,
    onAddMissing: (MismatchReviewRow) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.mismatch_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.mismatch_review_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .testTag(TestTags.MismatchReview.LIST),
            contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.mismatch_review_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (uiState.rows.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.mismatch_review_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(uiState.rows, key = { "${it.mediaId}:${it.seasonNumber}" }) { row ->
                MismatchCard(
                    row = row,
                    coverStorageDir = coverStorageDir,
                    isBusy = uiState.busyKey == row.mediaId to row.seasonNumber,
                    onAddMissing = { onAddMissing(row) },
                )
            }
        }
    }
}

/**
 * Poster size for a row here.
 *
 * A thumbnail rather than the 220dp the detail screens use: this is a list of decisions, and the
 * artwork is here to make a row recognisable at a glance, not to be looked at. 2:3 is the aspect
 * TMDB serves posters at, so a `Crop` at these dimensions is a straight scale rather than a trim.
 */
private val ROW_POSTER_WIDTH = 56.dp
private val ROW_POSTER_HEIGHT = 84.dp

/** One season's disagreement: what it is, and the one thing that can be done about it. */
@Composable
private fun MismatchCard(
    row: MismatchReviewRow,
    coverStorageDir: String,
    isBusy: Boolean,
    onAddMissing: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MismatchCardBody(
                row = row,
                isBusy = isBusy,
                onAddMissing = onAddMissing,
                // The text takes the weight, so a long show title or a wide button shrinks the text
                // rather than pushing the poster past the card's edge. #141 records the season header
                // that lost its overflow icon exactly that way.
                modifier = Modifier.weight(1f),
            )
            // Drawn only when there is one. CoverImage's placeholder is sized for a thumbnail and
            // would be a grey block of nothing here -- and a hand-entered show never gets artwork,
            // so it would be permanent. Same rule the detail screens follow.
            row.coverImageHash?.let { hash ->
                CoverImage(
                    coverDir = coverStorageDir,
                    coverImageHash = hash,
                    mediaType = MediaType.TV_SHOW,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = ROW_POSTER_WIDTH, height = ROW_POSTER_HEIGHT),
                )
            }
        }
    }
}

/** The text and action half of a row, extracted so the card stays a legible two-column layout. */
@Composable
private fun MismatchCardBody(
    row: MismatchReviewRow,
    isBusy: Boolean,
    onAddMissing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = row.showTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.mismatch_review_season_format, row.seasonNumber),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.mismatch_review_counts_format,
                    row.localEpisodes,
                    row.providerEpisodes,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (row.isUnderCount) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onAddMissing, enabled = !isBusy) {
                    Text(
                        stringResource(
                            // An empty season is a different sentence from a partial one -- see
                            // this file's KDoc on why they must not read the same.
                            if (row.isEmptySeason) {
                                R.string.mismatch_review_add_all_format
                            } else {
                                R.string.mismatch_review_add_missing_format
                            },
                            row.missingEpisodes,
                        ),
                    )
                }
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.mismatch_review_over_count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
