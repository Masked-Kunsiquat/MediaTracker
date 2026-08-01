package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Maps a [MediaItemEntity] to an identifier in an external catalog (ISBN, TMDB id, etc.),
 * per AGENTS.md §3.3. Composite primary key of (mediaId, provider) means a media item can
 * have at most one identifier per provider; re-inserting for the same provider replaces it.
 */
@Entity(
    tableName = "external_identifiers",
    primaryKeys = ["mediaId", "provider"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId"])],
)
data class ExternalIdentifierEntity(
    val mediaId: String,
    val provider: IdentifierProvider,
    val externalId: String,
)
