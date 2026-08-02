package com.hub.media.core.database.converters

import androidx.room.TypeConverter
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import kotlin.time.Instant

/**
 * Room [TypeConverter]s for [AppDatabase][com.hub.media.core.database.AppDatabase].
 *
 * Enums are persisted as their `name` (String), never their ordinal, so reordering enum
 * constants can never silently corrupt stored data. Timestamps are persisted as epoch
 * milliseconds ([Long]), since SQLite has no native temporal type.
 */
object Converters {

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun mediaTypeToName(value: MediaType): String = value.name

    @TypeConverter
    fun nameToMediaType(value: String): MediaType = MediaType.valueOf(value)

    @TypeConverter
    fun bookFormatToName(value: BookFormat): String = value.name

    @TypeConverter
    fun nameToBookFormat(value: String): BookFormat = BookFormat.valueOf(value)

    @TypeConverter
    fun identifierProviderToName(value: IdentifierProvider): String = value.name

    @TypeConverter
    fun nameToIdentifierProvider(value: String): IdentifierProvider = IdentifierProvider.valueOf(value)

    @TypeConverter
    fun readingStatusToName(value: ReadingStatus): String = value.name

    @TypeConverter
    fun nameToReadingStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)

    @TypeConverter
    fun trackingModeToName(value: TrackingMode): String = value.name

    @TypeConverter
    fun nameToTrackingMode(value: String): TrackingMode = TrackingMode.valueOf(value)
}
