package com.hub.media.features.books.domain

import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.FakeBookSearchProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveWorkToEditionsUseCaseTest {
    @Test
    fun execute_callsProviderAndReturnsResult() =
        runTest {
            val provider = FakeBookSearchProvider()
            val useCase = ResolveWorkToEditionsUseCase(provider)

            val result = useCase.execute("/works/OL1W")

            assertTrue(result is Resource.Success)
            assertEquals(emptyList(), (result as Resource.Success).data)
        }
}
