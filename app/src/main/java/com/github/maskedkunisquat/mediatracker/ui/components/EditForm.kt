package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.insets.barPadding

/**
 * The edit screens' compact header (#174): which item is being edited, in the detail screen's
 * vocabulary but smaller. A thumbnail when there is a cover, then the kind line ("Film · 1999") and
 * the saved title as a heading. Read-only by design -- the form below it is where things change, and
 * callers pass the title as loaded rather than the live field, so the header keeps naming the item
 * while its title is being retyped. With no cover there is no artwork slot at all, as in
 * [DetailHeader]. The title stops at two lines, so a long one cannot push the form down the screen.
 */
@Composable
fun EditHeader(
    kind: String,
    year: Int?,
    title: String,
    artwork: DetailArtwork?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artwork != null) {
            Box(
                modifier =
                    Modifier
                        .size(width = THUMBNAIL_WIDTH, height = THUMBNAIL_HEIGHT)
                        .clip(RoundedCornerShape(8.dp)),
            ) {
                CoverImage(
                    coverDir = artwork.coverStorageDir,
                    coverImageHash = artwork.coverImageHash,
                    mediaType = artwork.mediaType,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (year != null) stringResource(R.string.detail_kind_year_format, kind, year) else kind,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

private val THUMBNAIL_WIDTH = 64.dp
private val THUMBNAIL_HEIGHT = 96.dp

/**
 * One titled card-backed group of related fields (ROADMAP Task 7 Phase D), mirroring
 * `SettingsScreen`'s `SettingsSection` convention exactly: a [Text] title above a [Card], with the
 * card's content column spaced by 16dp between fields.
 */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * Persistent, non-scrolling Save/Cancel action bar (ROADMAP Task 7 Phase D; shared by every edit
 * screen since #174), pinned to the bottom of the form on an elevated [Surface] (Material 3's default `BottomAppBar` tonal elevation,
 * 3dp) so the pair reads as one committed action group that's always reachable, rather than two
 * inline buttons at the end of a long scroll. [canSave] mirrors the old inline Save button's
 * `enabled` condition exactly (parse-once validation must pass, and no save already in flight);
 * [isSaving] additionally swaps the Save button's label for a small inline
 * [CircularProgressIndicator] so the in-flight state is visible on the action itself, not only via
 * the fields being disabled. [cancelTag] and [saveTag] are the screen's own `TestTags` constants.
 */
@Composable
fun EditBottomBar(
    isSaving: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    cancelTag: String,
    saveTag: String,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            // A pinned control behind the navigation bar is the same bug as a pinned control
            // behind the keyboard (#95), so this bar takes the bottom and horizontal insets as
            // real padding. The Surface itself still spans to the window edge, so its elevated
            // background fills the bar area rather than leaving a strip of list showing under it.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(barPadding(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !isSaving,
                modifier = Modifier.weight(1f).testTag(cancelTag),
            ) {
                Text(stringResource(R.string.cancel_button))
            }
            Button(
                onClick = onSave,
                enabled = canSave && !isSaving,
                modifier = Modifier.weight(1f).testTag(saveTag),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.save_button))
                }
            }
        }
    }
}
