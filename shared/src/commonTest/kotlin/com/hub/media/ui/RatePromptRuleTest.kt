package com.hub.media.ui

import com.hub.media.core.database.entities.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** When "Rate this read?" opens (#230 review): only when a read goes from unfinished to ended. */
class RatePromptRuleTest {
    @Test
    fun asks_whenAReadEnds() {
        assertEquals(true, asksToRate(from = ReadingStatus.READING, to = ReadingStatus.FINISHED))
        assertEquals(true, asksToRate(from = ReadingStatus.READING, to = ReadingStatus.DNF))
        assertEquals(true, asksToRate(from = ReadingStatus.TO_READ, to = ReadingStatus.FINISHED))
    }

    @Test
    fun doesNotAsk_forARead_alreadyEnded_orNotEnding() {
        assertEquals(false, asksToRate(from = ReadingStatus.FINISHED, to = ReadingStatus.FINISHED))
        assertEquals(false, asksToRate(from = ReadingStatus.DNF, to = ReadingStatus.DNF))
        assertEquals(false, asksToRate(from = ReadingStatus.DNF, to = ReadingStatus.FINISHED))
        assertEquals(false, asksToRate(from = ReadingStatus.FINISHED, to = ReadingStatus.DNF))
        assertEquals(false, asksToRate(from = ReadingStatus.FINISHED, to = ReadingStatus.READING))
    }
}
