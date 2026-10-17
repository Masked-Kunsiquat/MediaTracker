package com.hub.media.core.database.entities

import kotlin.test.Test
import kotlin.test.assertEquals

/** [splitAuthors] (#219): the inverse of [joinAuthors], for stats that count per author. */
class SplitAuthorsTest {
    @Test
    fun roundTripsWithJoinAuthors() {
        assertEquals(listOf("A", "B"), splitAuthors(joinAuthors(listOf("A", "B"))))
    }

    @Test
    fun blankEntriesAreDropped() {
        assertEquals(listOf("A", "B"), splitAuthors("A; ; B"))
    }

    @Test
    fun noAuthors_isAnEmptyList() {
        assertEquals(emptyList(), splitAuthors(null))
    }
}
