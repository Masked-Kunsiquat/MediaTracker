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

    /** Manual movie entry (ROADMAP Task 13 Phase B). No provider lookup, so no arguments. */
    data object AddMovie : Route {
        override val route: String = "add_movie"
    }

    /**
     * Movie detail screen for a single movie identified by [ARG_MOVIE_ID] (Task 13 Phase B).
     *
     * A separate route from [BookDetail] rather than one polymorphic "media detail" route: the two
     * screens render different fields and drive different view models, so a shared route would
     * only branch on type immediately on arrival. Keeping them apart is what lets the library's
     * card tap decide the destination up front -- see `onNavigateToMediaDetail`.
     */
    data object MovieDetail : Route {
        /** NavHost argument key for the movie id, used both here and by [createRoute]. */
        const val ARG_MOVIE_ID: String = "movieId"

        /** Base path segment shared by [route] and [createRoute]. */
        private const val PATH: String = "movie_detail"

        override val route: String = "$PATH/{$ARG_MOVIE_ID}"

        /** Builds a concrete, navigable route string for a specific [movieId]. */
        fun createRoute(movieId: String): String = "$PATH/$movieId"
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
     * Edit-book-metadata screen: title/release year/purchase price/total pages/format form for a
     * single book identified by [ARG_BOOK_ID] (ROADMAP Task 6 Phase A). Mirrors [BookDetail]'s
     * PATH-constant pattern as its own destination rather than reusing [BookDetail]'s route, since
     * Compose Navigation scopes each `composable()` destination to its own ViewModel store.
     */
    data object EditBook : Route {
        /** NavHost argument key for the book id, used both here and by [createRoute]. */
        const val ARG_BOOK_ID: String = "bookId"

        /** Base path segment shared by [route] and [createRoute]. */
        private const val PATH: String = "edit_book"

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

    /**
     * Settings screen: app-wide preferences, starting with the week-start-day preference that
     * drives [Stats]'s period bounds (ROADMAP Task 7 Phase B).
     */
    data object Settings : Route {
        override val route: String = "settings"
    }

    /**
     * In-app log viewer, reached from the Settings screen's Diagnostics section
     * (ROADMAP Task 15 Phase B2).
     */
    data object LogViewer : Route {
        override val route: String = "log_viewer"
    }

    /**
     * In-app "What's new" changelog viewer, reached from Settings (ROADMAP Task 15 Phase B2b).
     */
    data object Changelog : Route {
        override val route: String = "changelog"
    }
}
