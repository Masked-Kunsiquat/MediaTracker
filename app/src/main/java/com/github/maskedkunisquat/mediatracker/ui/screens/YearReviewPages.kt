package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.components.ClockDial
import com.github.maskedkunisquat.mediatracker.ui.text.formatDuration
import com.hub.media.features.stats.data.DayPart
import com.hub.media.features.stats.data.YearReview
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/*
 * The year in review's pages (#221), one composable per kind. Split out of YearReviewScreen.kt by
 * region (AGENTS.md section 6). Each page is one big number or name with a line or two around it,
 * drawn in LocalContentColor so it reads on whichever theme colour its page has.
 */

/** The pages, in story order. A page whose data is absent for the year is never built. */
internal enum class YearPage {
    YEAR,
    TOTALS,
    LONGEST_BOOK,
    TOP_AUTHOR,
    LONGEST_SESSION,
    GOAL,
    REWATCHED_FILM,
    BINGE,
    RHYTHM,
    SUMMARY,
}

/** The pages [review] has something for, in order: the year and the totals always, the summary last. */
internal fun pagesOf(review: YearReview): List<YearPage> =
    listOfNotNull(
        YearPage.YEAR,
        YearPage.TOTALS,
        review.longestBook?.let { YearPage.LONGEST_BOOK },
        review.topAuthor?.let { YearPage.TOP_AUTHOR },
        review.longestSession?.let { YearPage.LONGEST_SESSION },
        review.goal?.let { YearPage.GOAL },
        review.rewatchedFilm?.let { YearPage.REWATCHED_FILM },
        review.bingeDay?.let { YearPage.BINGE },
        review.rhythm?.let { YearPage.RHYTHM },
        YearPage.SUMMARY,
    )

/** Whether nothing at all was logged in the year. */
private fun YearReview.isQuiet(): Boolean =
    totals.booksFinished == 0 && totals.films == 0 && totals.episodes == 0 && totals.readSeconds == 0L

@Composable
internal fun YearPageContent(
    page: YearPage,
    review: YearReview,
    years: List<Int>,
    thisYear: Int,
    onSelectYear: (Int) -> Unit,
) {
    when (page) {
        YearPage.YEAR -> YearPickPage(review.year, years, thisYear, onSelectYear)
        YearPage.TOTALS -> TotalsPage(review)
        YearPage.LONGEST_BOOK ->
            review.longestBook?.let {
                Highlight(
                    stringResource(R.string.year_review_longest_book),
                    it.title,
                    pluralStringResource(R.plurals.year_review_pages, it.pages, it.pages),
                )
            }
        YearPage.TOP_AUTHOR ->
            review.topAuthor?.let {
                Highlight(
                    stringResource(R.string.year_review_top_author),
                    it.name,
                    stringResource(R.string.year_review_of_reading, formatDuration(it.seconds)),
                )
            }
        YearPage.LONGEST_SESSION ->
            review.longestSession?.let {
                Highlight(
                    stringResource(R.string.year_review_longest_session),
                    formatDuration(it.seconds),
                    stringResource(R.string.year_review_with_book, it.title),
                )
            }
        YearPage.GOAL ->
            review.goal?.let {
                Highlight(
                    stringResource(R.string.year_review_goal),
                    stringResource(R.string.year_review_goal_of, it.finished, it.goal),
                    if (it.reached) {
                        stringResource(R.string.stats_goal_reached)
                    } else {
                        pluralStringResource(
                            R.plurals.year_review_goal_short,
                            it.goal - it.finished,
                            it.goal - it.finished,
                        )
                    },
                )
            }
        YearPage.REWATCHED_FILM ->
            review.rewatchedFilm?.let {
                Highlight(
                    stringResource(R.string.year_review_rewatched),
                    it.title,
                    pluralStringResource(R.plurals.year_review_times, it.viewings, it.viewings),
                )
            }
        YearPage.BINGE -> review.bingeDay?.let { BingePage(it) }
        YearPage.RHYTHM -> review.rhythm?.let { RhythmPage(it) }
        YearPage.SUMMARY -> SummaryPage(review)
    }
}

@Composable
private fun YearPickPage(
    year: Int,
    years: List<Int>,
    thisYear: Int,
    onSelectYear: (Int) -> Unit,
) {
    Text(
        text =
            if (year == thisYear) {
                stringResource(R.string.year_review_title_so_far, year)
            } else {
                stringResource(R.string.year_review_title, year)
            },
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(24.dp))
    Text(text = stringResource(R.string.year_review_pick_year), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        for (y in years) {
            FilterChip(selected = y == year, onClick = { onSelectYear(y) }, label = { Text(y.toString()) })
        }
    }
}

@Composable
private fun TotalsPage(review: YearReview) {
    val t = review.totals
    Text(text = stringResource(R.string.year_review_totals), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(24.dp))
    Total(t.booksFinished, pluralStringResource(R.plurals.year_review_books, t.booksFinished))
    Total(t.films, pluralStringResource(R.plurals.year_review_films, t.films))
    Total(t.episodes, pluralStringResource(R.plurals.year_review_episodes, t.episodes))
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.year_review_hours, formatDuration(t.readSeconds + t.watchSeconds)),
        style = MaterialTheme.typography.titleLarge,
    )
    if (t.unknownRuntime > 0) {
        Text(
            text = pluralStringResource(R.plurals.year_review_unknown_runtime, t.unknownRuntime, t.unknownRuntime),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Total(
    count: Int,
    what: String,
) {
    Text(text = count.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
    Text(text = what, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
}

/** One highlight: a label, the big value, and a supporting line. */
@Composable
private fun Highlight(
    label: String,
    value: String,
    line: String,
) {
    Text(text = label, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(24.dp))
    Text(text = value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(text = line, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun BingePage(binge: com.hub.media.features.stats.data.BingeDay) {
    val locale = LocalConfiguration.current.locales[0]
    val date = LocalDate.of(binge.year, binge.month, binge.day)
    Text(text = stringResource(R.string.year_review_binge), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(24.dp))
    Text(text = binge.episodes.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
    Text(text = stringResource(R.string.year_review_episodes_in_a_day), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(24.dp))
    Text(
        text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(R.string.year_review_mostly, binge.topShow),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun RhythmPage(rhythm: com.hub.media.features.stats.data.Rhythm) {
    val locale = LocalConfiguration.current.locales[0]
    Text(text = stringResource(R.string.year_review_rhythm), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Text(
        text = Month.of(rhythm.busiestMonth).getDisplayName(TextStyle.FULL, locale),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(text = stringResource(R.string.year_review_busiest_month), style = MaterialTheme.typography.titleMedium)
    rhythm.busiestPart?.let { part ->
        val parts = stringResource(part.pluralLabelRes()).lowercase(locale)
        Spacer(Modifier.height(16.dp))
        ClockDial(hours = rhythm.hours, busiest = stringResource(part.pluralLabelRes()), description = "")
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                stringResource(
                    if (rhythm.reading) R.string.year_review_read_most else R.string.year_review_watch_most,
                    parts,
                ),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun SummaryPage(review: YearReview) {
    if (review.isQuiet()) {
        Text(
            text = stringResource(R.string.year_review_title, review.year),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(text = stringResource(R.string.year_review_quiet), style = MaterialTheme.typography.titleLarge)
        return
    }
    val t = review.totals
    Text(
        text = stringResource(R.string.year_review_summary, review.year),
        style = MaterialTheme.typography.displaySmall,
    )
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in listOf(
            "${t.booksFinished} ${pluralStringResource(R.plurals.year_review_books, t.booksFinished)}",
            "${t.films} ${pluralStringResource(R.plurals.year_review_films, t.films)}",
            "${t.episodes} ${pluralStringResource(R.plurals.year_review_episodes, t.episodes)}",
            stringResource(R.string.year_review_hours, formatDuration(t.readSeconds + t.watchSeconds)),
        )) {
            Text(text = line, style = MaterialTheme.typography.titleLarge)
        }
        review.longestBook?.let {
            Text(
                stringResource(R.string.year_review_summary_longest, it.title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        review.bingeDay?.let {
            Text(
                stringResource(R.string.year_review_summary_binge, it.episodes, it.topShow),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun DayPart.pluralLabelRes(): Int =
    when (this) {
        DayPart.MORNING -> R.string.stats_daypart_mornings
        DayPart.AFTERNOON -> R.string.stats_daypart_afternoons
        DayPart.EVENING -> R.string.stats_daypart_evenings
        DayPart.NIGHT -> R.string.stats_daypart_nights
    }
