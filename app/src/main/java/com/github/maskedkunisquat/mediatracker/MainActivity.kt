package com.github.maskedkunisquat.mediatracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.github.maskedkunisquat.mediatracker.ui.navigation.AppNavigation
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.storage.coverStorageDirectory

/**
 * App entry point. Initializes the app container and sets up the navigation graph.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Retrieve the app container from the Application instance.
        val appContainer = (application as MediaTrackerApplication).appContainer
        val coverStorageDir = coverStorageDirectory(this)

        setContent {
            MediaTrackerTheme {
                MediaTrackerApp(
                    appContainer = appContainer,
                    coverStorageDir = coverStorageDir,
                )
            }
        }
    }
}

@Composable
private fun MediaTrackerApp(
    appContainer: com.hub.media.ui.AppContainer,
    coverStorageDir: String,
) {
    val navController = rememberNavController()

    AppNavigation(
        navController = navController,
        appContainer = appContainer,
        coverStorageDir = coverStorageDir,
    )
}
