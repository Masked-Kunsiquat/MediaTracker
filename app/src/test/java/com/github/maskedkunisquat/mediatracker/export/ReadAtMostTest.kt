package com.github.maskedkunisquat.mediatracker.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The import dialog's bounded read (#196 final review): a picked file larger than the cap is
 * refused without being held in memory, rather than read whole and crashing the app.
 */
class ReadAtMostTest {
    @Test
    fun aFileWithinTheCap_isReadWhole() {
        val bytes = ByteArray(1_000) { it.toByte() }
        assertArrayEquals(bytes, readAtMost(ByteArrayInputStream(bytes), max = 1_000))
    }

    @Test
    fun aFileOverTheCap_isNull() {
        assertNull(readAtMost(ByteArrayInputStream(ByteArray(1_001)), max = 1_000))
    }
}
