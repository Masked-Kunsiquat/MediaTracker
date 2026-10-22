package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.books.data.TitleRating
import kotlin.math.roundToInt

/**
 * The header's "Yours" (#230, #231): stars at your average across rated viewings, then "4.5 average of
 * 2 reads · latest 5" when two or more are rated, nothing more for one, and "Not rated yet" for none.
 * Read-only: a rating is set on a read, a watch-through or a viewing, never on the title.
 *
 * @param averageOf The plural naming what was rated -- reads, watch-throughs or viewings.
 */
@Composable
fun YourRatingRow(
    rating: TitleRating?,
    @PluralsRes averageOf: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.rating_yours),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RatingStars(halves = rating?.let { (it.averageStars * 2).roundToInt() })
        }
        when {
            rating == null ->
                Text(
                    text = stringResource(R.string.rating_not_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            rating.rated >= 2 ->
                Text(
                    text =
                        pluralStringResource(
                            averageOf,
                            rating.rated,
                            formatAverage(rating.averageStars),
                            rating.rated,
                            formatStars((rating.latestStars * 2).roundToInt()),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/** An average in stars, one decimal, in the viewer's locale: "4.5", "4.2". */
@Composable
internal fun formatAverage(stars: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    return String.format(locale, "%.1f", stars)
}

/** "Your rating" and its editable stars, on a read's or a watch-through's card (#230, #231). */
@Composable
fun RunRatingRow(
    halves: Int?,
    onRate: (Int?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.rating_your_rating), style = MaterialTheme.typography.labelLarge)
        RatingStars(halves = halves, starSize = 28.dp, onRate = onRate)
    }
}

/**
 * "Rate this read?" / "Rate this watch-through?" (#230, #231), offered once when one ends. Skip leaves it
 * unrated; Save stores the stars chosen, and is enabled once some are.
 *
 * @param key What is being rated, so a different read or run starts from its own [initial] stars.
 * @param starsTag The test tag for the stars, so each screen's tests find its own dialog's.
 */
@Composable
fun RateDialog(
    title: String,
    body: String,
    key: String,
    initial: Int?,
    starsTag: String,
    onSave: (halves: Int) -> Unit,
    onSkip: () -> Unit,
) {
    var halves by rememberSaveable(key) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(body)
                RatingStars(
                    halves = halves,
                    starSize = 40.dp,
                    onRate = { halves = it },
                    modifier = Modifier.testTag(starsTag),
                )
                Text(
                    text = halves?.let { formatStars(it) }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { halves?.let(onSave) }, enabled = halves != null) {
                Text(stringResource(R.string.rate_read_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.rate_read_skip)) }
        },
    )
}
