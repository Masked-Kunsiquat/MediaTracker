package com.hub.media.ui

import com.hub.media.core.database.dao.AppSettingsDao
import com.hub.media.core.database.entities.AppSettingEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-rolled in-memory fake [AppSettingsDao] (AGENTS.md §5 "No Unnecessary Dependencies" -- no
 * Room, no mocking library) so [RestoreViewModel] tests can exercise a real
 * [com.hub.media.features.settings.data.SettingsRepository] without a Room database, mirroring how
 * [FakeExportDataUseCase]/[FakeImportDataUseCase] stand in for their own real dependencies.
 *
 * A generic per-key map, not narrowed to the Google Books API key row specifically -- that matches
 * what the real Room-backed `app_settings` table actually is (AGENTS.md §5: fakes should behave
 * like the thing they replace, not like the one test that happens to use them).
 */
internal class FakeAppSettingsDao : AppSettingsDao {
    private val rows = mutableMapOf<String, MutableStateFlow<AppSettingEntity?>>()

    private fun flowFor(key: String) = rows.getOrPut(key) { MutableStateFlow(null) }

    override suspend fun upsert(setting: AppSettingEntity) {
        flowFor(setting.key).value = setting
    }

    override suspend fun getByKey(key: String): AppSettingEntity? = flowFor(key).value

    override fun observeByKey(key: String) = flowFor(key).asStateFlow()

    override suspend fun deleteByKey(key: String) {
        flowFor(key).value = null
    }

    override suspend fun deleteAll() {
        rows.values.forEach { it.value = null }
        rows.clear()
    }
}
