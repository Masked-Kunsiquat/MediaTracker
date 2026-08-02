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
 * Aspect ratio (width / height) shared by every book-cover container app-wide (library rows, the
 * detail screen header, the enlarged cover view), so covers get a predictable, uniform footprint
 * regardless of a specific image's actual pixel dimensions -- apply via `Modifier.aspectRatio(
 * BOOK_COVER_ASPECT_RATIO)` on the containing `Box`, not on [CoverImage] itself (it has no opinion
 * on layout size, only on how its content fills whatever box it's given).
 *
 * Book covers are conventionally taller than wide, ~2:3 width:height -- not the squarer 3:4 a
 * fixed 120dp x 160dp box previously assumed, which caused [ContentScale.Crop] to slice the top
 * and bottom off many covers.
 */
const val BOOK_COVER_ASPECT_RATIO = 2f / 3f

/**
 * Renders a cover image for a media item.
 *
 * If [coverImageHash] is null, displays a placeholder with a book icon.
 * Otherwise, asynchronously loads the image from disk (via [coverDir]/[coverImageHash])
 * off-thread (Dispatchers.IO) using [produceState]. On failure (missing file or decode error),
 * falls back to the placeholder.
 *
 * The placeholder branch is rendered with the exact same [modifier] as the image branch (both
 * simply apply it to their respective root), so a caller that pins [modifier] to
 * [BOOK_COVER_ASPECT_RATIO] gets a uniform footprint either way -- a placeholder that ignored the
 * aspect ratio would reintroduce ragged rows/columns whenever some covers are missing and others
 * aren't.
 *
 * @param coverDir Absolute path to the directory containing cover images (e.g.,
 *   context.filesDir + "/covers").
 * @param coverImageHash Complete cover filename as stored in the database (`<sha256>.jpg`,
 *   exactly what LocalImageStorageManager returned), or null if no cover exists.
 * @param modifier Modifier for the entire composable (Box container).
 * @param contentScale How the decoded image fills [modifier]'s bounds. Defaults to
 *   [ContentScale.Crop] (the pre-existing behavior, appropriate for a uniform grid/row look where
 *   cropping a bit of the cover is an acceptable trade for every row being the same height); pass
 *   [ContentScale.Fit] for a full-cover view (e.g. the detail screen header, or an enlarged
 *   preview) where nothing should be cut off.
 */
@Composable
fun CoverImage(
    coverDir: String,
    coverImageHash: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
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
            contentScale = contentScale,
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
