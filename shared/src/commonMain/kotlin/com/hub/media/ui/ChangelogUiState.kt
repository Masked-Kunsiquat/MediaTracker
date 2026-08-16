package com.hub.media.ui

import com.hub.media.features.changelog.ChangelogDocument

/**
 * UI state for the in-app "What's new" viewer (ROADMAP Task 15 Phase B2b).
 *
 * @property document The parsed changelog, newest version first.
 * @property expandedVersions Versions currently expanded, keyed by
 *   [com.hub.media.features.changelog.ChangelogVersion.version]. Seeded with the running app's own
 *   version so the screen opens on what the user just got, with older releases behind a scroll.
 * @property expandedEntries Individually expanded entries, keyed by [entryKey].
 * @property isLoading True until the asset has been read and parsed.
 * @property failedToLoad True if the changelog asset could not be read at all -- distinct from a
 *   successfully-read-but-empty document, since only one of those is worth apologising for.
 */
public data class ChangelogUiState(
    val document: ChangelogDocument = ChangelogDocument(emptyList()),
    val expandedVersions: Set<String> = emptySet(),
    val expandedEntries: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val failedToLoad: Boolean = false,
)

/**
 * Stable identity for one entry's expand state.
 *
 * Composed from the version, section and index rather than the entry's heading: headings are not
 * unique (several releases have a "**Fixed**"-style repeat), and two entries sharing an expand
 * state would toggle together for no visible reason.
 */
public fun entryKey(
    version: String,
    sectionTitle: String,
    index: Int,
): String = "$version/$sectionTitle/$index"
