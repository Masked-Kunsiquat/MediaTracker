package com.hub.media.ui

/**
 * State of the search process on the "add book" screen (ROADMAP Task 9 Phase B2).
 *
 * Search is orthogonal to the add-by-ISBN flow: a query failure does not fail the add flow, and
 * vice versa. A search result's terminal state is the user selecting one — what happens next (edition
 * resolution, ISBN lookup, ingestion) is the responsibility of [AddBookUiState].
 */
public sealed class AddSearchState {
    /** No search has been initiated, or [AddBookViewModel.clearSearch] was called. */
    public data object Idle : AddSearchState()

    /** A search is in flight; results are stale or unavailable. */
    public data object Searching : AddSearchState()

    /**
     * The most recent search completed without error but found no matches. Distinct from
     * [Error] so the UI can show "no results" rather than a failure message.
     */
    public data object NoResults : AddSearchState()

    /**
     * The most recent search failed.
     *
     * @property reason Typed reason for the failure, for localization.
     */
    public data class Error(
        val reason: AddSearchErrorReason,
    ) : AddSearchState()
}

/** Typed reasons for a search or resolution failure (ROADMAP Task 9 Phase B2). */
public sealed class AddSearchErrorReason {
    /** Selected result has no edition key; cannot resolve to ISBN. */
    public data object MissingEditionKey : AddSearchErrorReason()

    /** Selected edition has no ISBN in the provider index. */
    public data object MissingIsbn : AddSearchErrorReason()

    /**
     * The search or lookup failed with a generic error (network, parse, status).
     *
     * @property message Diagnostic description of the failure.
     */
    public data class Generic(
        val message: String,
    ) : AddSearchErrorReason()
}
