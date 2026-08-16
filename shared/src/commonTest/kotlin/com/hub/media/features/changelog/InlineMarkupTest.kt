package com.hub.media.features.changelog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [parseInlineMarkup] (ROADMAP Task 15 Phase B2b). The cases that matter are the malformed
 * ones: real changelog prose contains stray `*` and backticks, and eating characters would be a
 * worse failure than rendering one extra marker.
 */
class InlineMarkupTest {
    private fun render(spans: List<InlineSpan>) = spans.joinToString("") { it.text }

    @Test
    fun parseInlineMarkup_plainText_isOneUnstyledSpan() {
        assertEquals(listOf(InlineSpan("just words")), parseInlineMarkup("just words"))
    }

    @Test
    fun parseInlineMarkup_boldRun_isMarkedAndMarkersAreStripped() {
        val spans = parseInlineMarkup("a **bold** b")

        assertEquals(listOf("a ", "bold", " b"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.bold })
    }

    @Test
    fun parseInlineMarkup_codeRun_isMarkedAndMarkersAreStripped() {
        val spans = parseInlineMarkup("call `BookWriteDao` now")

        assertEquals(listOf("call ", "BookWriteDao", " now"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.code })
    }

    @Test
    fun parseInlineMarkup_asterisksInsideCode_stayLiteralRatherThanTurningBold() {
        val spans = parseInlineMarkup("`a**b`")

        assertEquals(1, spans.size)
        assertEquals("a**b", spans[0].text)
        assertTrue(spans[0].code && !spans[0].bold)
    }

    @Test
    fun parseInlineMarkup_unmatchedBoldMarker_isKeptAsLiteralText() {
        // Losing characters is worse than showing a marker -- see parseInlineMarkup's KDoc.
        val spans = parseInlineMarkup("a ** dangling")

        assertEquals("a ** dangling", render(spans))
        assertTrue(spans.none { it.bold })
    }

    @Test
    fun parseInlineMarkup_unmatchedBacktick_isKeptAsLiteralText() {
        val spans = parseInlineMarkup("run `git commit and stop")

        assertEquals("run `git commit and stop", render(spans))
        assertTrue(spans.none { it.code })
    }

    @Test
    fun parseInlineMarkup_everyInput_preservesEveryNonMarkerCharacter() {
        // The invariant that makes the whole thing safe: whatever the markup, nothing silently
        // disappears except the markers that were genuinely matched.
        val inputs =
            listOf(
                "**bold** and `code`",
                "***",
                "``",
                "`",
                "**",
                "a*b",
                "**a `b` c**",
                "no markup at all",
            )
        for (input in inputs) {
            val rendered = render(parseInlineMarkup(input))
            val strippedInput = input.replace("**", "").replace("`", "")
            assertTrue(
                rendered.length >= strippedInput.length,
                "input '$input' rendered as '$rendered', losing non-marker characters",
            )
        }
    }

    @Test
    fun parseInlineMarkup_emptyInput_yieldsNoSpans() {
        assertEquals(emptyList(), parseInlineMarkup(""))
    }

    @Test
    fun parseInlineMarkup_singleAsteriskRun_isItalicRatherThanLiteral() {
        val spans = parseInlineMarkup("repairs covers *and* authors")

        assertEquals(listOf("repairs covers ", "and", " authors"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.italic })
    }

    @Test
    fun parseInlineMarkup_unmatchedDoubleAsterisk_isNotConsumedAsAnEmptyItalicRun() {
        // Regression: adding italic support made the two asterisks of an unclosed `**` open and
        // immediately close an italic run, silently eating both characters.
        val spans = parseInlineMarkup("a ** dangling")

        assertEquals("a ** dangling", render(spans))
        assertTrue(spans.none { it.italic || it.bold })
    }

    @Test
    fun parseInlineMarkup_boldStillWinsOverItalicForDoubleMarkers() {
        val spans = parseInlineMarkup("**strong** not *light*")

        assertEquals(listOf("strong", " not ", "light"), spans.map { it.text })
        assertEquals(listOf(true, false, false), spans.map { it.bold })
        assertEquals(listOf(false, false, true), spans.map { it.italic })
    }
}
