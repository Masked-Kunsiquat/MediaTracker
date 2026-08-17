package com.hub.media.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Demonstrates the authoritative patterns for asserting final state in MediaTracker ViewModel tests.
 * These patterns prevent flakiness caused by the race between ViewModel actions and the 
 * combine -> stateIn pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncAssertionPatternExample {

    /**
     * Pattern 1: Using .first { condition } to await a specific state transition.
     * This is the preferred method for terminal states or state changes.
     */
    @Test
    fun saveSession_persistsSessionAndClearsPending() = runTest {
        // ... setup (insert book, start/stop reading) ...
        val viewModel = newViewModel()
        
        // 1. Initial await to ensure the screen is ready
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        // 2. Perform the action
        viewModel.saveSession(startUnit = 10.0, endUnit = 42.0)

        // 3. CRITICAL: Await the specific change. 
        // We don't just check 'is Ready', because it was ALREADY Ready.
        // We check for the side effect of the save (sessions list populated).
        val finalState = viewModel.uiState.first { 
            it is BookDetailUiState.Ready && it.sessions.isNotEmpty() 
        } as BookDetailUiState.Ready

        // 4. Now safe to assert values
        assertNull(finalState.pendingSession)
        assertEquals(1, finalState.sessions.size)
        assertEquals(42.0, finalState.sessions.first().endUnit)
    }

    /**
     * Pattern 2: Bridging Virtual and Real Time with runCurrentUntilOrTimeOut.
     * Use this when waiting for background work (like Room invalidations) that 
     * doesn't dispatch back to the virtual scheduler immediately.
     */
    @Test
    fun stopReading_producesPendingSession() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        viewModel.stopReading()

        // Room invalidations often need a real-time yield to propagate.
        // runCurrentUntilOrTimeOut is a project-specific helper (see BookDetailViewModelTest.kt).
        runCurrentUntilOrTimeOut {
            (viewModel.uiState.value as? BookDetailUiState.Ready)?.pendingSession != null
        }
        
        val ready = viewModel.uiState.value as BookDetailUiState.Ready
        // Assertion is now safe because the condition above held.
    }
}
