package com.github.maskedkunisquat.mediatracker.ui.components

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CoverImage"

/**
 * Renders a cover image for a media item.
 *
 * If [coverImageHash] is null, displays a placeholder with a book icon.
 * Otherwise, asynchronously loads the image from disk (via [coverDir]/[coverImageHash])
 * off-thread (Dispatchers.IO) using [produceState]. On failure (missing file or decode error),
 * falls back to the placeholder.
 *
 * @param coverDir Absolute path to the directory containing cover images (e.g.,
 *   context.filesDir + "/covers").
 * @param coverImageHash Complete cover filename as stored in the database (`<sha256>.jpg`,
 *   exactly what LocalImageStorageManager returned), or null if no cover exists.
 * @param modifier Modifier for the entire composable (Box container).
 */
@Composable
fun CoverImage(
    coverDir: String,
    coverImageHash: String?,
    modifier: Modifier = Modifier,
) {
    if (coverImageHash == null) {
        CoverPlaceholder(modifier = modifier)
        return
    }

    // Load the image off-thread via produceState.
    val imageBitmap = produceState<ImageBitmap?>(
        initialValue = null,
        coverDir,
        coverImageHash,
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                // coverImageHash is already the full "<sha256>.jpg" filename from the database
                BitmapFactory.decodeFile(File(coverDir, coverImageHash).absolutePath)
                    ?.asImageBitmap()
            } catch (e: Exception) {
                // Decode failed or file missing; fall through to placeholder.
                Log.w(TAG, "Failed to load cover image: $coverImageHash", e)
                null
            }
        }
    }.value

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = stringResource(R.string.cover_image_content_description),
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        CoverPlaceholder(modifier = modifier)
    }
}

/**
 * Placeholder displayed when no cover image is available.
 * Shows a book-like placeholder text centered in a light background.
 */
@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "📖",
            style = MaterialTheme.typography.displayLarge,
        )
    }
}
