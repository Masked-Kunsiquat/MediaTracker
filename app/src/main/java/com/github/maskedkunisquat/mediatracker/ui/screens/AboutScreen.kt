package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding

/**
 * About and credits (#137).
 *
 * ## Why this screen exists at all
 *
 * Not polish. TMDB's API terms make attribution a **condition of use**, and they are specific about
 * where it goes: *"the attribution must be within your application's 'About' or 'Credits' type
 * section."* The app had no such section, so the notice had nowhere it could legally live. This
 * screen is that section, and the name in the title bar is part of what satisfies the term rather
 * than a label somebody picked.
 *
 * The obligation is live rather than theoretical: release APKs are published on GitHub, so the app
 * is distributed, and it has shipped TMDB-sourced titles, years, runtimes, scores and episode data
 * since `v0.17.0` while attributing nothing.
 *
 * ## Prominence is a requirement, so it is a layout decision
 *
 * TMDB's rule: their logo *"shall be less prominent than the logo or mark that primarily describes
 * the application."* That is why [AppIdentity] renders the app's own name at `headlineMedium` above
 * everything else, and why the TMDB logo is drawn at the 20dp height its drawable declares rather
 * than stretched to the card. Growing the logo, or dropping the app name, would break a licence
 * term and not merely a design -- which is the sort of thing a later "make this look tidier" pass
 * would do without knowing.
 *
 * ## What each provider actually requires, having been read rather than assumed
 *
 * The three are **not** symmetric, and the asymmetry is recorded here because the obvious guess
 * (the one with the strictest-sounding terms is the one to worry about) is wrong:
 *
 * - **TMDB** -- required. Approved logo unmodified, the notice verbatim, and a link to
 *   themoviedb.org. Their naming rule also binds every user-facing string: only *"TMDB"* or
 *   *"The Movie Database"*, never anything else.
 * - **Google Books** -- also required, and the guidelines are in some ways heavier than TMDB's
 *   (*"Google attribution is required"*, plus prominent links). They are written for an app that
 *   shows Google-branded search results or previews, and this app shows neither: `GoogleBooksClient`
 *   is an ISBN fallback behind Open Library, so a user never sees which provider answered. Whether
 *   the requirement binds a consumer with no Google-branded surface is a judgement this project is
 *   not qualified to make, so it takes the conservative reading. Crediting costs a line; arguing
 *   the scope of someone else's licence to save that line is a bad trade.
 * - **Open Library** -- **not** required. Their licensing page asserts no proprietary rights over
 *   the database and states no attribution clause. It is credited here anyway, as a courtesy, and
 *   this sentence exists so nobody later either deletes it as pointless or "fixes" it into looking
 *   like an obligation.
 *
 * ## Why the logo is bundled rather than fetched
 *
 * The app is offline-first (AGENTS.md section 4), and an attribution that fails to render when the
 * network is down is not an attribution. A bundled vector also cannot be resampled or recoloured in
 * transit, which is the other half of what their branding rules ask for. Android cannot render an
 * SVG at all -- there is no platform support for the format -- so a VectorDrawable is the only way
 * to keep it a vector rather than a raster.
 *
 * That conversion is not free, and `tmdb_logo.xml`'s own comment carries the trap it hides: their
 * path uses relative subpath starts that Android's `PathParser` resolves against the wrong point,
 * which mangled four glyphs on a device while rendering perfectly in the golden. Read that comment
 * before touching the asset.
 *
 * ## Version comes in as a parameter
 *
 * [versionName] is passed rather than read from `BuildConfig` inside the screen, so the golden can
 * pin a fixed string. Reading it here would make the recorded image change on **every release**,
 * turning a screenshot lane that exists to catch regressions into one that has to be re-recorded as
 * a matter of routine -- and a golden nobody trusts is a golden nobody looks at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
            // Unpadded, so the list can draw behind the bars and re-add the space as
            // contentPadding: the same arrangement ChangelogScreen documents.
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(TestTags.About.LIST),
                contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { AppIdentity(versionName = versionName) }

                item {
                    Text(
                        text = stringResource(R.string.about_section_sources),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                item { TmdbCredit() }
                item { GoogleBooksCredit() }
                item { OpenLibraryCredit() }
            }
        }
    }
}

/**
 * The app's own mark, and the thing every provider credit below has to stay less prominent than.
 *
 * `headlineMedium` rather than something larger: it only has to out-rank a 20dp logo, and an
 * oversized app name on a credits screen reads as vanity.
 */
@Composable
private fun AppIdentity(versionName: String) {
    Column {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The TMDB credit: logo, the notice **verbatim**, and the link their terms name.
 *
 * `about_tmdb_notice` is quoted text, not copy. It reads *"This product uses the TMDB API but is
 * not endorsed or certified by TMDB"* because that is the sentence their FAQ specifies, and
 * rewording it -- even into something that means the same -- stops satisfying the term. It is
 * pinned by [AboutScreenTest] for that reason.
 *
 * The logo is drawn with [ContentScale.Fit] against the drawable's declared 239x20dp. Fit scales
 * both axes together, so the aspect ratio their branding rules protect survives a narrow display;
 * `FillBounds` would stretch it and `Crop` would clip it, and both are the modification the rules
 * name.
 */
@Composable
private fun TmdbCredit() {
    ProviderCard {
        Image(
            painter = painterResource(R.drawable.tmdb_logo),
            // "TMDB" or "The Movie Database" -- their naming rule admits no third option, and a
            // screen reader announcing anything else would be the one surface that broke it.
            contentDescription = stringResource(R.string.about_tmdb_logo_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = stringResource(R.string.about_tmdb_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.about_tmdb_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        ProviderLink(
            label = R.string.about_tmdb_link_label,
            url = TMDB_URL,
        )
    }
}

/**
 * The Google Books credit, taking the conservative reading of a requirement whose scope is
 * genuinely arguable -- see this file's KDoc.
 *
 * *"Powered by Google"* is their wording and is kept as-is for the same reason TMDB's notice is.
 */
@Composable
private fun GoogleBooksCredit() {
    ProviderCard {
        Text(
            text = stringResource(R.string.about_google_books_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.about_google_books_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.about_google_books_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        ProviderLink(
            label = R.string.about_google_books_link_label,
            url = GOOGLE_BOOKS_URL,
        )
    }
}

/**
 * Open Library, credited although nothing requires it.
 *
 * The description says so out loud rather than leaving it to look like the other two. A reader
 * comparing the three should be able to see which are obligations and which is manners.
 */
@Composable
private fun OpenLibraryCredit() {
    ProviderCard {
        Text(
            text = stringResource(R.string.about_open_library_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.about_open_library_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        ProviderLink(
            label = R.string.about_open_library_link_label,
            url = OPEN_LIBRARY_URL,
        )
    }
}

/** One provider's block. A card each, so the three credits read as three rather than as a wall. */
@Composable
private fun ProviderCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/**
 * A provider link.
 *
 * A [TextButton] rather than a clickable [Text]: both open a browser, but only the button carries a
 * button role and a 48dp touch target for free. The occlusion lane measures interactive nodes, so
 * the button is also the thing that makes this screen's navigation-bar guard able to see anything
 * at the bottom of the list at all.
 */
@Composable
private fun ProviderLink(
    label: Int,
    url: String,
) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = { uriHandler.openUri(url) },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(stringResource(label))
    }
}

/** The link TMDB's terms name explicitly: *"please point your link to https://www.themoviedb.org"*. */
private const val TMDB_URL: String = "https://www.themoviedb.org"

private const val GOOGLE_BOOKS_URL: String = "https://books.google.com"

private const val OPEN_LIBRARY_URL: String = "https://openlibrary.org"
