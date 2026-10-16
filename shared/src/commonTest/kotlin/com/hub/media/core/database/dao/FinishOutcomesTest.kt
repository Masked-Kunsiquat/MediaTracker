package com.hub.media.core.database.dao

import kotlin.test.Test
import kotlin.test.assertEquals

/** [dnfPercent] (#219): DNF over finished-plus-DNF, rounded; nothing to divide is unknown, not 0%. */
class FinishOutcomesTest {
    @Test
    fun noFinishedOrDnfBooks_isUnknown() {
        assertEquals(null, FinishOutcomes(0, 0).dnfPercent())
    }

    @Test
    fun theRate_isRounded() {
        assertEquals(33, FinishOutcomes(finished = 2, dnf = 1).dnfPercent())
        assertEquals(14, FinishOutcomes(finished = 12, dnf = 2).dnfPercent())
    }

    @Test
    fun onlyDnf_isAHundredPercent() {
        assertEquals(100, FinishOutcomes(finished = 0, dnf = 3).dnfPercent())
    }
}
