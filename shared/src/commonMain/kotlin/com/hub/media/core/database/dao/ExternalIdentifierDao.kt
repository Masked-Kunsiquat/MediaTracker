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

    /**
     * Every mediaId that already has an identifier for [provider], for a bulk scan that needs to
     * know which books are *missing* one (ROADMAP Task 14 Phase A's candidate seed). One query for
     * the whole library rather than [getByKey] per book — the scan already reads every book once
     * and a second per-book round trip would make seeding O(library) queries for no gain.
     */
    @Query("SELECT mediaId FROM external_identifiers WHERE provider = :provider")
    suspend fun getMediaIdsForProvider(provider: IdentifierProvider): List<String>

    @Query("SELECT * FROM external_identifiers WHERE mediaId = :mediaId")
    fun observeForMedia(mediaId: String): Flow<List<ExternalIdentifierEntity>>

    @Query("SELECT * FROM external_identifiers")
    fun observeAll(): Flow<List<ExternalIdentifierEntity>>
}
