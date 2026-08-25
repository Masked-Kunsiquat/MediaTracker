package com.github.maskedkunisquat.mediatracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.rememberNavController
import com.github.maskedkunisquat.mediatracker.ui.navigation.AppNavigation
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.storage.coverStorageDirectory

/**
 * App entry point. Initializes the app container and sets up the navigation graph.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Retrieve the app container from the Application instance.
        val appContainer = (application as MediaTrackerApplication).appContainer
        val coverStorageDir = coverStorageDirectory(this)

        setContent {
            MediaTrackerTheme {
                // Surfaces every `Modifier.testTag` in the tree as `resource-id` in a uiautomator
                // dump (see TestTags). Set once at the root because the flag propagates down the
                // semantics tree; setting it per screen would be seven places to forget.
                //
                // It is deliberately not gated on BuildConfig.DEBUG. The release build is what a
                // device check runs against when verifying a candidate, and a tag that vanishes in
                // the build you actually ship is a handle you cannot use when it matters. The cost
                // is a semantics property on nodes that already exist.
                Box(
                    // fillMaxSize is not decoration: a bare Box wraps its content, so without it
                    // this root would constrain the navigation host to whatever the first screen
                    // happened to measure rather than to the window.
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                ) {
                    MediaTrackerApp(
                        appContainer = appContainer,
                        coverStorageDir = coverStorageDir,
                    )
                }
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
