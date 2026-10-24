package com.github.maskedkunisquat.mediatracker.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
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
    coverBytes: ByteArray? = null,
) {
    // A looked-up book's cover before it is saved (#214): held in memory, not yet on disk, so it has
    // no hash for CoverImage to read. Decoded once per byte array; bytes that do not decode show no
    // artwork, as a book with no cover does.
    val unsavedCover =
        remember(coverBytes) {
            coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artwork == null && unsavedCover != null) {
            Image(
                bitmap = unsavedCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(width = THUMBNAIL_WIDTH, height = THUMBNAIL_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag(TestTags.EDIT_HEADER_UNSAVED_COVER),
            )
        }
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
 * screen since #174), pinned to the bottom of the form on an elevated [Surface] (Material 3's
 * default `BottomAppBar` tonal elevation, 3dp) so the pair reads as one committed action group
 * that's always reachable, rather than two inline buttons at the end of a long scroll. [canSave] is
 * the form's validation alone -- every field must parse -- and the bar adds "no save already in
 * flight" itself, disabling both buttons while [isSaving]. [isSaving] also swaps the Save button's
 * label for a small inline [CircularProgressIndicator] so the in-flight state is visible on the
 * action itself, not only via the fields being disabled. [cancelTag] and [saveTag] are the screen's
 * own `TestTags` constants.
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

/**
 * The frame every form screen shares (#214): a [Scaffold] with a back-arrow top bar, and a body the
 * keyboard shrinks. [content] receives the Scaffold's inner padding, because the two halves of a
 * form body take different pieces of it -- see [EditFormBody].
 *
 * `safeDrawing` because Scaffold's default content insets leave out the IME, so without it the
 * keyboard would sit over the field being edited instead of the layout resizing (#95). The bars are
 * *not* padded here: the scrolling form passes under the top bar, and the pinned action bar takes the
 * navigation bar itself. The keyboard is the exception, as always: it shrinks the viewport for both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreenScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    content: @Composable BoxScope.(innerPadding: PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .consumeWindowInsets(innerPadding),
        ) {
            content(innerPadding)
        }
    }
}

/**
 * A centered stand-in for a form that is not ready yet -- a spinner while loading, or
 * [EditLoadFailed] -- inside [FormScreenScaffold]'s body.
 */
@Composable
fun FormScreenMessage(
    innerPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Edit Book's shape since #174, written once (#214): a scrolling form that takes the remaining
 * height, then [aboveBar] (an inline error, where a screen has one), then the Cancel/Save bar pinned
 * under it -- above the keyboard when it is up, above the navigation bar when it is not
 * ([EditBottomBar] takes those insets itself).
 *
 * The snackbar lies over the form rather than in Scaffold's slot: Scaffold lifts a snackbar only
 * above its own bottomBar, and this bar is content, so a Scaffold-placed snackbar would sit on Save
 * and swallow taps on it until it timed out (#174 review).
 */
@Composable
fun EditFormBody(
    innerPadding: PaddingValues,
    formTag: String,
    isSaving: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    cancelTag: String,
    saveTag: String,
    snackbarHostState: SnackbarHostState? = null,
    aboveBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // Inside the scroll, so the form passes under the top app bar. Horizontal bars
                        // only: the bottom inset is the pinned action bar's to handle, and adding it
                        // here would open a navigation-bar-sized gap above a bar that is already there.
                        .padding(top = innerPadding.calculateTopPadding())
                        .padding(barPadding(WindowInsetsSides.Horizontal))
                        .padding(16.dp)
                        .testTag(formTag),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        aboveBar()
        EditBottomBar(
            isSaving = isSaving,
            canSave = canSave,
            onCancel = onCancel,
            onSave = onSave,
            cancelTag = cancelTag,
            saveTag = saveTag,
        )
    }
}

/** [FormScreenScaffold] around an [EditFormBody], for a screen whose form is always ready. */
@Composable
fun EditFormScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    formTag: String,
    isSaving: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    cancelTag: String,
    saveTag: String,
    snackbarHostState: SnackbarHostState? = null,
    aboveBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    FormScreenScaffold(title = title, onNavigateBack = onNavigateBack) { innerPadding ->
        EditFormBody(
            innerPadding = innerPadding,
            formTag = formTag,
            isSaving = isSaving,
            canSave = canSave,
            onCancel = onCancel,
            onSave = onSave,
            cancelTag = cancelTag,
            saveTag = saveTag,
            snackbarHostState = snackbarHostState,
            aboveBar = aboveBar,
            content = content,
        )
    }
}
