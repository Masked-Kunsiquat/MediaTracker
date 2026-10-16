package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.github.maskedkunisquat.mediatracker.BuildConfig
import com.github.maskedkunisquat.mediatracker.ui.screens.AboutScreen
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.AddTVShowScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.BookDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.EditBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.EditMovieScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.EditTVShowScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.HubScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LibraryScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LogViewerScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.MismatchReviewScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieSearchScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.SettingsScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.StatsScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowSearchScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.UpNextScreenRoute
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer

/**
 * Sets up the NavHost with routes and navigation graph for the entire app.
 *
 * Routes:
 * - [Route.Hub]: The hub (start destination, #126): a card per domain, All media, Stats, Settings.
 * - [Route.Library]: The library, scoped to one domain by [Route.Library.ARG_DOMAIN], or All media
 *   without it.
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
        startDestination = Route.Hub.route,
    ) {
        guardedComposable(Route.Hub.route) {
            HubScreenRoute(
                appContainer = appContainer,
                onOpenDomain = { navController.navigate(Route.Library.createRoute(it)) },
                onOpenAllMedia = { navController.navigate(Route.Library.createRoute(null)) },
                onOpenStats = { navController.navigate(Route.Stats.route) },
                onOpenSettings = { navController.navigate(Route.Settings.route) },
            )
        }

        guardedComposable(
            route = Route.Library.route,
            arguments =
                listOf(
                    navArgument(Route.Library.ARG_DOMAIN) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { backStackEntry ->
            val domain = Route.Library.parseDomain(backStackEntry.arguments?.getString(Route.Library.ARG_DOMAIN))
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
                domain = domain,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToUpNext = {
                    navController.navigate(Route.UpNext.route)
                },
            )
        }

        guardedComposable(Route.AddMovie.route) {
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

        guardedComposable(Route.AddMovieManual.route) {
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

        guardedComposable(
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
                coverStorageDir = coverStorageDir,
                movieId = movieId,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToEditMovie = {
                    navController.navigate(Route.EditMovie.createRoute(movieId))
                },
            )
        }

        guardedComposable(
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
                coverStorageDir = coverStorageDir,
                onNavigateBack = { navController.navigateUp() },
            )
        }

        guardedComposable(Route.AddTVShow.route) {
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

        guardedComposable(Route.AddTVShowManual.route) {
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

        guardedComposable(
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
                coverStorageDir = coverStorageDir,
                showId = showId,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToEdit = { navController.navigate(Route.EditTVShow.createRoute(showId)) },
            )
        }

        guardedComposable(
            route = Route.EditTVShow.route,
            arguments =
                listOf(
                    navArgument(Route.EditTVShow.ARG_SHOW_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val showId =
                requireNotNull(backStackEntry.arguments?.getString(Route.EditTVShow.ARG_SHOW_ID)) {
                    "Missing required argument: ${Route.EditTVShow.ARG_SHOW_ID}"
                }
            EditTVShowScreenRoute(
                appContainer = appContainer,
                showId = showId,
                coverStorageDir = coverStorageDir,
                onNavigateBack = { navController.navigateUp() },
            )
        }

        guardedComposable(Route.AddBook.route) {
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

        guardedComposable(
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

        guardedComposable(
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
                coverStorageDir = coverStorageDir,
                bookId = bookId,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.Stats.route) {
            StatsScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.Settings.route) {
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
                onNavigateToMismatchReview = {
                    navController.navigate(Route.MismatchReview.route)
                },
                onNavigateToAbout = {
                    navController.navigate(Route.About.route)
                },
            )
        }

        // No `AboutScreenRoute` wrapper: the screen has no ViewModel and one parameter, so a
        // wrapper would exist only to read BuildConfig one call earlier. Passing the version from
        // here is also what keeps the screen itself testable at a pinned version -- see its KDoc.
        guardedComposable(Route.About.route) {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.UpNext.route) {
            UpNextScreenRoute(
                appContainer = appContainer,
                coverStorageDir = coverStorageDir,
                onOpenShow = { showId -> navController.navigate(Route.TVShowDetail.createRoute(showId)) },
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.MismatchReview.route) {
            MismatchReviewScreenRoute(
                appContainer = appContainer,
                coverStorageDir = coverStorageDir,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.Changelog.route) {
            ChangelogScreenRoute(
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }

        guardedComposable(Route.LogViewer.route) {
            LogViewerScreenRoute(
                appContainer = appContainer,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }
    }
}

/**
 * [composable], with the destination's content behind [BlockInputUntilResumed]: a screen that is
 * still arriving or already leaving takes no taps (#126). Every destination in [AppNavigation] uses
 * it, so the mis-tap cannot come back between any pair of screens.
 */
private fun NavGraphBuilder.guardedComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        BlockInputUntilResumed(entry) { content(entry) }
    }
}
