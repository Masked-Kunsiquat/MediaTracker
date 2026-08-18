package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.RestoreDatabaseUseCase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getGoogleBooksApiKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the **non-destructive half only** of the Settings screen's `.sqlite` restore action
 * (ROADMAP Task 8 Phase C): validating and staging a user-picked file. Deliberately does *not*
 * expose the destructive swap step ([RestoreDatabaseUseCase.commit]) as a method here, unlike
 * [ExportViewModel]/[ImportViewModel]/[BackupViewModel]'s single-action shape:
 *
 * Committing a restore requires, in exact order, (1) the app layer closing
 * [com.hub.media.ui.AppContainer] -- the very container this ViewModel's [restoreDatabaseUseCase]
 * dependency was itself wired from -- and (2) a full process restart immediately afterward (see
 * [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase]'s class KDoc for why
 * both are necessary). Neither of those steps can happen from inside a [ViewModel]: closing the
 * container out from under a `viewModelScope.launch { }` coroutine that is itself backed by that
 * same container is exactly the "half-live container" shape AGENTS.md §1 warns against, and process
 * restart needs a platform `Context`/`Activity`, which no shared-module class holds. So the route
 * composable calls [RestoreDatabaseUseCase.commit] directly (via `AppContainer.restoreDatabaseUseCase`)
 * as the very last step of its own confirm handler, in the same breath as closing the container and
 * triggering the restart -- deliberately outside this ViewModel's lifecycle, which is about to be
 * torn down anyway.
 *
 * @param restoreDatabaseUseCase Validates/stages a candidate file. Typed as the narrow
 *   [RestoreDatabaseUseCase] interface so tests can hand-roll a fake with no Room dependency
 *   (AGENTS.md §5).
 * @param settingsRepository Read-only source for [RestoreUiState.AwaitingConfirmation.apiKeyWillBeCleared]
 *   -- only ever asked whether a Google Books API key is currently set
 *   ([com.hub.media.features.settings.data.getGoogleBooksApiKey] returning non-null), never asked for
 *   the key's value. Taken as the same concrete [SettingsRepository] type
 *   [com.hub.media.ui.SettingsViewModel]/[com.hub.media.ui.StatsViewModel] already depend on, rather
 *   than a narrower interface, to match their precedent.
 */
public class RestoreViewModel(
    private val restoreDatabaseUseCase: RestoreDatabaseUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    public val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    /**
     * Validates/stages [incomingFilePath] (the app layer's own private copy of whatever the user
     * picked via SAF -- see [RestoreDatabaseUseCase.stage]'s KDoc for why staging happens before
     * this is even called). If a validation is already in flight ([RestoreUiState.Validating]),
     * this call is silently ignored.
     */
    public fun validateSelectedFile(incomingFilePath: String) {
        if (_uiState.value is RestoreUiState.Validating) return

        _uiState.value = RestoreUiState.Validating
        viewModelScope.launch {
            // Read now, while the live database still holds whatever key it holds -- see
            // RestoreUiState.AwaitingConfirmation.apiKeyWillBeCleared's KDoc for why this can't
            // be deferred to any later point in the restore flow. Presence only; the key's
            // value is never read here or held by this ViewModel.
            val keyCheckResult: Resource<Boolean> =
                try {
                    Resource.Success(settingsRepository.getGoogleBooksApiKey() != null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Resource.Error("Failed to check existing API key")
                }

            _uiState.value =
                when (keyCheckResult) {
                    is Resource.Success -> {
                        when (val result = restoreDatabaseUseCase.stage(incomingFilePath)) {
                            is Resource.Success -> {
                                RestoreUiState.AwaitingConfirmation(
                                    result.data,
                                    apiKeyWillBeCleared = keyCheckResult.data,
                                )
                            }
                            is Resource.Error -> RestoreUiState.Error(result.message)
                        }
                    }
                    is Resource.Error -> RestoreUiState.Error(keyCheckResult.message)
                }
        }
    }

    /** Resets state back to [RestoreUiState.Idle], e.g. after the user cancels or dismisses an error. */
    public fun reset() {
        _uiState.value = RestoreUiState.Idle
    }
}
