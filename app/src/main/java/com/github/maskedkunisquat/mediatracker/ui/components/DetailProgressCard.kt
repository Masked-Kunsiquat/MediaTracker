package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R

/**
 * Shared Option A progress card (#141 step 2), used by the show detail screen for episode progress
 * and by book (step 3) for reading progress. Mirrors [BookDetailsTab]'s existing `ProgressSection`
 * card almost exactly -- same label, value and bar styling -- but is generic over what is being
 * counted, so it takes an already-formatted [value] string (e.g. "12 / 19 episodes") rather than
 * knowing about episodes or pages itself.
 *
 * **Divide-by-zero guard: renders nothing when [total] is 0 or less**, the same "absence reads as
 * absence" rule [DetailSynopsis] and [DetailHeader]'s artwork slot already follow, rather than
 * showing a bar frozen at 0% -- a show with no episodes yet has no progress to report, and a stalled
 * empty bar reads as a bug rather than as "nothing tracked yet."
 *
 * @param value The formatted progress line, e.g. "12 / 19 episodes".
 * @param completed How many of [total] are done.
 * @param total The denominator. `<= 0` renders nothing at all.
 */
@Composable
fun DetailProgressCard(
    value: String,
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return

    Card(
        // No side margin of its own: the screen owns it, so a padded container does not double it.
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_progress_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { completed.toFloat() / total },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** One [DetailFacts] label/value pair. A `null` [value] drops the whole pair -- see that KDoc. */
data class DetailFact(
    val label: String,
    val value: String?,
)

/**
 * Shared Option A facts grid (#141 step 2): a 2-column grid of label/value pairs, used by the show
 * detail screen (first aired, airing status, seasons, episodes) and by book (step 3, format, pages,
 * ISBN, tracking).
 *
 * Pairs whose [DetailFact.value] is `null` are dropped before laying out the grid, rather than
 * rendered as an empty cell -- the same "unknown" case [DetailHeader]'s runtime line handles by
 * substituting a label, except here there is no natural placeholder for an unlabelled fact and
 * dropping it keeps the grid honest about what is actually known. Renders nothing at all when every
 * pair is dropped.
 */
@Composable
fun DetailFacts(
    facts: List<DetailFact>,
    modifier: Modifier = Modifier,
) {
    val present = facts.filter { it.value != null }
    if (present.isEmpty()) return

    Card(
        // No side margin of its own: the screen owns it, so a padded container does not double it.
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            present.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    pair.forEach { fact ->
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fact.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = fact.value.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Keeps an odd final row's single cell in the left column rather than stretching
                    // it across both, so the grid's right edge stays aligned above it.
                    if (pair.size == 1) {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }
    }
}
