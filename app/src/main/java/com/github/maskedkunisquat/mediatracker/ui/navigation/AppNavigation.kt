package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookScreenRoute
import com.github.maskedkunisquat.mediatracker.ui.screens.LibraryScreenRoute
import com.hub.media.ui.AppContainer

/**
 * Sets up the NavHost with routes and navigation graph for the entire app.
 *
 * Routes:
 * - [Route.Library]: The library screen (start destination).
 * - [Route.AddBook]: The add-book-by-ISBN screen.
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
    }
}
