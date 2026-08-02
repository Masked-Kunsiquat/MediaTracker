package com.github.maskedkunisquat.mediatracker.ui.navigation

/**
 * Navigation routes for the app.
 *
 * Sealed interface for type-safe route construction. Each route has a [route] string
 * for NavController use, and a public `object` or `class` singleton/factory for route references.
 *
 * References: androidx.navigation.compose.navigate() and the NavHost route parameter
 * use these string values to identify and navigate between destinations.
 */
sealed interface Route {
    val route: String

    /**
     * Library screen showing the list of all books.
     */
    data object Library : Route {
        override val route: String = "library"
    }

    /**
     * Add-book-by-ISBN screen: single text field for ISBN entry.
     */
    data object AddBook : Route {
        override val route: String = "add_book"
    }

    /**
     * Book detail screen: cover + metadata, reading timer, session history, for a single book
     * identified by [ARG_BOOK_ID] (Task4 Phase C).
     */
    data object BookDetail : Route {
        /** NavHost argument key for the book id, used both here and by [createRoute]. */
        const val ARG_BOOK_ID: String = "bookId"

        /** Base path segment shared by [route] and [createRoute]. */
        private const val PATH: String = "book_detail"

        override val route: String = "$PATH/{$ARG_BOOK_ID}"

        /** Builds a concrete, navigable route string for a specific [bookId]. */
        fun createRoute(bookId: String): String = "$PATH/$bookId"
    }

    /**
     * Stats screen: aggregate reading stats for this week/month plus the current streak
     * (ROADMAP Task 5 Phase C).
     */
    data object Stats : Route {
        override val route: String = "stats"
    }
}
