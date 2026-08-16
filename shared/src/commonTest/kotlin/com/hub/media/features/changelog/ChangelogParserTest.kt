package com.hub.media.features.changelog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [parseChangelog] (ROADMAP Task 15 Phase B2b).
 *
 * The fixtures deliberately reproduce the shapes measured in the real `CHANGELOG.md` rather than
 * idealised Markdown -- specifically the two that broke the obvious implementation: `[Unreleased]`
 * having no preamble at all, and a version having *two* preamble paragraphs. See ROADMAP Task 15
 * Phase B2's "Known tension" bullet for the measurements.
 */
class ChangelogParserTest {
    private val sample =
        """
        # Changelog

        Some blurb about the format that belongs to no release.

        ## [Unreleased]

        ### Added

        - **A feature** — did a thing.
          - a sub-bullet that belongs to the feature
        - a short one-liner with no bold lead

        ## [0.8.0] - 2026-08-08

        First preamble paragraph.

        Second preamble paragraph.

        ### Added

        - **Another feature** — more detail.

        ### Changed

        - **Something changed** — why.
        """.trimIndent()

    @Test
    fun parseChangelog_realWorldShape_splitsVersionsSectionsAndEntries() {
        val doc = parseChangelog(sample)

        assertEquals(listOf("Unreleased", "0.8.0"), doc.versions.map { it.version })
        assertEquals(listOf(null, "2026-08-08"), doc.versions.map { it.date })

        val unreleased = doc.versions[0]
        assertEquals(listOf("Added"), unreleased.sections.map { it.title })
        assertEquals(2, unreleased.sections[0].entries.size)

        val released = doc.versions[1]
        assertEquals(listOf("Added", "Changed"), released.sections.map { it.title })
    }

    @Test
    fun parseChangelog_unreleasedWithNoPreamble_yieldsBlankRatherThanStealingTheNextSection() {
        val doc = parseChangelog(sample)

        assertEquals(
            "",
            doc.versions.single { it.version == "Unreleased" }.preamble,
            "[Unreleased] genuinely has no preamble; it must not absorb the ### Added heading",
        )
        // Positive control: the parser CAN find a preamble, so the empty string above is a real
        // absence rather than the preamble logic being broken outright.
        assertTrue(
            doc.versions
                .single { it.version == "0.8.0" }
                .preamble
                .isNotEmpty(),
        )
    }

    @Test
    fun parseChangelog_versionWithTwoPreambleParagraphs_keepsBoth() {
        val preamble = parseChangelog(sample).versions.single { it.version == "0.8.0" }.preamble

        assertTrue(preamble.contains("First preamble paragraph."))
        assertTrue(
            preamble.contains("Second preamble paragraph."),
            "'the summary paragraph' is not a real invariant -- 0.8.0 has two, and both must survive",
        )
    }

    @Test
    fun parseChangelog_bulletWithBoldLead_splitsHeadingFromBody() {
        val entry = parseChangelog(sample).versions[0].sections[0].entries[0]

        assertEquals("A feature", entry.heading)
        assertTrue(entry.body.contains("did a thing"))
    }

    @Test
    fun parseChangelog_bulletWithoutBoldLead_hasNullHeadingSoTheUiRendersItFlat() {
        val entry = parseChangelog(sample).versions[0].sections[0].entries[1]

        assertNull(entry.heading, "no bold lead means no collapsible header")
        assertEquals("a short one-liner with no bold lead", entry.body)
    }

    @Test
    fun parseChangelog_indentedSubBullets_stayAttachedToTheirParentEntry() {
        val entries = parseChangelog(sample).versions[0].sections[0].entries

        assertEquals(2, entries.size, "a sub-bullet must not be promoted to a sibling entry")
        assertTrue(
            entries[0].body.contains("a sub-bullet that belongs to the feature"),
            "the sub-bullet belongs in its parent's body",
        )
    }

    @Test
    fun parseChangelog_contentBeforeTheFirstVersionHeading_isSkipped() {
        val doc = parseChangelog(sample)

        assertTrue(
            doc.versions.none { it.preamble.contains("Some blurb") },
            "the file's own title/format blurb describes no release",
        )
    }

    @Test
    fun parseChangelog_inputWithNoVersionHeadings_yieldsAnEmptyDocumentRatherThanThrowing() {
        assertEquals(0, parseChangelog("# Just a title\n\nsome prose").versions.size)
        assertEquals(0, parseChangelog("").versions.size)
    }

    @Test
    fun parseChangelog_theProjectsOwnChangelog_parsesIntoSomethingRenderable() {
        // The regression guard that matters: the fixtures above encode what this file looked like
        // when the parser was written, but the parser is aimed at the real thing, which keeps
        // growing. This asserts the shape the viewer depends on still holds for the actual content
        // shipped in assets -- if a future entry is written in a way that collapses the structure,
        // this fails here rather than showing an empty screen on a device nobody is testing on.
        val doc = parseChangelog(REAL_CHANGELOG_EXCERPT)

        val unreleased = assertNotNull(doc.versions.firstOrNull { it.version == "Unreleased" })
        assertTrue(unreleased.sections.isNotEmpty(), "[Unreleased] must still yield sections")
        assertTrue(
            unreleased.sections.flatMap { it.entries }.any { it.heading != null },
            "at least one entry must still carry a bold lead, or nothing is foldable",
        )
        val released = assertNotNull(doc.versions.firstOrNull { it.version == "0.8.0" })
        assertTrue(released.preamble.isNotEmpty(), "a released version must still have a preamble")
        assertTrue(released.date != null, "a released version must still carry its date")
    }

    @Test
    fun reflow_hardWrappedParagraph_joinsSoftBreaksIntoOneLine() {
        // CHANGELOG.md is hard-wrapped at ~100 columns for diff reviewability. Rendering those
        // breaks verbatim on a phone produced ragged, zigzagging text at a width unrelated to the
        // screen -- found by running the app on a device, not by any test.
        val out = reflow("a line that was\nwrapped in source\nacross three lines")

        assertEquals("a line that was wrapped in source across three lines", out)
    }

    @Test
    fun reflow_blankLine_startsANewParagraph() {
        val out = reflow("first para\nstill first\n\nsecond para")

        assertEquals("first para still first\n\nsecond para", out)
    }

    @Test
    fun reflow_subBullets_keepTheirOwnLinesRatherThanJoiningTheProse() {
        // Naive joining turned a sub-bullet into a stray hyphen mid-sentence.
        val out = reflow("intro\n- first bullet\n  wrapped on\n- second bullet")

        assertEquals(
            "intro\n\n\u2022 first bullet wrapped on\n\u2022 second bullet",
            out,
        )
    }

    @Test
    fun parseChangelog_entryBody_dropsTheSeparatorLeftBehindByRemovingTheHeading() {
        // Entries read "" so stripping the bold title leaves its dash dangling.
        val doc = parseChangelog("## [1.0.0]\n\n### Added\n\n- **Title** \u2014 the detail.")
        val entry = doc.versions[0].sections[0].entries[0]

        assertEquals("Title", entry.heading)
        assertEquals("the detail.", entry.body)
    }

    @Test
    fun parseChangelog_preamble_isReflowedNotJustTrimmed() {
        // The preamble is hard-wrapped in source exactly like entry bodies are. It was left
        // unreflowed at first and the tests above did not catch it, because they only asserted
        // 'contains' -- the ragged rendering was visible on a device instead.
        val doc =
            parseChangelog(
                "## [1.0.0]\n\na preamble that was\nwrapped in source.\n\n### Added\n\n- x",
            )

        assertEquals("a preamble that was wrapped in source.", doc.versions[0].preamble)
    }

    // --- `### Internal` is written for the repository, not the reader of the app ----------------

    @Test
    fun parseChangelog_internalSection_isOmittedWhileOthersSurvive() {
        val doc =
            parseChangelog(
                """
                ## [1.0.0]

                ### Added

                - **A real feature** — the user can see this.

                ### Internal

                - **Secret scanning (CI)** — gitleaks runs on every pull request.

                ### Fixed

                - **A real fix** — the user can see this too.
                """.trimIndent(),
            )

        val sections = doc.versions.single().sections
        assertEquals(listOf("Added", "Fixed"), sections.map { it.title })
        // The positive half matters as much as the negative: a parser that dropped everything would
        // also satisfy "Internal is absent".
        val headings = sections.flatMap { section -> section.entries.map { it.heading } }
        assertEquals(listOf("A real feature", "A real fix"), headings)
    }

    @Test
    fun parseChangelog_internalSectionEntries_doNotLeakIntoTheNeighbouringSection() {
        // The bullets under Internal are still parsed and then discarded. If the discard happened at
        // the wrong moment they would be flushed into whichever section closed next, which is the
        // failure this pins -- and it would look like a working feature, just with a stray entry.
        val doc =
            parseChangelog(
                "## [1.0.0]\n\n### Internal\n\n- **Tooling** — invisible.\n\n### Added\n\n- **Visible** — yes.",
            )

        val version = doc.versions.single()
        val added = version.sections.single()
        assertEquals("Added", added.title)
        assertEquals(listOf("Visible"), added.entries.map { it.heading })
    }

    @Test
    fun parseChangelog_internalSectionTitle_isMatchedCaseInsensitively() {
        // Failing open would put build detail in front of a user, so a stray lower-case heading is
        // still hidden. See INTERNAL_SECTION's KDoc on why the two directions are not symmetric.
        val doc = parseChangelog("## [1.0.0]\n\n### internal\n\n- **Tooling** — invisible.")

        assertTrue(doc.versions.isEmpty())
    }

    @Test
    fun parseChangelog_versionLeftEmptyByOmission_isDroppedRatherThanShownAsABareHeading() {
        val doc =
            parseChangelog(
                "## [1.0.0]\n\n### Internal\n\n- **Only tooling** — nothing for a reader.\n\n" +
                    "## [0.9.0]\n\n### Added\n\n- **Something real** — visible.",
            )

        assertEquals(listOf("0.9.0"), doc.versions.map { it.version })
    }

    @Test
    fun parseChangelog_versionWithPreambleButOnlyInternalSections_keepsItsPreamble() {
        // Dropping on "no sections" alone would silently discard a release whose story lives in its
        // preamble -- which is exactly how [0.8.0] is written.
        val doc =
            parseChangelog(
                "## [1.0.0]\n\nThe release prose that carries the story.\n\n" +
                    "### Internal\n\n- **Tooling** — invisible.",
            )

        val version = doc.versions.single()
        assertEquals("The release prose that carries the story.", version.preamble)
        assertTrue(version.sections.isEmpty())
    }
}

/**
 * A verbatim excerpt of the project's real `CHANGELOG.md` -- the `[Unreleased]` heading through the
 * start of `[0.8.0]`'s first section. Inlined rather than read from disk so this stays a pure
 * `commonTest` unit test with no file-I/O or asset plumbing; refreshed by hand if the changelog's
 * *structure* (not its content) ever changes.
 */
private val REAL_CHANGELOG_EXCERPT =
    """
    ## [Unreleased]

    ### Added

    - **Logging facility (ROADMAP Task 15 Phase A)** — `shared/` previously had no logging at all
      (no `Logger`, no `Napier`, not even a `println`).
      - **Verbosity is gated centrally, not per platform.** `AppLogger` wraps the platform sink.

    ### Changed

    - **Android Auto Backup no longer sends your library to Google Drive.** The rules files had
      shipped as the untouched sample templates.

    ## [0.8.0] - 2026-08-08

    Repairs an imported library. A Goodreads import previously produced books with neither covers
    nor authors; this release captures authors on ingestion.

    ### Added

    - **Author capture** (Room schema v5, `MIGRATION_4_5`) — `BookDetailsEntity` gained a column.
    """.trimIndent()
