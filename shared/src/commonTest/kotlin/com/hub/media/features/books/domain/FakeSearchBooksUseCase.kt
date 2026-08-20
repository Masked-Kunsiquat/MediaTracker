package com.hub.media.features.books.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.media.network.MediaSearchResult
import kotlinx.coroutines.delay

/**
 * Hand-rolled fake [SearchBooksUseCase] for ViewModel tests (AGENTS.md §5 "No Unnecessary
 * Dependencies" — no mocking library). Records how many times [execute] was called and returns
 * a configurable result.
 *
 * Supports optional delay to simulate network latency and test debounce/cancellation behavior.
 * For tests that care about the min-length check without implementing the full normalization,
 * [minLengthIsEnough] can be toggled per instance.
 */
internal class FakeSearchBooksUseCase(
    private val results: List<MediaSearchResult> = emptyList(),
    private val error: Resource.Error? = null,
    private val delay: Long = 0,
    private val minLengthIsEnough: Boolean = true,
) : SearchBooksUseCase {
    var executeCallCount: Int = 0
        private set

    override suspend fun execute(query: String): Resource<List<MediaSearchResult>> {
        executeCallCount++
        if (delay > 0) {
            delay(delay)
        }
        return error ?: Resource.Success(results)
    }

    override suspend fun searchBooks(query: String): Resource<List<MediaSearchResult>> = execute(query)

    override fun isQueryLongEnough(query: String): Boolean = minLengthIsEnough
}
