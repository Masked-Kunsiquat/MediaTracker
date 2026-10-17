package com.hub.media.ui

import com.hub.media.core.database.entities.MediaType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [openingOn] (#217 review): Stats opened from a library holds back the previously remembered tab
 * until the library's own tab is stored, so it never flashes the old tab first. Fed the sequence a
 * slow settings write produces on a phone, rather than relying on timing a test cannot create.
 */
class OpeningOnTest {
    @Test
    fun theOldTab_isHeldBack_thenEveryPickPasses() =
        runTest {
            val stored = flowOf(MediaType.BOOK, MediaType.MOVIE, MediaType.TV_SHOW, MediaType.BOOK)

            assertEquals(
                listOf(MediaType.MOVIE, MediaType.TV_SHOW, MediaType.BOOK),
                stored.openingOn(MediaType.MOVIE).toList(),
            )
        }

    @Test
    fun withNoOpeningTab_everythingPasses() =
        runTest {
            val stored = flowOf(MediaType.BOOK, MediaType.MOVIE)

            assertEquals(listOf(MediaType.BOOK, MediaType.MOVIE), stored.openingOn(null).toList())
        }
}
