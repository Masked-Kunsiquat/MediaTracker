package com.hub.media.features.portability.csv

/**
 * Reads a parsed row's details as the book variant, failing the test loudly if it is not one.
 *
 * Exists because [ParsedLibraryRow] carried the book columns directly until Issue #106 made movies
 * and shows importable, at which point they moved behind [ParsedRowDetails]. The book tests below
 * assert on those columns constantly, and a cast at every call site would bury what each test is
 * actually checking. The cast is safe by construction in every one of them: they all parse a row
 * whose `type` column is `BOOK`.
 *
 * Deliberately not a null-returning `as?`: a test whose row parsed as the wrong media type should
 * fail where the assumption broke, not read as an unexplained `null` several assertions later.
 */
internal val ParsedLibraryRow.book: ParsedRowDetails.Book
    get() = details as ParsedRowDetails.Book

/** The movie counterpart of [book]. */
internal val ParsedLibraryRow.movie: ParsedRowDetails.Movie
    get() = details as ParsedRowDetails.Movie

/** The show counterpart of [book]. */
internal val ParsedLibraryRow.show: ParsedRowDetails.TVShow
    get() = details as ParsedRowDetails.TVShow
