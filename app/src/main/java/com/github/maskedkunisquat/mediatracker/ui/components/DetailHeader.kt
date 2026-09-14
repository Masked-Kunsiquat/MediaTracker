package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.MediaType
import java.util.Locale

/**
 * Shared Option A detail header (#141), built film-first and designed so TV and Book can adopt it
 * without a second version: the text is plain parameters, artwork is a nullable descriptor, and the
 * rating scale is a parameter (some providers may not be out of 10) rather than a constant. The
 * status control is a slot rather than a fixed [StatusDropdownChip] because [T] differs per media
 * type ([com.hub.media.core.database.entities.WatchStatus] vs
 * [com.hub.media.core.database.entities.ReadingStatus]) and a `Composable` type parameter cannot
 * express that here.
 *
 * A `Row`: artwork on the left, a text column on the right, top-aligned so a short title does not
 * center the column against a tall poster.
 *
 * @param kind Media-type label, e.g. "Film". Combined with [year] into one labelMedium line.
 * @param year Release year, or `null` to omit it from the kind line ("Film" alone rather than
 *   "Film ·").
 * @param title The item's title. Marked as a heading (`Modifier.semantics { heading() }`) since it
 *   no longer sits in the top app bar, which is where that role used to come from for free.
 * @param subline An optional second detail line (runtime, seasons, authors) -- `null` omits it
 *   entirely rather than rendering an empty row.
 * @param rating Community rating out of [ratingScale], or `null` to omit the whole row.
 * @param ratingScale The denominator [rating] is out of. Defaults to 10 (TMDB); a parameter rather
 *   than a hardcoded "/ 10" so a future provider on a different scale does not need a second header.
 * @param artwork Cover descriptor, or `null` to draw no artwork slot at all. Callers build this only
 *   when a cover hash exists -- see [DetailArtwork]'s KDoc for why the null case matters.
 * @param statusNote An optional line under the status control (e.g. "Watched Jan 3, 2024"). `null`
 *   omits it.
 * @param statusControl The status-change control for this media type, typically a
 *   [StatusDropdownChip]. A slot rather than a fixed type so each media type supplies its own enum.
 */
@Composable
fun DetailHeader(
    kind: String,
    year: Int?,
    title: String,
    subline: String?,
    rating: Double?,
    artwork: DetailArtwork?,
    statusNote: String?,
    modifier: Modifier = Modifier,
    ratingScale: Int = 10,
    statusControl: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (artwork != null) {
            Box(
                modifier =
                    Modifier
                        .size(width = ARTWORK_WIDTH, height = ARTWORK_HEIGHT)
                        .clip(RoundedCornerShape(12.dp)),
            ) {
                CoverImage(
                    coverDir = artwork.coverStorageDir,
                    coverImageHash = artwork.coverImageHash,
                    mediaType = artwork.mediaType,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(ARTWORK_HEIGHT),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (year != null) stringResource(R.string.detail_kind_year_format, kind, year) else kind,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rating != null) {
                RatingRow(rating = rating, scale = ratingScale)
            }
            Box(modifier = Modifier.padding(top = 4.dp)) {
                statusControl()
            }
            if (statusNote != null) {
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Cover descriptor for [DetailHeader]. [coverImageHash] is non-null by construction -- callers build
 * one only when a hash exists (`movie.item.coverImageHash?.let { DetailArtwork(dir, it, type) }`),
 * so passing `null` for the whole parameter is how "no cover" is expressed, matching [CoverImage]'s
 * existing "drawn only when a hash exists" rule on every detail screen (a hand-entered title never
 * gets artwork, so a placeholder box would be permanent -- see `MovieDetailScreen`'s prior comment).
 */
data class DetailArtwork(
    val coverStorageDir: String,
    val coverImageHash: String,
    val mediaType: MediaType,
)

private val ARTWORK_WIDTH = 132.dp
private val ARTWORK_HEIGHT = 198.dp

/**
 * Star + value + "/ scale", merged into one semantics node with a single content description (e.g.
 * "Rated 7.9 out of 10") -- three [Text]/[Icon] children would otherwise read as three separate
 * TalkBack stops for what is one fact.
 */
@Composable
private fun RatingRow(
    rating: Double,
    scale: Int,
) {
    // The viewer's locale, so a comma-decimal device reads "7,9" (the class of bug #78 fixed on input).
    val formatted = String.format(Locale.getDefault(), "%.1f", rating)
    val description = stringResource(R.string.detail_rating_content_description, formatted, scale)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(text = formatted, style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.detail_rating_scale_suffix, scale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Generic status-change control (#141), replacing Film's four stacked [androidx.compose.material3.FilterChip]s,
 * TV's abandon button and Book's `StatusChip` (`BookDetailsTab.kt`) with one shape all three media
 * types share. Generic over [T] so each caller passes its own status enum
 * ([com.hub.media.core.database.entities.WatchStatus], [com.hub.media.core.database.entities.ReadingStatus])
 * without this component knowing either exists.
 *
 * An [AssistChip] whose label is the status alone (no "Status:" prefix, unlike Book's chip) that
 * opens a [DropdownMenu] of every [options] entry on tap; selecting one calls [onSelect] and closes
 * the menu. [onClickLabel] names the action for TalkBack, since "Watched" read alone does not say the
 * control changes anything.
 *
 * @param value The currently selected status, shown as the chip's label via [label].
 * @param options Every selectable status, in menu order.
 * @param label Renders a status as its display string. `@Composable` because every existing
 *   `displayLabel()` (e.g. [com.hub.media.core.database.entities.WatchStatus]'s) reads it via
 *   `stringResource`.
 * @param onSelect Invoked with the tapped option; the caller decides what re-selecting the current
 *   value does (Book's equivalent treats it as a harmless no-op re-application).
 * @param onClickLabel The accessibility action label for opening the menu, e.g. "Change status".
 */
@Composable
fun <T> StatusDropdownChip(
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val open = { expanded = true }

    Box(modifier = modifier) {
        AssistChip(
            onClick = open,
            label = { Text(label(value)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors =
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            border = null,
            modifier =
                Modifier.semantics {
                    onClick(label = onClickLabel) {
                        open()
                        true
                    }
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Number of lines [DetailSynopsis] clamps to before offering "More". */
private const val SYNOPSIS_MAX_LINES = 4

/**
 * Shared synopsis block (#141): renders nothing for a `null`/blank [text] (the same "absence reads
 * as absence" rule [CoverImage]'s callers already follow), otherwise clamps to
 * [SYNOPSIS_MAX_LINES] lines with a "More"/"Less" [TextButton].
 *
 * The button only appears when there is something to toggle: either the clamped layout actually
 * overflowed ([androidx.compose.ui.text.TextLayoutResult.hasVisualOverflow], read via
 * [Text]'s `onTextLayout`) or the text is currently expanded. A short synopsis that never overflows
 * never grows a button. [expanded] is [rememberSaveable] so rotation/process death does not silently
 * re-collapse it.
 */
@Composable
fun DetailSynopsis(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else SYNOPSIS_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                if (!expanded) overflowing = layoutResult.hasVisualOverflow
            },
        )
        if (overflowing || expanded) {
            // Offset by the button's own 12dp content padding, so "More" lines up with the text above.
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.offset(x = (-12).dp)) {
                Text(
                    text =
                        stringResource(
                            if (expanded) R.string.detail_synopsis_less else R.string.detail_synopsis_more,
                        ),
                )
            }
        }
    }
}
