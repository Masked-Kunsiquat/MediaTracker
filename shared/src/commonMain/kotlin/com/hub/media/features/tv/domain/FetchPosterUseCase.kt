package com.hub.media.features.tv.domain

import com.hub.media.core.database.MediaRepository
import com.hub.media.core.network.tmdbPosterUrl
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.warn
import com.hub.media.features.books.network.CoverImageDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "FetchPoster"

/**
 * Downloads a title's TMDB poster and records it against the row (ROADMAP Task 13 Phase D).
 *
 * ### Failing is a non-event
 * A poster is decoration. Every failure path here returns [Resource.Error] and every caller is
 * expected to ignore it: the title is already in the library by the time this runs, and a library
 * entry without artwork is a complete, usable row. Surfacing "could not download a poster" to
 * someone who just added a film would be reporting a problem they neither caused nor need to act on.
 *
 * That is why this does not run *before* the add. A poster fetched first would make artwork a
 * precondition of having the film at all.
 *
 * ### It stores a hash, not a URL
 * [LocalImageStorageManager] is content-addressed, and
 * [com.hub.media.core.database.entities.MediaItemEntity.coverImageHash] holds the name of a local
 * file. This app is offline-first (AGENTS.md §4): a stored remote URL would make every poster vanish
 * the moment the device lost signal, and would hand TMDB's CDN a say in whether the library renders.
 *
 * Content addressing also means two titles sharing artwork share one file, and re-running this over
 * a title that already has its poster rewrites nothing — the bytes hash to the same name.
 *
 * ### Works for films and shows alike
 * [MediaRepository.updateCoverImageHash] is universal rather than per-media-type, so nothing here
 * needs to know which it is holding. That is the same reason
 * [com.hub.media.core.database.entities.MediaItemEntity.communityRating] lives where it does.
 */
public class FetchPosterUseCase(
    private val coverDownloader: CoverImageDownloader,
    private val imageStorage: LocalImageStorageManager,
    private val mediaRepository: MediaRepository,
    private val scope: CoroutineScope,
    private val logger: Logger = AppLogger,
) {
    /**
     * Starts a fetch that outlives whoever asked for it, and returns immediately.
     *
     * ### Why this cannot run in a ViewModel's scope
     * Adding a title navigates with `popUpTo(inclusive = true)`, which **removes the search
     * destination from the back stack**. That clears its ViewModel and cancels `viewModelScope` —
     * so a download started there is killed within moments of being started, and whether a poster
     * arrives becomes a race between the CDN and the navigation animation. It won on a fast
     * connection during testing, which is exactly how this would have shipped unnoticed.
     *
     * [scope] is owned by [com.hub.media.ui.AppContainer] and lives as long as the process, so the
     * download, the file write and the row update all complete regardless of where the user goes
     * next.
     *
     * Fire-and-forget by design: the result is a poster or no poster, and [execute] logs the
     * difference. Nothing awaits this, because there is nothing a caller would do differently.
     */
    public fun enqueue(
        mediaId: String,
        posterPath: String?,
    ) {
        scope.launch { execute(mediaId, posterPath) }
    }

    /**
     * Fetches [posterPath] and records the resulting hash against [mediaId].
     *
     * @param posterPath TMDB's relative path, exactly as the API returned it. `null` or blank means
     *   the title has no artwork, which is answered without spending a request.
     * @return [Resource.Error] for every failure, including "there was no poster to fetch". Callers
     *   log and continue; see this class's KDoc on why none of them should do more.
     */
    public suspend fun execute(
        mediaId: String,
        posterPath: String?,
    ): Resource<Unit> {
        val url =
            tmdbPosterUrl(posterPath)
                ?: return Resource.Error("This title has no poster on TMDB.")

        return try {
            val bytes =
                when (val downloaded = coverDownloader.download(url)) {
                    is Resource.Error -> return downloaded
                    is Resource.Success -> downloaded.data
                }
            val hash =
                imageStorage.saveImage(bytes).getOrElse { error ->
                    logger.warn(TAG) { "Could not store a poster for $mediaId (${error::class.simpleName})" }
                    return Resource.Error("Could not store the downloaded poster.")
                }
            mediaRepository.updateCoverImageHash(mediaId, hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The URL is never interpolated into the message: it is not secret, but the download
            // path is shared with Open Library covers and this keeps one rule for both.
            logger.warn(TAG) { "Failed to fetch a poster for $mediaId (${e::class.simpleName})" }
            Resource.Error("Failed to fetch poster: ${e.message ?: "Unknown error"}", cause = e)
        }
    }
}
