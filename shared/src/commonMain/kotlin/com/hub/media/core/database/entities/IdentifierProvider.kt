package com.hub.media.core.database.entities

/**
 * The external catalog/API a [ExternalIdentifierEntity.externalId] is scoped to.
 * See AGENTS.md §4 for the API sources these correspond to.
 */
enum class IdentifierProvider {
    ISBN,
    OPEN_LIBRARY,
    GOOGLE_BOOKS,
    TMDB,
    TVDB,
}
