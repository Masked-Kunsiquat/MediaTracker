package com.hub.media.core.database

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.util.newId
import kotlin.time.Instant

internal fun sampleMediaItem(
    id: String = newId(),
    title: String = "Test Book",
    type: MediaType = MediaType.BOOK,
    releaseYear: Int? = 2020,
    purchasePrice: Double? = 9.99,
    createdAt: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
): MediaItemEntity = MediaItemEntity(
    id = id,
    type = type,
    title = title,
    releaseYear = releaseYear,
    purchasePrice = purchasePrice,
    createdAt = createdAt,
)

internal fun sampleBookDetails(
    mediaId: String,
    isbn: String? = "9780000000000",
    format: BookFormat = BookFormat.PHYSICAL,
    totalPages: Int? = 300,
): BookDetailsEntity = BookDetailsEntity(
    mediaId = mediaId,
    isbn = isbn,
    format = format,
    totalPages = totalPages,
)

internal fun sampleExternalIdentifier(
    mediaId: String,
    provider: IdentifierProvider = IdentifierProvider.ISBN,
    externalId: String = "9780000000000",
): ExternalIdentifierEntity = ExternalIdentifierEntity(
    mediaId = mediaId,
    provider = provider,
    externalId = externalId,
)

internal fun sampleReadingSession(
    mediaId: String,
    id: String = newId(),
    timestampStart: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    timestampEnd: Instant = Instant.fromEpochMilliseconds(1_700_000_600_000),
    durationSeconds: Long = 600,
    startUnit: Double = 10.0,
    endUnit: Double = 25.0,
    deltaPages: Int? = 15,
    notes: String? = null,
): ReadingSessionEntity = ReadingSessionEntity(
    id = id,
    mediaId = mediaId,
    timestampStart = timestampStart,
    timestampEnd = timestampEnd,
    durationSeconds = durationSeconds,
    startUnit = startUnit,
    endUnit = endUnit,
    deltaPages = deltaPages,
    notes = notes,
)
