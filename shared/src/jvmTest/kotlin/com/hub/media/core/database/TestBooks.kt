package com.hub.media.core.database

import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.newId
import kotlin.time.Instant

/**
 * A book with its read #1, the way every write path creates one since #230. Tests that seeded a
 * book's status on `book_details` seed it here instead. Returns read #1's id.
 */
internal suspend fun AppDatabase.insertTestBook(
    mediaId: String,
    status: ReadingStatus = ReadingStatus.TO_READ,
    finishedAt: Instant? = null,
    rating: Int? = null,
    totalPages: Int? = 300,
    title: String = "Book $mediaId",
): String {
    mediaItemDao().insert(sampleMediaItem(id = mediaId, title = title))
    bookDetailsDao().insert(
        sampleBookDetails(mediaId = mediaId, totalPages = totalPages, status = status, finishedAt = finishedAt),
    )
    val readId = newId()
    readThroughDao().insert(
        ReadThroughEntity(
            id = readId,
            mediaId = mediaId,
            number = 1,
            status = status,
            finishedAt = finishedAt,
            rating = rating,
        ),
    )
    return readId
}
