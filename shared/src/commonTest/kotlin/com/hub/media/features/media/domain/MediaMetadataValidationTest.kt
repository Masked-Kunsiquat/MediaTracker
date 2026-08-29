package com.hub.media.features.media.domain

import com.hub.media.features.books.domain.BookMetadataValidation
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.features.tv.data.TVMetadataValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Direct tests for [MediaMetadataValidation], added when it was extracted in #81.
 *
 * The rules it now owns were previously exercised only *through* their callers -- a repository
 * test that saves a book with a negative price proves the check fires, but only for that one
 * caller, and it cannot say anything about the boundary values it does not happen to use. That is
 * why the extraction is the right moment to write these: an object every media type delegates to
 * should be falsifiable on its own terms rather than as a side effect of somebody else's test.
 *
 * The delegation tests at the bottom are deliberately about *identity of outcome*, not about which
 * function calls which. They exist because the failure this refactor could plausibly introduce is
 * one media type quietly ending up on a different rule than the others -- which no repository test
 * would catch, since each one only ever asks about its own medium.
 */
class MediaMetadataValidationTest {
    // ---- validateTitle ----

    @Test
    fun validateTitle_nonBlank_passes() {
        assertNull(MediaMetadataValidation.validateTitle("Dune"))
    }

    @Test
    fun validateTitle_empty_isRejected() {
        assertEquals("Title must not be blank", MediaMetadataValidation.validateTitle(""))
    }

    @Test
    fun validateTitle_whitespaceOnly_isRejected() {
        // isBlank(), not isEmpty() -- a title of spaces and tabs is not a title, and a CSV row
        // padded with whitespace is the realistic way this arrives.
        assertEquals("Title must not be blank", MediaMetadataValidation.validateTitle("  \t "))
    }

    // ---- validatePurchasePrice ----

    @Test
    fun validatePurchasePrice_null_passes() {
        // null is "unknown", which is a legitimate state and distinct from "unreadable".
        assertNull(MediaMetadataValidation.validatePurchasePrice(null))
    }

    @Test
    fun validatePurchasePrice_zero_passes() {
        // Free is a real price. The bound is >= 0, not > 0.
        assertNull(MediaMetadataValidation.validatePurchasePrice(0.0))
    }

    @Test
    fun validatePurchasePrice_negative_isRejected() {
        assertEquals(
            "Purchase price must not be negative",
            MediaMetadataValidation.validatePurchasePrice(-0.01),
        )
    }

    @Test
    fun validatePurchasePrice_nan_isRejected() {
        // The reason this object exists rather than three copies of it. Every comparison involving
        // NaN other than != is false per IEEE 754, so `price < 0.0` is false for NaN and a plain
        // negativity check accepts it. A NaN price then poisons every sum and average it enters.
        assertEquals(
            "Purchase price must be a finite number",
            MediaMetadataValidation.validatePurchasePrice(Double.NaN),
        )
    }

    @Test
    fun validatePurchasePrice_infinity_isRejected() {
        // Reachable by hand: "Infinity" typed into a price field parses cleanly via toDoubleOrNull,
        // and is >= 0.0, so the form's own Save gate lets it through. This check is what stops it.
        assertEquals(
            "Purchase price must be a finite number",
            MediaMetadataValidation.validatePurchasePrice(Double.POSITIVE_INFINITY),
        )
    }

    @Test
    fun validatePurchasePrice_negativeInfinity_isRejected_asNonFinite() {
        // Both checks would reject this; the finite check runs first, so the message says so.
        // Asserted because the ordering is what makes the message predictable.
        assertEquals(
            "Purchase price must be a finite number",
            MediaMetadataValidation.validatePurchasePrice(Double.NEGATIVE_INFINITY),
        )
    }

    // ---- validateReleaseYear ----

    @Test
    fun validateReleaseYear_null_passes() {
        assertNull(MediaMetadataValidation.validateReleaseYear(null, minYear = 1888, maxYear = 2100))
    }

    @Test
    fun validateReleaseYear_bothBounds_areInclusive() {
        assertNull(MediaMetadataValidation.validateReleaseYear(1888, minYear = 1888, maxYear = 2100))
        assertNull(MediaMetadataValidation.validateReleaseYear(2100, minYear = 1888, maxYear = 2100))
    }

    @Test
    fun validateReleaseYear_outsideEitherBound_isRejected_andMessageQuotesTheBounds() {
        // The message interpolates the caller's own bounds, which is the whole point of passing
        // them in -- a film rejected for being too old must not be told the book floor.
        assertEquals(
            "Release year must be between 1888 and 2100",
            MediaMetadataValidation.validateReleaseYear(1887, minYear = 1888, maxYear = 2100),
        )
        assertEquals(
            "Release year must be between 1888 and 2100",
            MediaMetadataValidation.validateReleaseYear(2101, minYear = 1888, maxYear = 2100),
        )
    }

    // ---- the three media validators agree, and disagree, in the right places ----

    @Test
    fun everyMediaType_appliesTheSameTitleAndPriceRules() {
        // If any one of these drifted onto its own copy of the rule, this is where it shows up.
        for (title in listOf("", "   ", "Dune")) {
            val expected = MediaMetadataValidation.validateTitle(title)
            assertEquals(expected, BookMetadataValidation.validateTitle(title), "book title: '$title'")
            assertEquals(expected, MovieMetadataValidation.validateTitle(title), "movie title: '$title'")
            assertEquals(expected, TVMetadataValidation.validateTitle(title), "tv title: '$title'")
        }
        for (price in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val expected = MediaMetadataValidation.validatePurchasePrice(price)
            assertEquals(expected, BookMetadataValidation.validatePurchasePrice(price), "book price: $price")
            assertEquals(expected, MovieMetadataValidation.validatePurchasePrice(price), "movie price: $price")
            assertEquals(expected, TVMetadataValidation.validatePurchasePrice(price), "tv price: $price")
        }
    }

    @Test
    fun releaseYearFloors_stayPerMedium() {
        // The bounds are the part that must NOT be shared. Each medium accepts its own floor and
        // rejects the year before it; the book floor predates cinema by four centuries, so a film
        // dated 1450 has to be rejected even though a book dated 1450 is fine.
        assertNull(BookMetadataValidation.validateReleaseYear(1450))
        assertNotNull(BookMetadataValidation.validateReleaseYear(1449))

        assertNull(MovieMetadataValidation.validateReleaseYear(1888))
        assertNotNull(MovieMetadataValidation.validateReleaseYear(1887))
        assertNotNull(MovieMetadataValidation.validateReleaseYear(1450))

        assertNull(TVMetadataValidation.validateReleaseYear(1928))
        assertNotNull(TVMetadataValidation.validateReleaseYear(1927))
        assertNotNull(TVMetadataValidation.validateReleaseYear(1888))
    }

    @Test
    fun eachMediumStillEmitsItsOwnExactRejectionMessage() {
        // The refactor's headline claim is that no rejection message changed. The floors test above
        // only asserts "rejected", which a reworded message would still satisfy -- so these pin the
        // text, and pin that each medium quotes *its own* bounds rather than a shared pair.
        assertEquals(
            "Release year must be between 1450 and 2100",
            BookMetadataValidation.validateReleaseYear(1449),
        )
        assertEquals(
            "Release year must be between 1888 and 2100",
            MovieMetadataValidation.validateReleaseYear(1887),
        )
        assertEquals(
            "Release year must be between 1928 and 2100",
            TVMetadataValidation.validateReleaseYear(1927),
        )
    }

    @Test
    fun releaseYearCeiling_isSharedByEveryMedium() {
        assertEquals(MediaMetadataValidation.MAX_RELEASE_YEAR, BookMetadataValidation.MAX_RELEASE_YEAR)
        assertEquals(MediaMetadataValidation.MAX_RELEASE_YEAR, MovieMetadataValidation.MAX_RELEASE_YEAR)
        assertEquals(MediaMetadataValidation.MAX_RELEASE_YEAR, TVMetadataValidation.MAX_RELEASE_YEAR)
    }
}
