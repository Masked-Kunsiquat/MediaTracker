package com.hub.media.features.stats.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [authorKey] (#219): spellings of one author that differ only in case, spacing or punctuation share
 * a key, so top authors counts them as one person.
 */
class AuthorKeyTest {
    @Test
    fun caseSpacingAndPunctuation_shareAKey() {
        assertEquals("jrrtolkien", authorKey("J.R.R. Tolkien"))
        assertEquals("jrrtolkien", authorKey("J. R. R. Tolkien"))
        assertEquals("jrrtolkien", authorKey("j r r tolkien"))
    }

    @Test
    fun nonLatinLetters_areKept() {
        assertEquals("charlottebrontë", authorKey("Charlotte Brontë"))
    }

    @Test
    fun punctuationOnly_isAnEmptyKey() {
        assertEquals("", authorKey("—"))
    }
}
