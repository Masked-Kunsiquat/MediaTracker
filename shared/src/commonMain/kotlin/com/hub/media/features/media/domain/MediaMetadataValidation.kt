package com.hub.media.features.media.domain

/**
 * The metadata validation rules that do not vary by media type.
 *
 * These lived on `BookMetadataValidation` until #81, because books were the only media type when
 * they were written. Movies and TV shows then delegated to them, and both objects carried the same
 * apology in their KDoc: *"reaching into a book-named object from movie code is admittedly odd, and
 * the honest fix is to rename it to something media-neutral. That is deliberately not done here."*
 * By the time that deferral was re-read it had four non-book consumers -- movies, TV,
 * `LibraryCsvImporter` and `GoodreadsCsvImporter` -- so it was cashed in.
 *
 * The split is by *whether the rule depends on the medium*, not by who happens to call it:
 * - A title must not be blank. True of a book, a film and a show alike.
 * - A price must be finite and non-negative. Money does not know what it bought.
 * - A release year must fall within bounds -- but **the bounds themselves are per-medium**, which
 *   is why [validateReleaseYear] takes them as parameters rather than owning them. Printing
 *   predates cinema by four centuries and broadcast television by four and a half; a shared floor
 *   would accept a film released before film existed. Each media type's validation object owns its
 *   own floor and documents why that year and not a rounder one.
 *
 * Rules that *do* depend on the medium stay with it: total pages on
 * [com.hub.media.features.books.domain.BookMetadataValidation], runtime on
 * [com.hub.media.features.movies.data.MovieMetadataValidation], seasons and episode counts on
 * [com.hub.media.features.tv.data.TVMetadataValidation].
 *
 * Contract, unchanged from the object this was extracted from: every function returns a
 * human-readable rejection message, or `null` when the value is valid. Callers short-circuit on
 * the first non-null message. Message text is byte-identical to what the book-named object
 * produced, so no caller observes a different [com.hub.media.core.util.Resource.Error] string.
 */
public object MediaMetadataValidation {
    /** A title must not be blank -- used for a manual edit, an imported CSV row, and every medium. */
    public fun validateTitle(title: String): String? = if (title.isBlank()) "Title must not be blank" else null

    /**
     * A known [purchasePrice] must be finite and `>= 0`; `null` ("unknown") always passes.
     *
     * The finite check is defence in depth, not just a CSV-import concern: `purchasePrice < 0.0` is
     * `false` for [Double.NaN] (every comparison involving `NaN` other than `!=` is `false` per
     * IEEE 754), so a plain negativity check alone would silently accept `NaN` and let it poison
     * that item's price everywhere it is later summed/averaged/compared. This isn't hypothetical:
     * [com.hub.media.features.portability.csv.parseRequiredDouble]/`parseOptionalDouble` already
     * reject non-finite CSV cells before calling this, but the manual edit forms parse their text
     * field and gate Save on `>= 0.0`, which is `true` for `Double.POSITIVE_INFINITY`
     * (`"Infinity"` typed into the field). This check is what actually stops that value from being
     * persisted, regardless of what the UI layer's own gate lets through.
     *
     * This is the specific rule that made forking these checks per media type a bad idea. It is a
     * one-line-looking function whose correctness rests on an IEEE 754 detail, so a re-derived copy
     * would be subtly wrong far more often than it would be right.
     */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        when {
            purchasePrice == null -> null
            !purchasePrice.isFinite() -> "Purchase price must be a finite number"
            purchasePrice < 0.0 -> "Purchase price must not be negative"
            else -> null
        }

    /**
     * A known [releaseYear] must fall within [minYear]..[maxYear]; `null` ("unknown") always passes.
     *
     * The bounds are parameters because they are the one part of this rule that is genuinely
     * per-medium -- see this object's KDoc. Callers pass their own floor and
     * [MAX_RELEASE_YEAR].
     */
    public fun validateReleaseYear(
        releaseYear: Int?,
        minYear: Int,
        maxYear: Int,
    ): String? =
        if (releaseYear != null && releaseYear !in minYear..maxYear) {
            "Release year must be between $minYear and $maxYear"
        } else {
            null
        }

    /**
     * Upper bound on a release year, shared by every media type.
     *
     * Unlike the floors, this one genuinely does not vary: it is a static far-future year rather
     * than "current year + N" derived from a clock, so the bound stays deterministic for tests
     * while still covering announced future releases for decades. All three media types had
     * independently arrived at 2100 with that same reasoning written out three times.
     */
    public const val MAX_RELEASE_YEAR: Int = 2100
}
