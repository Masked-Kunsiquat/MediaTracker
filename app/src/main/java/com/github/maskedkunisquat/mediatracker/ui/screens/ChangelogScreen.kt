package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.BuildConfig
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.ChangelogViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.features.changelog.ChangelogVersion
import com.hub.media.features.changelog.parseInlineMarkup
import com.hub.media.ui.ChangelogUiState
import com.hub.media.ui.ChangelogViewModel
import com.hub.media.ui.entryKey

/**
 * Route wrapper for the in-app "What's new" changelog viewer (ROADMAP Task 15 Phase B2b).
 *
 * Supplies the ViewModel with the app's own `versionName` (so the screen opens on the release the
 * user is actually running) and an asset reader. `CHANGELOG.md` is copied into assets at build time
 * from the repo root -- see `app/build.gradle.kts` -- so the root file stays the single source of
 * truth and a stale duplicate can never be committed.
 */
@Composable
fun ChangelogScreenRoute(onNavigateBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: ChangelogViewModel =
        viewModel(
            factory =
                remember(context) {
                    ChangelogViewModelFactory(
                        currentVersion = BuildConfig.VERSION_NAME,
                        readChangelog = {
                            runCatching {
                                context.assets
                                    .open("CHANGELOG.md")
                                    .bufferedReader()
                                    .use { it.readText() }
                            }.getOrNull()
                        },
                    )
                },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChangelogScreen(
        uiState = uiState,
        onToggleVersion = viewModel::toggleVersion,
        onToggleEntry = viewModel::toggleEntry,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The changelog viewer (ROADMAP Task 15 Phase B2b): a three-level fold -- version, then preamble
 * always visible once a version is open, then each `**bold**` entry behind its own expander.
 *
 * ### Why folded rather than shown flat
 * The sections are wildly uneven: `[0.7.0]` is 335 lines against `[0.1.0]`'s 38, and the single
 * longest bullet is 109 lines -- nearly three times the whole `[0.1.0]` section. Flat, that is
 * dozens of phone screens with no way to skim. See ROADMAP Task 15 Phase B2's "Known tension"
 * bullet for the measurements this was decided on.
 *
 * ### A `LazyColumn` here, unlike the log viewer
 * The log viewer had to avoid lazy layout because selection breaks across recycled items. This
 * screen can afford it: the whole changelog is far longer than the log's bounded window, and
 * collapsing means the expanded text at any moment is short enough that per-entry
 * [SelectionContainer]s cover the realistic case (selecting within one entry) without needing
 * selection to span the entire document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    uiState: ChangelogUiState,
    onToggleVersion: (String) -> Unit,
    onToggleEntry: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            // No padding here: the list below draws behind the bars and re-adds the space as
            // contentPadding. Padding this Box is what stops that happening.
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
        ) {
            when {
                // Neither of the two non-list states scrolls, so both keep real padding and centre
                // in the content area rather than in the window.
                uiState.isLoading ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                uiState.failedToLoad || uiState.document.versions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.changelog_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }

                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.document.versions.size) { index ->
                            val version = uiState.document.versions[index]
                            VersionCard(
                                version = version,
                                expanded = version.version in uiState.expandedVersions,
                                expandedEntries = uiState.expandedEntries,
                                onToggleVersion = { onToggleVersion(version.version) },
                                onToggleEntry = onToggleEntry,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun VersionCard(
    version: ChangelogVersion,
    expanded: Boolean,
    expandedEntries: Set<String>,
    onToggleVersion: () -> Unit,
    onToggleEntry: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpanderRow(
                title = version.date?.let { "${version.version} — $it" } ?: version.version,
                titleStyle = MaterialTheme.typography.titleMedium,
                expanded = expanded,
                onClick = onToggleVersion,
            )
            if (!expanded) return@Column

            // Always visible once the version is open: the preamble is the plain-language summary
            // of the release, which is the thing worth reading before deciding whether to expand
            // any of the technical detail below it.
            if (version.preamble.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = annotated(version.preamble),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            version.sections.forEach { section ->
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                section.entries.forEachIndexed { index, entry ->
                    val key = entryKey(version.version, section.title, index)
                    if (entry.heading == null) {
                        // No bold lead -- these are the short one-liners, already brief enough to
                        // read in place, so folding them would only add a tap.
                        SelectionContainer {
                            Text(
                                text = annotated(entry.body),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        val entryExpanded = key in expandedEntries
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ExpanderRow(
                                title = entry.heading!!,
                                titleStyle = MaterialTheme.typography.bodyMedium,
                                expanded = entryExpanded,
                                onClick = { onToggleEntry(key) },
                            )
                            if (entryExpanded) {
                                SelectionContainer {
                                    Text(
                                        text = annotated(entry.body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tappable title row with a chevron -- the one fold affordance, used at both levels. */
@Composable
private fun ExpanderRow(
    title: String,
    titleStyle: androidx.compose.ui.text.TextStyle,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = titleStyle, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription =
                stringResource(
                    if (expanded) R.string.changelog_collapse else R.string.changelog_expand,
                ),
        )
    }
}

/**
 * Renders the `**bold**`/`` `code` `` subset via [parseInlineMarkup]. The parsing itself lives in
 * `shared/` and is unit-tested there; this is only the Compose mapping.
 */
@Composable
private fun annotated(text: String): AnnotatedString =
    buildAnnotatedString {
        parseInlineMarkup(text).forEach { span ->
            // Built as one SpanStyle rather than nested branches so the flags genuinely compose --
            // *`code` inside emphasis* occurs in this changelog and would otherwise lose one of them.
            val style =
                SpanStyle(
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                )
            withStyle(style) { append(span.text) }
        }
    }
