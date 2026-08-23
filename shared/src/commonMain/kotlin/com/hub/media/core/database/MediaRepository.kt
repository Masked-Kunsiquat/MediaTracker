package com.hub.media.core.database

import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.features.media.data.MediaWithDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/** Log tag for [MediaRepository] (ROADMAP Task 15 Phase C). */
private const val TAG = "MediaRepository"

/**
 * Repository for managing universal media item operations (Books, Movies, TV Shows).
 * Consolidated from [com.hub.media.features.books.data.BookRepository] per Issue #67.
 */
public class MediaRepository(
    private val db: AppDatabase,
    private val logger: Logger = AppLogger,
) {
    /**
     * Observes all media items in the database as a reactive stream, ordered by creation date (newest first).
     */
    public fun observeAllMedia(): Flow<List<MediaItemEntity>> = db.mediaItemDao().observeAll()

    /**
     * Observes all media items of a specific [type] as a reactive stream, ordered by title.
     */
    public fun observeMediaByType(type: MediaType): Flow<List<MediaItemEntity>> = db.mediaItemDao().observeByType(type)

    /**
     * Observes a single media item by ID as a reactive stream.
     */
    public fun observeMediaItem(id: String): Flow<MediaItemEntity?> = db.mediaItemDao().observeById(id)

    /**
     * Observes derived episode progress for every show that has episodes, keyed by show id
     * (ROADMAP Task 13 Phase C).
     *
     * A show with no episode rows is **absent from the map**, not present with zeros — the
     * underlying query groups by `mediaId`. [com.hub.media.ui.LibraryStatusFilter.ofShow] treats
     * that absence as "not started"; anything else reading this must decide the same question
     * deliberately rather than assume a key exists.
     */
    public fun observeTVProgressByMediaId(): Flow<Map<String, TVProgressRow>> =
        db.episodeDao().observeProgress().map { rows -> rows.associateBy { it.mediaId } }

    /**
     * Observes every episode across the whole library, unfiltered by show (ROADMAP Task 13 Phase
     * C). Backs [com.hub.media.features.portability.domain.ExportDataUseCase], which needs every
     * episode for `episodes_export.csv` the same way [observeAllMediaWithDetails] needs every media
     * item -- there is no per-show id to scope the read to, unlike
     * [com.hub.media.features.tv.data.TVShowRepository.observeEpisodes].
     */
    public fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = db.episodeDao().observeAll()

    /**
     * Observes every media item together with its details as a reactive stream.
     */
    public fun observeAllMediaWithDetails(): Flow<List<MediaWithDetails>> =
        combine(
            observeAllMedia(),
            db.bookDetailsDao().observeAll(),
            db.movieDetailsDao().observeAll(),
            db.tvDetailsDao().observeAll(),
        ) { mediaItems, bookDetails, movieDetails, tvDetails ->
            val bookDetailsByMediaId = bookDetails.associateBy { it.mediaId }
            val movieDetailsByMediaId = movieDetails.associateBy { it.mediaId }
            val tvDetailsByMediaId = tvDetails.associateBy { it.mediaId }
            mediaItems.map { mediaItem ->
                when (mediaItem.type) {
                    MediaType.BOOK ->
                        MediaWithDetails.Book(
                            item = mediaItem,
                            details = bookDetailsByMediaId[mediaItem.id],
                        )
                    MediaType.MOVIE ->
                        MediaWithDetails.Movie(
                            item = mediaItem,
                            details = movieDetailsByMediaId[mediaItem.id],
                        )
                    MediaType.TV_SHOW ->
                        MediaWithDetails.TVShow(
                            item = mediaItem,
                            details = tvDetailsByMediaId[mediaItem.id],
                        )
                }
            }
        }

    /**
     * Deletes a media item and all associated data (cascades via FK constraints).
     *
     * @param id The media ID of the item to delete.
     * @return [Resource.Success] if deleted, or [Resource.Error] on failure.
     */
    public suspend fun deleteMediaItem(id: String): Resource<Unit> =
        try {
            db.mediaItemDao().deleteById(id)
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to delete media item: id=$id" }
            Resource.Error(
                message = "Failed to delete item: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }

    /**
     * Updates only [MediaItemEntity.coverImageHash] for [mediaId].
     *
     * @param mediaId The item whose cover is being updated.
     * @param coverImageHash The new `<sha256>.jpg` filename.
     * @return [Resource.Success] if updated, or [Resource.Error] if [mediaId] does not resolve to
     *   an existing item or the underlying DB write throws.
     */
    public suspend fun updateCoverImageHash(
        mediaId: String,
        coverImageHash: String,
    ): Resource<Unit> =
        try {
            val rowsAffected = db.mediaItemDao().updateCoverImageHash(mediaId, coverImageHash)
            if (rowsAffected == 0) {
                Resource.Error("Media item with id=$mediaId not found")
            } else {
                Resource.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update cover hash for item: id=$mediaId" }
            Resource.Error(
                message = "Failed to update cover image: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }
}
