package com.hub.media.features.portability.domain

/**
 * How [ImportDataUseCase] handles an imported row that matches a book/session already in the
 * database (ROADMAP Task 8 Phase B) -- see that class's KDoc for the exact matching precedence and
 * per-field rules each policy applies.
 */
public enum class DuplicatePolicy {

    /** Leave the existing record completely untouched; the imported row is discarded. */
    SKIP,

    /** Overwrite every field this importer manages with the imported row's values. */
    REPLACE,

    /** Backfill only fields the existing record has left null/blank; never overwrite a set value. */
    MERGE,
}
