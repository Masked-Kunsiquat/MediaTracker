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

/**
 * Reflows one block of changelog source into display text.
 *
 * `CHANGELOG.md` is hard-wrapped at ~100 columns for reviewability in a diff, and its continuation
 * lines are indented. Rendering those line breaks verbatim on a phone produces ragged, zigzagging
 * text -- every source break becomes a display break at a width that has nothing to do with the
 * screen. Markdown's own rule is the right one: a single newline inside a paragraph is a *soft*
 * wrap and should join with a space, and only a blank line starts a new paragraph.
 *
 * Sub-bullets are kept as their own lines and marked with a bullet character, because collapsing
 * them into the running text (which is what naive joining does) loses the structure that made them
 * sub-bullets -- they read as a stray hyphen mid-sentence.
 */
internal fun reflow(raw: String): String {
    val blocks = mutableListOf<Pair<Boolean, String>>()
    val current = StringBuilder()
    var isBullet = false

    fun flush() {
        if (current.isNotEmpty()) {
            blocks += isBullet to current.toString().trim()
            current.clear()
        }
    }

    for (line in raw.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flush()
            isBullet = false
            continue
        }
        if (trimmed.startsWith("- ")) {
            flush()
            isBullet = true
            current.append(trimmed.removePrefix("- "))
            continue
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(trimmed)
    }
    flush()

    return buildString {
        blocks.forEachIndexed { index, (bullet, text) ->
            if (index > 0) {
                // Bullets in a run stay tight; anything else gets a blank line so paragraphs read
                // as paragraphs rather than one wall of text.
                append(if (bullet && blocks[index - 1].first) "\n" else "\n\n")
            }
            if (bullet) append("• ")
            append(text)
        }
    }
}

/**
 * The one `###` section title the viewer does not show.
 *
 * `CHANGELOG.md` serves two audiences that had been silently merged. Most of it is written for the
 * person using the app -- `[0.11.1]` opens "Books were quietly losing their authors" -- but the CI
 * and tooling entries are written for whoever maintains the repository, and the file is copied into
 * the app's assets verbatim, so those were appearing on the What's New screen next to release prose.
 * Putting them under `### Internal` keeps one file and one source of truth while letting the screen
 * show only the half addressed to its reader.
 *
 * Matched case-insensitively on purpose. The two failure directions are not symmetric: hiding a
 * section that should have been shown is a missing line on a screen, while showing one that should
 * have been hidden puts build-pipeline detail in front of a user. A stray `### internal` should
 * still be hidden.
 */
private const val INTERNAL_SECTION = "Internal"

private val VERSION_HEADING = Regex("""^##\s+\[([^\]]+)\]\s*(?:-\s*(.+))?\s*$""")
private val SECTION_HEADING = Regex("""^###\s+(.+?)\s*$""")
private val BOLD_LEAD = Regex("""^\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)

/**
 * Parses [markdown] into a [ChangelogDocument].
 *
 * Content before the first `## [` heading (the file's title and format blurb) is skipped: it
 * describes the changelog rather than any release, so it has no place in a per-version viewer.
 * `### Internal` sections are omitted entirely -- see [INTERNAL_SECTION].
 *
 * Never throws -- an input with no version headings at all yields an empty document.
 */
public fun parseChangelog(markdown: String): ChangelogDocument {
    val versions = mutableListOf<ChangelogVersion>()
    var current: VersionBuilder? = null

    fun finishCurrent() {
        val built = current?.build() ?: return
        // A version left with nothing after [INTERNAL_SECTION] is dropped rather than rendered as a
        // bare heading with nothing under it -- which would be a worse screen than the noise this
        // removes. The preamble is checked too, so a release whose prose carries the story keeps its
        // entry even if every one of its sections was internal.
        if (built.preamble.isNotBlank() || built.sections.isNotEmpty()) versions += built
    }

    for (rawLine in markdown.lineSequence()) {
        val versionMatch = VERSION_HEADING.matchEntire(rawLine)
        if (versionMatch != null) {
            finishCurrent()
            current =
                VersionBuilder(
                    version = versionMatch.groupValues[1],
                    date = versionMatch.groupValues[2].takeIf { it.isNotBlank() },
                )
            continue
        }
        current?.accept(rawLine)
    }
    finishCurrent()
    return ChangelogDocument(versions)
}

/** Accumulates one version's lines, splitting them into preamble, sections and bullets. */
private class VersionBuilder(
    private val version: String,
    private val date: String?,
) {
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
        entries +=
            if (lead != null) {
                ChangelogEntry(
                    heading = lead.groupValues[1].replace("\n", " ").trim(),
                    // Entries are written "**Title** - detail", so removing the title leaves the
                    // separator dangling at the start of the body. Stripped here rather than in the UI
                    // so every renderer gets the same clean text. Covers hyphen, en dash and em dash --
                    // this changelog uses the last of those, but the others cost nothing to accept.
                    body =
                        reflow(
                            text.removeRange(lead.range).trim().trimStart('-', '–', '—', ' '),
                        ),
                )
            } else {
                ChangelogEntry(heading = null, body = reflow(text))
            }
    }

    private fun closeSection() {
        closeEntry()
        val title = sectionTitle
        // Dropped here rather than skipped while reading, so an [Internal] section's bullets are
        // still parsed normally and simply discarded. Bailing out earlier would mean teaching
        // accept() to track "am I inside a hidden section", which is the kind of mode that goes
        // wrong the moment the file grows a shape nobody anticipated.
        if (title != null && !title.equals(INTERNAL_SECTION, ignoreCase = true)) {
            sections += ChangelogSection(title, entries)
        }
        entries = mutableListOf()
        sectionTitle = null
    }

    fun build(): ChangelogVersion {
        closeSection()
        return ChangelogVersion(
            version = version,
            date = date,
            preamble = reflow(preamble.joinToString("\n")),
            sections = sections,
        )
    }
}
