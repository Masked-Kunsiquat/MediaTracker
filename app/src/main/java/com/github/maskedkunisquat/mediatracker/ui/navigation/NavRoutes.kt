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
}
