package com.github.maskedkunisquat.mediatracker.ui.navigation

import com.hub.media.core.database.entities.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hub's routes (#126): the start destination, and the library's optional domain. */
class RoutesTest {
    @Test
    fun theHub_hasItsOwnRoute() {
        assertEquals("hub", Route.Hub.route)
    }

    @Test
    fun allMedia_isTheLibraryWithNoDomain() {
        assertEquals("library", Route.Library.createRoute(null))
    }

    @Test
    fun aDomainLibrary_carriesItsType() {
        assertEquals("library?domain=BOOK", Route.Library.createRoute(MediaType.BOOK))
        assertEquals("library?domain=TV_SHOW", Route.Library.createRoute(MediaType.TV_SHOW))
    }

    @Test
    fun theLibraryPattern_acceptsTheOptionalDomain() {
        assertEquals("library?domain={domain}", Route.Library.route)
    }
}
