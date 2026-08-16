package com.hub.media.features.changelog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses the project's *real* `CHANGELOG.md` rather than a fixture.
 *
 * [ChangelogParserTest] proves the parser handles the `### Internal` rule; this proves the file
 * actually obeys it. Those are different failures: a heading typed `###Internal` or `### internal
 * notes` parses perfectly and silently ships build-pipeline detail to the What's New screen, with
 * every fixture test still green. The file is the artifact that reaches a user, so the file is what
 * this asserts against.
 *
 * Lives in `jvmTest` because it reads from disk, which is exactly the carve-out AGENTS.md §7
 * describes -- `commonTest` has no portable file access, which is why [ChangelogParserTest] inlines
 * its excerpt instead.
 *
 * The path is resolved relative to the `shared` module directory, the same assumption
 * `MigrationTest` already makes with `Path.of("schemas")`.
 */
class RealChangelogTest {
    private val changelog: String =
        Path.of("..", "CHANGELOG.md").let { path ->
            check(Files.exists(path)) { "Expected the real CHANGELOG.md at ${path.toAbsolutePath()}" }
            Files.readString(path)
        }

    @Test
    fun realChangelog_parsesIntoSomething() {
        // The positive control for every assertion below: they are all "X is absent" shaped, and
        // would pass trivially against an empty document. AGENTS.md §7 on tests that cannot fail.
        val doc = parseChangelog(changelog)
        assertTrue(doc.versions.size > 5, "expected many versions, got ${doc.versions.size}")
        assertTrue(
            doc.versions.any { version -> version.sections.any { it.entries.isNotEmpty() } },
            "every version parsed with no entries at all, which means the parser, not the file, is wrong",
        )
    }

    @Test
    fun realChangelog_exposesNoInternalSectionToTheViewer() {
        val leaked =
            parseChangelog(changelog)
                .versions
                .flatMap { version -> version.sections.map { version.version to it.title } }
                .filter { (_, title) -> title.equals("Internal", ignoreCase = true) }

        assertEquals(emptyList(), leaked, "an ### Internal section reached the What's New screen")
    }

    @Test
    fun realChangelog_internalHeadingIsSpelledExactlyAsTheParserExpects() {
        // The parser matches `### Internal` on its own line. `###Internal` (no space) or
        // `### Internal notes` are not matched and would be rendered in full -- and both are the
        // kind of thing that survives review, because the file still reads correctly to a human.
        val suspicious =
            changelog
                .lines()
                .map { it.trimEnd() }
                .filter { it.contains("Internal", ignoreCase = true) && it.trimStart().startsWith("#") }
                .filterNot { it == "### Internal" }

        assertEquals(emptyList(), suspicious, "heading mentions Internal but is not exactly '### Internal'")
    }
}
