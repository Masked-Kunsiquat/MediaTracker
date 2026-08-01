package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalIdentifierDao {

    /** Composite PK of (mediaId, provider) — replaces any existing mapping for that pair. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identifier: ExternalIdentifierEntity)

    @Delete
    suspend fun delete(identifier: ExternalIdentifierEntity)

    @Query("DELETE FROM external_identifiers WHERE mediaId = :mediaId AND provider = :provider")
    suspend fun deleteByKey(mediaId: String, provider: IdentifierProvider)

    @Query("SELECT * FROM external_identifiers WHERE mediaId = :mediaId AND provider = :provider")
    suspend fun getByKey(mediaId: String, provider: IdentifierProvider): ExternalIdentifierEntity?

    @Query("SELECT * FROM external_identifiers WHERE mediaId = :mediaId")
    fun observeForMedia(mediaId: String): Flow<List<ExternalIdentifierEntity>>

    @Query("SELECT * FROM external_identifiers")
    fun observeAll(): Flow<List<ExternalIdentifierEntity>>
}
