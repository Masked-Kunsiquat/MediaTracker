package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.changelog.parseChangelog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the in-app changelog viewer (ROADMAP Task 15 Phase B2b).
 *
 * ### Why the content arrives as a lambda
 * The changelog ships as an Android asset (copied from the repo root at build time -- see
 * `app/build.gradle.kts`), and `context.assets` is a platform API `shared/` cannot touch. Rather
 * than push this whole ViewModel into the app module, the app passes a suspending reader in. That
 * keeps the parsing, the fold state, and the "open on the current version" rule testable in
 * `commonTest` with no Android dependency and no asset plumbing -- which matters here, because this
 * project has no Compose UI test harness (see ROADMAP's tech-debt entry), so anything left in the
 * app module is effectively untested.
 *
 * @param currentVersion The running app's `versionName`, used to pick which version opens expanded.
 * @param readChangelog Returns the raw changelog Markdown, or `null` if it cannot be read.
 */
public class ChangelogViewModel(
    private val currentVersion: String,
    private val readChangelog: suspend () -> String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChangelogUiState())
    public val uiState: StateFlow<ChangelogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val raw = readChangelog()
            if (raw == null) {
                _uiState.value = ChangelogUiState(isLoading = false, failedToLoad = true)
                return@launch
            }
            val document = parseChangelog(raw)
            _uiState.value =
                ChangelogUiState(
                    document = document,
                    expandedVersions = initiallyExpanded(document.versions.map { it.version }),
                    isLoading = false,
                )
        }
    }

    /**
     * Which version opens expanded: the running [currentVersion] if it appears in the changelog,
     * otherwise the newest entry.
     *
     * The fallback is not defensive padding -- it is the *normal* case during development. A debug
     * build's `versionName` is whatever `[versions] app` currently says, which is typically a
     * release that has not been written up yet, so its section genuinely does not exist. Opening
     * everything collapsed there would make the screen look broken; opening the newest section
     * shows the `[Unreleased]` notes, which is what someone on an unreleased build wants anyway.
     */
    private fun initiallyExpanded(versions: List<String>): Set<String> =
        when {
            versions.isEmpty() -> emptySet()
            currentVersion in versions -> setOf(currentVersion)
            else -> setOf(versions.first())
        }

    /** Toggles one version's expanded state. */
    public fun toggleVersion(version: String) {
        _uiState.update { it.copy(expandedVersions = it.expandedVersions.toggle(version)) }
    }

    /** Toggles one entry's expanded state; [key] comes from [entryKey]. */
    public fun toggleEntry(key: String) {
        _uiState.update { it.copy(expandedEntries = it.expandedEntries.toggle(key)) }
    }

    private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
}
