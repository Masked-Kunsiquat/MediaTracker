package com.hub.media.features.books.domain

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.media.network.MediaSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchBooksUseCaseTest {
    /**
     * Records every query it is handed, which is what most of these tests actually assert on:
     * the point of this class is *which requests never happen*.
     */
    private class RecordingProvider(
        private val response: (String) -> Resource<List<MediaSearchResult>> = {
            Resource.Success(listOf(hit("The Hobbit")))
        },
    ) : BookSearchProvider {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()

        override suspend fun searchByTitleOrAuthor(
            query: String,
            limit: Int,
        ): Resource<List<MediaSearchResult>> {
            queries.add(query)
            limits.add(limit)
            return response(query)
        }

        override suspend fun resolveEditionToIsbn(editionKey: String): Resource<String?> = Resource.Success(null)

        override suspend fun fetchEditionsForWork(workKey: String): Resource<List<BookEditionSearchResult>> =
            Resource.Success(emptyList())
    }

    private companion object {
        fun hit(title: String) =
            MediaSearchResult(
                title = title,
                type = MediaType.BOOK,
                provider = IdentifierProvider.OPEN_LIBRARY,
            )
    }

    @Test
    fun query_shorterThanTheMinimum_neverReachesTheNetwork() =
        runTest {
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            val result = useCase.execute("ho")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(emptyList(), (result as Resource.Success).data)
            assertEquals(emptyList(), provider.queries, "a too-short query must spend no budget")
        }

    @Test
    fun whitespaceOnlyPadding_doesNotFakeUpTheMinimumLength() =
        runTest {
            // "  h  " is 5 characters but one letter. Counting before trimming would let a single
            // keystroke plus stray spaces trigger a catalogue-wide search.
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            useCase.execute("  h  ")

            assertEquals(emptyList(), provider.queries)
        }

    @Test
    fun query_atTheMinimum_isSearched() =
        runTest {
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            useCase.execute("hob")

            assertEquals(listOf("hob"), provider.queries)
        }

    @Test
    fun repeatedQuery_isServedFromCacheWithoutASecondRequest() =
        runTest {
            // The reason the cache exists: typing is not monotonic. A user types forward, backspaces,
            // and types forward again over the same prefixes constantly.
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            val first = useCase.execute("hobbit")
            val second = useCase.execute("hobbit")

            assertEquals(listOf("hobbit"), provider.queries, "expected exactly one network call")
            assertEquals(
                (first as Resource.Success).data,
                (second as Resource.Success).data,
                "cached result must match the live one",
            )
        }

    @Test
    fun queriesDifferingOnlyInCaseOrSpacing_shareOneRequest() =
        runTest {
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            useCase.execute("The Hobbit")
            useCase.execute("the hobbit")
            useCase.execute("  the   hobbit  ")

            assertEquals(listOf("the hobbit"), provider.queries)
        }

    @Test
    fun errors_areNotCached() =
        runTest {
            // A transient offline blip must not pin itself to a query for the life of the process --
            // otherwise the user has to change what they typed to retry something already working.
            var attempt = 0
            val provider =
                RecordingProvider { query ->
                    attempt++
                    if (attempt == 1) Resource.Error("offline") else Resource.Success(listOf(hit(query)))
                }
            val useCase = RealSearchBooksUseCase(provider)

            val failed = useCase.execute("hobbit")
            val retried = useCase.execute("hobbit")

            assertTrue(failed is Resource.Error, "expected Error, got $failed")
            assertTrue(retried is Resource.Success, "a retry must actually retry, got $retried")
            assertEquals(listOf("hobbit", "hobbit"), provider.queries)
        }

    @Test
    fun emptyResults_areCached() =
        runTest {
            // "Found nothing" is a real, correct answer. Re-asking the provider for it on every
            // keystroke would spend the budget the cache exists to protect.
            val provider = RecordingProvider { Resource.Success(emptyList()) }
            val useCase = RealSearchBooksUseCase(provider)

            useCase.execute("zzzzqqqq")
            useCase.execute("zzzzqqqq")

            assertEquals(listOf("zzzzqqqq"), provider.queries)
        }

    @Test
    fun leastRecentlyUsedQuery_isEvictedFirst() =
        runTest {
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider, cacheSize = 2)

            useCase.execute("aaa")
            useCase.execute("bbb")
            useCase.execute("aaa") // refreshes "aaa", making "bbb" the oldest
            useCase.execute("ccc") // evicts "bbb"
            useCase.execute("aaa") // still cached
            useCase.execute("bbb") // evicted, so this re-requests

            assertEquals(listOf("aaa", "bbb", "ccc", "bbb"), provider.queries)
        }

    @Test
    fun cancellation_propagatesRatherThanBecomingAnError() =
        runTest {
            val provider = RecordingProvider { throw CancellationException("superseded") }
            val useCase = RealSearchBooksUseCase(provider)

            assertFailsWith<CancellationException> { useCase.execute("hobbit") }
        }

    @Test
    fun cancelledSearch_isNotCached() =
        runTest {
            // A cancelled search produced no answer, so caching anything for it would serve a result
            // the provider never actually returned.
            var cancelFirst = true
            val provider =
                RecordingProvider { query ->
                    if (cancelFirst) {
                        cancelFirst = false
                        throw CancellationException("superseded")
                    }
                    Resource.Success(listOf(hit(query)))
                }
            val useCase = RealSearchBooksUseCase(provider)

            assertFailsWith<CancellationException> { useCase.execute("hobbit") }
            val retried = useCase.execute("hobbit")

            assertTrue(retried is Resource.Success, "expected the retry to reach the provider")
            assertEquals(listOf("hobbit", "hobbit"), provider.queries)
        }

    @Test
    fun limit_isForwardedToTheProvider() =
        runTest {
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider, limit = 5)

            useCase.execute("hobbit")

            assertEquals(listOf(5), provider.limits)
        }

    @Test
    fun isQueryLongEnough_matchesWhatExecuteActuallyDoes() =
        runTest {
            // Exposed so the UI can say "keep typing" instead of "no matches" without re-implementing
            // normalization -- and it is only useful if it cannot drift from execute().
            val provider = RecordingProvider()
            val useCase = RealSearchBooksUseCase(provider)

            listOf("", "  ", "h", "ho", "  ho  ").forEach { query ->
                assertFalse(useCase.isQueryLongEnough(query), "expected too short: '$query'")
                useCase.execute(query)
            }
            assertEquals(emptyList(), provider.queries, "execute must agree with isQueryLongEnough")

            listOf("hob", "  the hobbit  ").forEach { query ->
                assertTrue(useCase.isQueryLongEnough(query), "expected long enough: '$query'")
            }
        }
}
