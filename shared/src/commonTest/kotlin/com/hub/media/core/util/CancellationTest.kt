package com.hub.media.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {
    private class FatalTestError : Error("fatal")

    // --- catchingNonCancellation ---

    @Test
    fun catchingNonCancellation_returnsBlockResult_whenNothingThrows() {
        var handlerCalls = 0
        val result =
            catchingNonCancellation(block = { 42 }, onException = {
                handlerCalls++
                -1
            })
        assertEquals(42, result)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_rethrowsCancellation_withoutCallingHandler() {
        val cancellation = CancellationException("screen closed")
        var handlerCalls = 0
        val thrown =
            assertFailsWith<CancellationException> {
                catchingNonCancellation(block = { throw cancellation }, onException = { handlerCalls++ })
            }
        assertSame(cancellation, thrown)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_passesOrdinaryException_toHandler() {
        val failure = IllegalStateException("disk full")
        val result = catchingNonCancellation<Any>(block = { throw failure }, onException = { it })
        assertSame(failure, result)
    }

    @Test
    fun catchingNonCancellation_letsErrorsPropagate() {
        var handlerCalls = 0
        assertFailsWith<FatalTestError> {
            catchingNonCancellation(block = { throw FatalTestError() }, onException = { handlerCalls++ })
        }
        assertEquals(0, handlerCalls)
    }

    @Test
    fun catchingNonCancellation_allowsNonLocalReturnFromBlock() {
        fun earlyExit(): String {
            catchingNonCancellation(block = { return "returned early" }, onException = { "handled" })
            return "fell through"
        }
        assertEquals("returned early", earlyExit())
    }

    @Test
    fun catchingNonCancellation_allowsNonLocalContinueFromHandler() {
        val visited = mutableListOf<Int>()
        for (i in 1..3) {
            catchingNonCancellation(
                block = { if (i == 2) error("skip $i") },
                onException = { continue },
            )
            visited += i
        }
        assertEquals(listOf(1, 3), visited)
    }

    @Test
    fun catchingNonCancellation_blockMaySuspend() =
        runTest {
            val result =
                catchingNonCancellation(block = {
                    delay(1)
                    "after delay"
                }, onException = { "handled" })
            assertEquals("after delay", result)
        }

    @Test
    fun catchingNonCancellation_propagatesRealJobCancellation() =
        runTest {
            var handled = false
            var reachedBlock = false
            val job =
                launch {
                    catchingNonCancellation(
                        block = {
                            reachedBlock = true
                            awaitCancellation()
                        },
                        onException = { handled = true },
                    )
                }
            runCurrent()
            assertTrue(reachedBlock, "positive control: the block must actually be suspended when cancelled")
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(handled)
        }

    // --- guardResource ---

    @Test
    fun guardResource_passesSuccessThrough_withoutLogging() {
        val logger = RecordingLogger()
        val result =
            logger.guardResource(tag = "Tag", failure = "Failed to save", logMessage = { "unused" }) {
                Resource.Success(7)
            }
        assertEquals(Resource.Success(7), result)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun guardResource_passesBlocksOwnError_throughWithoutLogging() {
        val logger = RecordingLogger()
        val result =
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "unused" }) {
                Resource.Error("Item with id=1 not found")
            }
        assertEquals(Resource.Error("Item with id=1 not found"), result)
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun guardResource_wrapsException_inPrefixedMessage_withCause_andOneErrorLog() {
        val logger = RecordingLogger()
        val failure = IllegalStateException("disk full")
        val result =
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "save failed: id=1" }) {
                throw failure
            }
        val error = assertIs<Resource.Error>(result)
        assertEquals("Failed to save: disk full", error.message)
        assertSame(failure, error.cause)
        assertEquals(
            listOf(RecordingLogger.Entry(LogLevel.ERROR, "Tag", "save failed: id=1", failure)),
            logger.entries,
        )
    }

    @Test
    fun guardResource_usesUnknownError_whenExceptionHasNoMessage() {
        val logger = RecordingLogger()
        val result =
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "x" }) {
                throw IllegalStateException()
            }
        assertEquals("Failed to save: Unknown error", assertIs<Resource.Error>(result).message)
    }

    @Test
    fun guardResource_rethrowsCancellation_andLogsNothing() {
        val logger = RecordingLogger()
        assertFailsWith<CancellationException> {
            logger.guardResource<Int>(tag = "Tag", failure = "Failed to save", logMessage = { "x" }) {
                throw CancellationException("screen closed")
            }
        }
        assertTrue(logger.entries.isEmpty())
    }
}
