package com.hub.media.features.changelog

/**
 * The tiny inline-Markdown subset the changelog viewer renders (ROADMAP Task 15 Phase B2b): bold
 * (`**text**`) and inline code (`` `text` ``). Nothing else -- no links, no italics, no nesting.
 *
 * ### Why this exists rather than showing the raw text
 * Originally scheduled as optional polish on top of a plain-text dump. It moved into scope because
 * the viewer's fold structure needs it anyway: the `**bold**` lead that
 * [ChangelogEntry.heading] extracts *is* the collapsible header, so the parse happens regardless
 * and rendering it properly is nearly free once it has.
 *
 * ### Producing spans, not `AnnotatedString`
 * `AnnotatedString` is a Compose type and `shared/` is KMP-clean, so this returns a plain list of
 * [InlineSpan] that the app module maps onto Compose styling. That keeps the parsing testable
 * without a Compose test harness -- which matters here, since this project has none (see ROADMAP's
 * tech-debt entry).
 *
 * ### Unmatched markers are literal, deliberately
 * A lone `*` or a backtick with no partner is emitted as ordinary text rather than swallowed or
 * treated as an error. Changelog prose genuinely contains both -- a `*` used for emphasis in
 * passing, or a stray backtick in a quoted shell command -- and silently eating characters would be
 * a worse failure than showing one extra marker.
 */

/** A run of text with uniform styling. [bold] and [code] are independent and may both be set. */
public data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
)

/**
 * Splits [text] into styled runs. Adjacent runs with identical styling are merged, so the result is
 * the minimal span list for the input. Returns a single unstyled span for plain text, and an empty
 * list for empty input.
 */
public fun parseInlineMarkup(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    val buffer = StringBuilder()
    var bold = false
    var code = false
    var i = 0

    fun flush() {
        if (buffer.isNotEmpty()) {
            spans += InlineSpan(buffer.toString(), bold, code)
            buffer.clear()
        }
    }

    while (i < text.length) {
        val boldMarker = text.startsWith("**", i)
        // Inside a code run, `**` is not a bold marker: `` `a**b` `` is code containing asterisks.
        // Only a closing backtick can end a code run, which is what makes that hold.
        if (boldMarker && !code && (bold || hasCloser(text, i + 2, "**"))) {
            flush()
            bold = !bold
            i += 2
            continue
        }
        if (text[i] == '`' && (code || hasCloser(text, i + 1, "`"))) {
            flush()
            code = !code
            i += 1
            continue
        }
        buffer.append(text[i])
        i += 1
    }
    // An unterminated run reaching the end means its opening marker never had a partner. The text
    // was already emitted literally as it was consumed, so nothing is lost here.
    flush()
    return spans
}

/** True if [marker] appears again at or after [from], i.e. the run opening here actually closes. */
private fun hasCloser(text: String, from: Int, marker: String): Boolean =
    from <= text.length && text.indexOf(marker, from) >= 0
