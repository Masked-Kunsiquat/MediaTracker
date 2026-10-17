package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.YearReviewViewModelFactory
import com.hub.media.ui.AppContainer
import com.hub.media.ui.YearReviewUiState
import com.hub.media.ui.YearReviewViewModel
import kotlinx.coroutines.launch

/** The year in review's route (#221): its view model, opened on [initialYear] (`null` is this year). */
@Composable
fun YearReviewScreenRoute(
    appContainer: AppContainer,
    initialYear: Int?,
    onClose: () -> Unit,
) {
    val viewModel: YearReviewViewModel = viewModel(factory = YearReviewViewModelFactory(appContainer, initialYear))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    YearReviewScreen(
        uiState = uiState,
        thisYear =
            java.time.Year
                .now()
                .value,
        onSelectYear = viewModel::selectYear,
        onClose = onClose,
    )
}

/**
 * The year in review (#221): a story of full-screen pages, one number or highlight each, that the
 * user swipes or taps through -- the right third of the screen goes on, the left third goes back.
 * TalkBack users get the same as "Next page" and "Previous page" actions on each page -- the node
 * TalkBack focuses -- since tapping thirds of the screen is not discoverable without sight. A page with nothing for the year is never
 * built ([pagesOf]). Each page has its own theme colour, and sets [LocalContentColor] so its text
 * reads on that colour.
 *
 * No `Scaffold` and no text field: the page draws edge to edge and pads itself with
 * `safeDrawingPadding`.
 *
 * @param thisYear The current year, so its title reads "so far".
 */
@Composable
fun YearReviewScreen(
    uiState: YearReviewUiState,
    thisYear: Int,
    onSelectYear: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val review = uiState.review
    if (review == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // A new year is a new story, starting from its first page.
    key(review.year) {
        val pages = pagesOf(review)
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val scope = rememberCoroutineScope()
        val nextLabel = stringResource(R.string.year_review_next)
        val previousLabel = stringResource(R.string.year_review_previous)
        val go = { by: Int ->
            val target = (pagerState.currentPage + by).coerceIn(0, pages.lastIndex)
            scope.launch { pagerState.animateScrollToPage(target) }
        }
        val colours =
            listOf(
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
            )

        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(TestTags.YearReview.PAGER)
                    .pointerInput(pages.size) {
                        detectTapGestures { offset ->
                            when {
                                offset.x > size.width * 2f / 3f -> go(1)
                                offset.x < size.width / 3f -> go(-1)
                            }
                        }
                    },
        ) { index ->
            val (background, onBackground) = colours[index % colours.size]
            YearPageFrame(
                index = index,
                count = pages.size,
                background = background,
                onBackground = onBackground,
                onClose = onClose,
                // TalkBack focuses the page, never the pager, so the paging actions live here; a page
                // reads as one item -- except the year page, whose chips must stay reachable (#221 review).
                modifier =
                    Modifier
                        .testTag(TestTags.YearReview.page(pages[index].name))
                        .semantics(mergeDescendants = pages[index] != YearPage.YEAR) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(nextLabel) {
                                        go(1)
                                        true
                                    },
                                    CustomAccessibilityAction(previousLabel) {
                                        go(-1)
                                        true
                                    },
                                )
                        },
            ) {
                YearPageContent(pages[index], review, uiState.years, thisYear, onSelectYear)
            }
        }
    }
}

/** A page's frame: its colour, the progress bars and ✕ along the top, its content centred. */
@Composable
private fun YearPageFrame(
    index: Int,
    count: Int,
    background: Color,
    onBackground: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides onBackground) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(background)
                    .safeDrawingPadding()
                    .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(count) { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(4.dp)
                                .background(
                                    if (i <=
                                        index
                                    ) {
                                        onBackground
                                    } else {
                                        onBackground.copy(alpha = 0.25f)
                                    },
                                    CircleShape,
                                ),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag(TestTags.YearReview.CLOSE)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stats_close))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}
