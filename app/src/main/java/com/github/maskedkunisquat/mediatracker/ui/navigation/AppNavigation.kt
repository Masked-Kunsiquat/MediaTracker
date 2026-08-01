package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.BookDetailScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LibraryScreenRoute
import com.hub.media.ui.AppContainer

/**
 * Sets up the NavHost with routes and navigation graph for the entire app.
 *
 * Routes:
 * - [Route.Library]: The library screen (start destination).
 * - [Route.AddBook]: The add-book-by-ISBN screen.
 * - [Route.BookDetail]: The book detail screen, parameterized by [Route.BookDetail.ARG_BOOK_ID].
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
                onNavigateToBookDetail = { bookId ->
                    navController.navigate(Route.BookDetail.createRoute(bookId))
                },
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
            arguments = listOf(
                navArgument(Route.BookDetail.ARG_BOOK_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val bookId = requireNotNull(backStackEntry.arguments?.getString(Route.BookDetail.ARG_BOOK_ID)) {
                "Missing required argument: ${Route.BookDetail.ARG_BOOK_ID}"
            }
            BookDetailScreenRoute(
                appContainer = appContainer,
                coverStorageDir = coverStorageDir,
                bookId = bookId,
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }
    }
}
