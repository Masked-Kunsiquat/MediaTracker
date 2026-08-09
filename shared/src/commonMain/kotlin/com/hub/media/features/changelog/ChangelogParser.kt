package com.hub.media.features.changelog

/**
 * Parser for the project's own `CHANGELOG.md` (ROADMAP Task 15 Phase B2b), turning it into the
 * structure the in-app "What's new" viewer folds.
 *
 * ### Why a hand-rolled parser and not a Markdown library
 * AGENTS.md §5 rules out a new dependency without explicit approval, and a full Markdown parser for
 * one screen would not clear that bar. This does not need to be one: the input is not arbitrary
 * Markdown, it is *this project's* changelog, which follows Keep a Changelog and whose actual shape
 * was measured before this was written (see ROADMAP Task 15 Phase B2's "Why extraction is safe
 * here"). Nesting is effectively two levels, and 58 of 70 top-level bullets lead with a bold title.
 *
 * ### Degrading, not failing
 * The 12 top-level bullets that *don't* lead with a bold title are all short one-liners, so the
 * entries that need folding are exactly the ones carrying a usable header. That is what lets this
 * parser get away with a single fallback rule -- no bold lead means [ChangelogEntry.heading] is
 * `null` and the UI renders the entry flat -- rather than a pile of special cases. A future entry
 * written in some shape nobody anticipated yields a slightly less tidy screen, never a crash and
 * never an empty one.
 */

/** A parsed `CHANGELOG.md`. [versions] are in file order, i.e. newest first. */
public data class ChangelogDocument(
    val versions: List<ChangelogVersion>,
)

/**
 * One `## [x.y.z] - date` block.
 *
 * @property version The bracketed name exactly as written: a version like `"0.8.0"`, or
 *   `"Unreleased"`.
 * @property date The trailing date, or `null` for `[Unreleased]`, which never carries one.
 * @property preamble Everything between the version heading and its first `###` section, joined
 *   with newlines and blank where there is none.
 *
 *   Defined as "everything before the first `###`" rather than "the summary paragraph", because the
 *   latter is not a real invariant of this file: `[Unreleased]` -- the section most often read
 *   during development -- has no preamble at all, and `[0.8.0]` has two paragraphs. The looser rule
 *   is the one that actually holds for every version.
 * @property sections The `### Added`/`### Changed`/... blocks, in file order.
 */
public data class ChangelogVersion(
    val version: String,
    val date: String?,
    val preamble: String,
    val sections: List<ChangelogSection>,
)

/** One `### Added`-style block within a version. */
public data class ChangelogSection(
    val title: String,
    val entries: List<ChangelogEntry>,
)

/**
 * One top-level `- ` bullet, which is the unit the viewer collapses.
 *
 * @property heading The leading `**bold**` run, with its markers stripped -- the collapsible row's
 *   title. `null` when the bullet does not start with one, in which case the UI renders [body]
 *   flat instead of behind an expander (see this file's KDoc).
 * @property body Everything else in the bullet, including any indented sub-bullets, with the
 *   original indentation preserved so nesting still reads correctly once expanded.
 */
public data class ChangelogEntry(
    val heading: String?,
    val body: String,
)

private val VERSION_HEADING = Regex("""^##\s+\[([^\]]+)\]\s*(?:-\s*(.+))?\s*$""")
private val SECTION_HEADING = Regex("""^###\s+(.+?)\s*$""")
private val BOLD_LEAD = Regex("""^\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)

/**
 * Parses [markdown] into a [ChangelogDocument].
 *
 * Content before the first `## [` heading (the file's title and format blurb) is skipped: it
 * describes the changelog rather than any release, so it has no place in a per-version viewer.
 * Never throws -- an input with no version headings at all yields an empty document.
 */
public fun parseChangelog(markdown: String): ChangelogDocument {
    val versions = mutableListOf<ChangelogVersion>()
    var current: VersionBuilder? = null

    for (rawLine in markdown.lineSequence()) {
        val versionMatch = VERSION_HEADING.matchEntire(rawLine)
        if (versionMatch != null) {
            current?.let { versions += it.build() }
            current = VersionBuilder(
                version = versionMatch.groupValues[1],
                date = versionMatch.groupValues[2].takeIf { it.isNotBlank() },
            )
            continue
        }
        current?.accept(rawLine)
    }
    current?.let { versions += it.build() }
    return ChangelogDocument(versions)
}

/** Accumulates one version's lines, splitting them into preamble, sections and bullets. */
private class VersionBuilder(private val version: String, private val date: String?) {
    private val preamble = mutableListOf<String>()
    private val sections = mutableListOf<ChangelogSection>()
    private var sectionTitle: String? = null
    private var entries = mutableListOf<ChangelogEntry>()
    private var entryLines = mutableListOf<String>()

    fun accept(line: String) {
        SECTION_HEADING.matchEntire(line)?.let { match ->
            closeSection()
            sectionTitle = match.groupValues[1]
            return
        }
        // A top-level bullet starts a new entry. An indented one continues the current entry, which
        // is what keeps sub-bullets attached to the bullet they belong under rather than being
        // promoted to siblings of it.
        if (line.startsWith("- ")) {
            closeEntry()
            entryLines += line.removePrefix("- ")
            return
        }
        if (sectionTitle == null && entryLines.isEmpty()) {
            preamble += line
        } else {
            entryLines += line
        }
    }

    private fun closeEntry() {
        if (entryLines.isEmpty()) return
        val text = entryLines.joinToString("\n").trim()
        entryLines = mutableListOf()
        if (text.isEmpty()) return
        val lead = BOLD_LEAD.find(text)
        entries += if (lead != null) {
            ChangelogEntry(
                heading = lead.groupValues[1].replace("\n", " ").trim(),
                body = text.removeRange(lead.range).trim(),
            )
        } else {
            ChangelogEntry(heading = null, body = text)
        }
    }

    private fun closeSection() {
        closeEntry()
        val title = sectionTitle
        if (title != null) sections += ChangelogSection(title, entries)
        entries = mutableListOf()
        sectionTitle = null
    }

    fun build(): ChangelogVersion {
        closeSection()
        return ChangelogVersion(
            version = version,
            date = date,
            preamble = preamble.joinToString("\n").trim(),
            sections = sections,
        )
    }
}
