package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.AddTVShowScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.BookDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.EditBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.EditMovieScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LibraryScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LogViewerScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieSearchScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.SettingsScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.StatsScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowSearchScreenRoute
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer

/**
 * Sets up the NavHost with routes and navigation graph for the entire app.
 *
 * Routes:
 * - [Route.Library]: The library screen (start destination).
 * - [Route.AddBook]: The add-book-by-ISBN screen.
 * - [Route.BookDetail]: The book detail screen, parameterized by [Route.BookDetail.ARG_BOOK_ID].
 * - [Route.EditBook]: The edit-book-metadata screen, parameterized by
 *   [Route.EditBook.ARG_BOOK_ID] (ROADMAP Task 6 Phase A).
 * - [Route.Stats]: The stats screen (ROADMAP Task 5 Phase C).
 * - [Route.Settings]: The Settings screen (ROADMAP Task 7 Phase B).
 *
 * @param navController The navigation controller managing back stack and route transitions.
 * @param appContainer The dependency container for creating ViewModels.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 */
@Composable
fun AppNavigation(
    navController: NavHostController,
    appContainer: AppContainer,
    coverStorageDir: String,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Library.route,
    ) {
        composable(Route.Library.route) {
            LibraryScreenRoute(
                appContainer = appContainer,
                coverStorageDir = coverStorageDir,
                onNavigateToAddBook = {
                    navController.navigate(Route.AddBook.route)
                },
                onNavigateToAddMovie = {
                    navController.navigate(Route.AddMovie.route)
                },
                onNavigateToAddTVShow = {
                    navController.navigate(Route.AddTVShow.route)
                },
                // Type-aware at last (ROADMAP Task 13 Phase B). Until a movie row could exist this
                // took an id alone and sent everything to Book Detail; the `when` is now
                // exhaustive, so adding a media type fails to compile here rather than silently
                // routing to the wrong screen.
                onNavigateToMediaDetail = { mediaId, type ->
                    when (type) {
                        MediaType.BOOK -> navController.navigate(Route.BookDetail.createRoute(mediaId))
                        MediaType.MOVIE -> navController.navigate(Route.MovieDetail.createRoute(mediaId))
                        MediaType.TV_SHOW -> navController.navigate(Route.TVShowDetail.createRoute(mediaId))
                    }
                },
                onNavigateToStats = {
                    navController.navigate(Route.Stats.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Route.Settings.route)
                },
            )
        }

        composable(Route.AddMovie.route) {
            MovieSearchScreenRoute(
                appContainer = appContainer,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToManualEntry = { navController.navigate(Route.AddMovieManual.route) },
                // Replaces the whole add flow in the back stack, so Back from the detail screen
                // returns to the library rather than to a spent search.
                onMovieAdded = { movieId ->
                    navController.navigate(Route.MovieDetail.createRoute(movieId)) {
                        popUpTo(Route.AddMovie.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.AddMovieManual.route) {
            AddMovieScreenRoute(
                appContainer = appContainer,
                onNavigateBack = { navController.navigateUp() },
                // popUpTo AddMovie, not AddMovieManual: the search screen is still underneath, and
                // leaving it there would put a spent search between the new film and the library.
                onMovieAdded = { movieId ->
                    navController.navigate(Route.MovieDetail.createRoute(movieId)) {
                        popUpTo(Route.AddMovie.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Route.MovieDetail.route,
            arguments =
                listOf(
                    navArgument(Route.MovieDetail.ARG_MOVIE_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val movieId =
                requireNotNull(backStackEntry.arguments?.getString(Route.MovieDetail.ARG_MOVIE_ID)) {
                    "Missing required argument: ${Route.MovieDetail.ARG_MOVIE_ID}"
                }
            MovieDetailScreenRoute(
                appContainer = appContainer,
                movieId = movieId,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToEditMovie = {
                    navController.navigate(Route.EditMovie.createRoute(movieId))
                },
            )
        }

        composable(
            route = Route.EditMovie.route,
            arguments =
                listOf(
                    navArgument(Route.EditMovie.ARG_MOVIE_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val movieId =
                requireNotNull(backStackEntry.arguments?.getString(Route.EditMovie.ARG_MOVIE_ID)) {
                    "Missing required argument: ${Route.EditMovie.ARG_MOVIE_ID}"
                }
            EditMovieScreenRoute(
                appContainer = appContainer,
                movieId = movieId,
                onNavigateBack = { navController.navigateUp() },
            )
        }

        composable(Route.AddTVShow.route) {
            TVShowSearchScreenRoute(
                appContainer = appContainer,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToManualEntry = { navController.navigate(Route.AddTVShowManual.route) },
                // Straight to the new show, replacing the *whole* add flow in the back stack so Back
                // from the detail screen returns to the library rather than to a spent search.
                onShowAdded = { showId ->
                    navController.navigate(Route.TVShowDetail.createRoute(showId)) {
                        popUpTo(Route.AddTVShow.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.AddTVShowManual.route) {
            AddTVShowScreenRoute(
                appContainer = appContainer,
                onNavigateBack = { navController.navigateUp() },
                // popUpTo AddTVShow, not AddTVShowManual: the search screen is still underneath, and
                // leaving it there would put a spent search between the new show and the library.
                onShowAdded = { showId ->
                    navController.navigate(Route.TVShowDetail.createRoute(showId)) {
                        popUpTo(Route.AddTVShow.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Route.TVShowDetail.route,
            arguments =
                listOf(
                    navArgument(Route.TVShowDetail.ARG_SHOW_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val showId =
                requireNotNull(backStackEntry.arguments?.getString(Route.TVShowDetail.ARG_SHOW_ID)) {
                    "Missing required argument: ${Route.TVShowDetail.ARG_SHOW_ID}"
                }
            TVShowDetailScreenRoute(
                appContainer = appContainer,
                showId = showId,
                onNavigateBack = { navController.navigateUp() },
            )
        }

        composable(Route.AddBook.route) {
            AddBookScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
                onNavigateToLibrary = {
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Route.BookDetail.route,
            arguments =
                listOf(
                    navArgument(Route.BookDetail.ARG_BOOK_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val bookId =
                requireNotNull(backStackEntry.arguments?.getString(Route.BookDetail.ARG_BOOK_ID)) {
                    "Missing required argument: ${Route.BookDetail.ARG_BOOK_ID}"
                }
            // BookDetailScreenRoute wires the TopAppBar delete action to its own
            // BookDetailViewModel.deleteBook() (shared module), so no destination-scoped
            // LibraryViewModel workaround is needed here anymore.
            BookDetailScreenRoute(
                appContainer = appContainer,
                coverStorageDir = coverStorageDir,
                bookId = bookId,
                onNavigateBack = {
                    navController.navigateUp()
                },
                onNavigateToEditBook = {
                    navController.navigate(Route.EditBook.createRoute(bookId))
                },
            )
        }

        composable(
            route = Route.EditBook.route,
            arguments =
                listOf(
                    navArgument(Route.EditBook.ARG_BOOK_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val bookId =
                requireNotNull(backStackEntry.arguments?.getString(Route.EditBook.ARG_BOOK_ID)) {
                    "Missing required argument: ${Route.EditBook.ARG_BOOK_ID}"
                }
            EditBookScreenRoute(
                appContainer = appContainer,
                bookId = bookId,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        composable(Route.Stats.route) {
            StatsScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        composable(Route.Settings.route) {
            SettingsScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
                onNavigateToLogViewer = {
                    navController.navigate(Route.LogViewer.route)
                },
                onNavigateToChangelog = {
                    navController.navigate(Route.Changelog.route)
                },
            )
        }

        composable(Route.Changelog.route) {
            ChangelogScreenRoute(
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        composable(Route.LogViewer.route) {
            LogViewerScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }
    }
}
