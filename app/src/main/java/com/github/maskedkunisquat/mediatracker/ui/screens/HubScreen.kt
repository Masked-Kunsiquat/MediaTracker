package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.HubViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer
import com.hub.media.ui.HubUiState
import com.hub.media.ui.HubViewModel

/** The hub's route: its view model, and where each card and row leads. */
@Composable
fun HubScreenRoute(
    appContainer: AppContainer,
    onOpenDomain: (MediaType) -> Unit,
    onOpenAllMedia: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: HubViewModel =
        viewModel(factory = remember(appContainer) { HubViewModelFactory(appContainer) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HubScreen(
        uiState = uiState,
        onOpenDomain = onOpenDomain,
        onOpenAllMedia = onOpenAllMedia,
        onOpenStats = onOpenStats,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * The home screen (#126): one card per media domain, then what is global. The owner's original
 * model for the app -- each domain is its own place -- with the old app-wide library kept as "All
 * media" rather than deleted, since it is the only answer to "what do I own across everything".
 *
 * Each card carries one live number ([HubUiState]), because a hub that is only a menu is a tap tax.
 * The number is blank while loading or after a failed read, never a "0" that is not yet true. Stats
 * is books-only today and sits here as a row rather than on a card; Settings is the ⚙, with the same
 * content description the library's used to have, so it is found the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    uiState: HubUiState,
    onOpenDomain: (MediaType) -> Unit,
    onOpenAllMedia: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val showNumbers = !uiState.isLoading && !uiState.loadFailed
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                // Its own string, not app_name: the debug build renames the app ("MediaTracker Debug"),
                // and the hub's title is copy, not the launcher label.
                title = { Text(stringResource(R.string.hub_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_content_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(scrollingContentPadding(innerPadding, PaddingValues(16.dp)))
                    .testTag(TestTags.Hub.LIST),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DomainCard(
                emoji = "📖",
                name = stringResource(R.string.hub_books),
                number =
                    if (showNumbers) {
                        pluralStringResource(R.plurals.hub_books_count, uiState.readingBooks, uiState.readingBooks)
                    } else {
                        null
                    },
                tag = TestTags.Hub.BOOKS,
                onClick = { onOpenDomain(MediaType.BOOK) },
            )
            DomainCard(
                emoji = "🎬",
                name = stringResource(R.string.hub_films),
                number =
                    if (showNumbers) {
                        pluralStringResource(R.plurals.hub_films_count, uiState.watchlistFilms, uiState.watchlistFilms)
                    } else {
                        null
                    },
                tag = TestTags.Hub.FILMS,
                onClick = { onOpenDomain(MediaType.MOVIE) },
            )
            DomainCard(
                emoji = "📺",
                name = stringResource(R.string.hub_shows),
                number =
                    if (showNumbers) {
                        pluralStringResource(R.plurals.hub_shows_count, uiState.showsUpNext, uiState.showsUpNext)
                    } else {
                        null
                    },
                tag = TestTags.Hub.SHOWS,
                onClick = { onOpenDomain(MediaType.TV_SHOW) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            HubRow(
                text = stringResource(R.string.hub_all_media),
                tag = TestTags.Hub.ALL_MEDIA,
                onClick = onOpenAllMedia,
            )
            HubRow(text = stringResource(R.string.stats_title), tag = TestTags.Hub.STATS, onClick = onOpenStats)
        }
    }
}

/**
 * One domain's card: the whole card is the tap target, and its texts merge into one TalkBack stop
 * ("Books, 3 reading"). The emoji is the one the library's rows use for that type, and is cleared
 * from semantics because the name already says it. [number] is `null` while there is nothing true to
 * show; its line keeps a minimum height so the card does not jump when the number arrives.
 */
@Composable
private fun DomainCard(
    emoji: String,
    name: String,
    number: String?,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        // Card(onClick) is clickable but sets no role, so TalkBack would not call it a button.
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = number.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 20.dp),
                )
            }
        }
    }
}

/** A plain row to something global (All media, Stats), with a chevron saying it opens a screen. */
@Composable
private fun HubRow(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = {
            Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        // A button to TalkBack, as the cards are, so all five ways out of the hub announce alike.
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag),
    )
}
