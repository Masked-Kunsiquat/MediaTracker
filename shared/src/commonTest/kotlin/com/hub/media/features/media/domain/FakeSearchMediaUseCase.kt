package com.hub.media.features.media.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.media.network.MediaSearchResult
import kotlinx.coroutines.delay

/**
 * Hand-rolled fake [SearchMediaUseCase] for ViewModel tests (AGENTS.md §5 "No Unnecessary
 * Dependencies" — no mocking library).
 *
 * @property results Successful hits to return.
 * @property error Failure to return.
 * @property delay Artificial delay to test debounce/cancellation.
 * @property minLengthIsEnough Whether to honor [isQueryLongEnough] (defaults to true).
 */
internal class FakeSearchMediaUseCase(
    private val results: List<MediaSearchResult> = emptyList(),
    private val error: Resource<List<MediaSearchResult>>? = null,
    private val delay: Long = 0,
    private val minLengthIsEnough: Boolean = true,
) : SearchMediaUseCase {
    var executeCallCount = 0
        private set

    override suspend fun execute(query: String): Resource<List<MediaSearchResult>> {
        executeCallCount++
        if (delay > 0) delay(delay)
        return error ?: Resource.Success(results)
    }

    override fun isQueryLongEnough(query: String): Boolean = minLengthIsEnough
}
