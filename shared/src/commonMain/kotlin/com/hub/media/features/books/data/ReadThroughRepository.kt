package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.guardResource
import com.hub.media.core.util.newId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

/**
 * A book's reads (#230): starting a re-read, removing a mistaken one, and rating a read. The status
 * of the latest read is changed through [BookRepository.updateReadingStatus], the one status path.
 */
public class ReadThroughRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val logger: Logger = AppLogger,
) {
    public fun observeReads(mediaId: String): Flow<List<ReadThroughEntity>> =
        db.readThroughDao().observeForMedia(mediaId)

    /**
     * Starts the next read at Reading, only after the latest read is Finished or Did not finish. Earlier
     * reads keep their dates and ratings. A double tap's second insert hits the unique (mediaId, number)
     * index and throws, which [guardResource] turns into an error, so exactly one read is created.
     *
     * @return the new read's id.
     */
    public suspend fun readAgain(mediaId: String): Resource<String> =
        logger.guardResource(
            tag = TAG,
            failure = "Couldn't start a re-read",
            logMessage = { "Failed to start a re-read: mediaId=$mediaId" },
        ) {
            val latest =
                db.readThroughDao().getLatest(mediaId)
                    ?: return Resource.Error("No read-through found for id=$mediaId")
            if (latest.status != ReadingStatus.FINISHED && latest.status != ReadingStatus.DNF) {
                return Resource.Error("Finish or stop the current read before reading again")
            }
            val id = newId()
            db.readThroughDao().insert(
                ReadThroughEntity(
                    id = id,
                    mediaId = mediaId,
                    number = latest.number + 1,
                    status = ReadingStatus.READING,
                    startedAt = clock.now(),
                ),
            )
            Resource.Success(id)
        }

    /**
     * Removes a re-read that has no sessions: the undo for a mistaken Read again, which returns the
     * book to its previous read's state. Read #1 and a read with sessions are never removed.
     */
    public suspend fun removeRead(readId: String): Resource<Unit> =
        logger.guardResource(
            tag = TAG,
            failure = "Couldn't remove the read",
            logMessage = { "Failed to remove a read: readId=$readId" },
        ) {
            val read = db.readThroughDao().getById(readId) ?: return Resource.Error("Read not found")
            if (read.number == 1) return Resource.Error("The first read can't be removed")
            if (db.readThroughDao().sessionCount(readId) > 0) return Resource.Error("This read has sessions")
            db.readThroughDao().deleteById(readId)
            Resource.Success(Unit)
        }

    /** Sets [readId]'s rating in half-steps, or clears it with `null`. Off the 1..10 grid is refused. */
    public suspend fun setRating(
        readId: String,
        halves: Int?,
    ): Resource<Unit> {
        if (halves != null && !isValidRating(halves)) return Resource.Error("A rating is half a star to five stars")
        return logger.guardResource(
            tag = TAG,
            failure = "Couldn't save the rating",
            logMessage = { "Failed to save a rating: readId=$readId" },
        ) {
            if (db.readThroughDao().updateRating(readId, halves) == 0) {
                Resource.Error("Read not found")
            } else {
                Resource.Success(Unit)
            }
        }
    }

    private companion object {
        const val TAG = "ReadThroughRepository"
    }
}
