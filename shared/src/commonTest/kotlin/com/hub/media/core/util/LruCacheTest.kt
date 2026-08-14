package com.hub.media.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class LruCacheTest {

    @Test
    fun storesAndReturnsValues() = runTest {
        val cache = LruCache<String, Int>(maxSize = 4)

        cache.put("a", 1)

        assertEquals(1, cache.get("a"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun evictsTheOldestEntryOnceFull() = runTest {
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        assertNull(cache.get("a"), "oldest entry should have been evicted")
        assertEquals(2, cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun readingAnEntryMakesItSurviveTheNextEviction() = runTest {
        // The "recently used" half of LRU, and the half that a plain insertion-ordered map would
        // get wrong: "a" is oldest by insertion but newest by use.
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)

        assertEquals(1, cache.get("a"), "a was just read and should have survived")
        assertNull(cache.get("b"), "b was least recently used")
    }

    @Test
    fun overwritingAKeyRefreshesItsRecency() = runTest {
        val cache = LruCache<String, Int>(maxSize = 2)

        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("a", 99)
        cache.put("c", 3)

        assertEquals(99, cache.get("a"), "the overwrite should have moved a to newest")
        assertNull(cache.get("b"))
    }

    @Test
    fun zeroOrNegativeMaxSize_isClampedToAUsableCache() = runTest {
        // A literal zero-size cache evicts every entry the moment it is inserted -- a silently
        // useless cache rather than an obviously broken one, which is worse.
        val cache = LruCache<String, Int>(maxSize = 0)

        cache.put("a", 1)

        assertEquals(1, cache.get("a"))
        assertEquals(1, cache.size())
    }

    @Test
    fun clear_dropsEverything() = runTest {
        val cache = LruCache<String, Int>(maxSize = 4)
        cache.put("a", 1)
        cache.put("b", 2)

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun concurrentWrites_allLandAndRespectTheBound() = runTest {
        // The motivating caller is a type-ahead with several searches genuinely in flight, so
        // concurrent access is the normal case rather than a rare race.
        val cache = LruCache<Int, Int>(maxSize = 50)

        (1..100).map { n -> async { cache.put(n, n) } }.awaitAll()

        assertEquals(50, cache.size(), "the bound must hold under concurrent writes")
    }
}
