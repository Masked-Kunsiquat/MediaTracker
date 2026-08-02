package com.hub.media.features.books.domain

import com.hub.media.features.books.data.BookRepository

/**
 * Pure book-metadata validation rules, extracted from [BookRepository.updateBookMetadata]
 * (ROADMAP Task 8 Phase B) so the CSV importer (`ImportDataUseCase`) can apply the exact same
 * title/price/pages/year bounds a manual edit already enforces, rather than forking a second,
 * divergent copy of these checks -- AGENTS.md §7's "reuse, don't fork" requirement for this
 * phase's row validation.
 *
 * Every function returns a human-readable rejection message, or `null` when the value is valid --
 * callers (both [BookRepository.updateBookMetadata] and the importer) short-circuit on the first
 * non-null message. Message text is unchanged from the inline checks this replaces, so existing
 * callers observe identical [com.hub.media.core.util.Resource.Error] text.
 */
public object BookMetadataValidation {

    /** A title must not be blank -- used for both a manual edit and an imported CSV row. */
    public fun validateTitle(title: String): String? =
        if (title.isBlank()) "Title must not be blank" else null

    /** A known [purchasePrice] must be `>= 0`; `null` ("unknown") always passes. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        if (purchasePrice != null && purchasePrice < 0.0) "Purchase price must not be negative" else null

    /** A known [totalPages] must be `> 0`; `null` ("unknown") always passes. */
    public fun validateTotalPages(totalPages: Int?): String? =
        if (totalPages != null && totalPages <= 0) "Total pages must be a positive number" else null

    /**
     * A known [releaseYear] must fall within [BookRepository.MIN_RELEASE_YEAR]..
     * [BookRepository.MAX_RELEASE_YEAR]; `null` ("unknown") always passes.
     */
    public fun validateReleaseYear(releaseYear: Int?): String? =
        if (releaseYear != null && releaseYear !in BookRepository.MIN_RELEASE_YEAR..BookRepository.MAX_RELEASE_YEAR) {
            "Release year must be between ${BookRepository.MIN_RELEASE_YEAR} and ${BookRepository.MAX_RELEASE_YEAR}"
        } else {
            null
        }
}
