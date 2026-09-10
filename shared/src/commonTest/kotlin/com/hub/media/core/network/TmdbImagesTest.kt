package com.hub.media.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [tmdbPosterUrl]. Pure, so it runs on every target.
 *
 * The size is pinned by a test rather than left to the constant alone: it is one of TMDB's published
 * widths, and an unlisted one is not resized by the CDN, it 404s. A change to it should be a
 * deliberate edit to a failing assertion rather than a silent one-character change.
 */
class TmdbImagesTest {
    @Test
    fun buildsAUrlFromTmdbsRelativePath() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc123.jpg",
            tmdbPosterUrl("/abc123.jpg"),
        )
    }

    @Test
    fun aMissingPosterIsNullRatherThanAUrlThatCanOnly404() {
        // TMDB returns both null and "" for a title with no artwork.
        assertNull(tmdbPosterUrl(null))
        assertNull(tmdbPosterUrl(""))
        assertNull(tmdbPosterUrl("   "))
    }

    @Test
    fun theRequestedWidthIsOneTmdbActuallyServes() {
        // w500 is on TMDB's published list. An unlisted width is not resized, it 404s -- so this is
        // pinned deliberately rather than treated as a free parameter.
        assertTrue(tmdbPosterUrl("/x.jpg")!!.contains("/w500/"), tmdbPosterUrl("/x.jpg")!!)
    }
}
