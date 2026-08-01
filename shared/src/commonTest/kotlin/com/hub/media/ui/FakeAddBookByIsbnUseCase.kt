package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookIngestionUseCase
import kotlinx.coroutines.CompletableDeferred

/**
 * Hand-rolled fake [BookIngestionUseCase] for [AddBookViewModel] tests (AGENTS.md §5 "No
 * Unnecessary Dependencies" — no mocking library). Records how many times [execute] was invoked
 * and, when [awaitGate] is set, suspends until [release] is called so tests can observe
 * intermediate [AddBookUiState.Loading] states deterministically.
 */
internal class FakeAddBookByIsbnUseCase(
    private val result: Resource<String> = Resource.Success("fake-media-id"),
) : BookIngestionUseCase {

    /** Number of times [execute] has been called. */
    var callCount: Int = 0
        private set

    /** When true, [execute] suspends on an internal gate until [release] is called. */
    var awaitGate: Boolean = false

    private val gate = CompletableDeferred<Unit>()

    override suspend fun execute(isbn: String): Resource<String> {
        callCount++
        if (awaitGate) gate.await()
        return result
    }

    /** Releases a pending [execute] call started while [awaitGate] was true. */
    fun release() {
        gate.complete(Unit)
    }
}
