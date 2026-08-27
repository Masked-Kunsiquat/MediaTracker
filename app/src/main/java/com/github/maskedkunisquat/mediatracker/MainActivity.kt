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
                // Surfaces every `Modifier.testTag` in this composition as `resource-id` in a
                // uiautomator dump (see TestTags). Set once at the root rather than per screen,
                // which would be seven places to forget.
                //
                // "This composition" is the limit and it is worth knowing: a Dialog, DropdownMenu
                // or Popup hosts its own AndroidComposeView with its own semantics root, so this
                // flag does not reach inside one. A testTag placed in Library's add menu or any of
                // Settings' dialogs will be in the semantics tree -- and so visible to the
                // Robolectric lane -- while being absent from a device dump. If you tag something
                // in a popup, set the flag again there.
                //
                // It is deliberately not gated on BuildConfig.DEBUG. The release build is what a
                // device check runs against when verifying a candidate, and a tag that vanishes in
                // the build you actually ship is a handle you cannot use when it matters. The cost
                // is a semantics property on nodes that already exist.
                Box(
                    // Both arguments preserve what setContent already did, rather than adding
                    // anything: its AndroidComposeView hands the content exact window-sized
                    // constraints (min == max). fillMaxSize keeps this Box that size, and
                    // propagateMinConstraints keeps it passing the minimum *down* -- a Box relaxes
                    // min to 0 by default, which would let a future wrap-content destination
                    // silently measure to its content instead of to the window. Every destination
                    // today uses Scaffold, which reads constraints.maxHeight, so nothing visible
                    // depends on this yet; that is exactly why it would be missed later.
                    propagateMinConstraints = true,
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
