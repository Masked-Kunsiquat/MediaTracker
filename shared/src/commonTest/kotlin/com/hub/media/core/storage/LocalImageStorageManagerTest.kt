package com.hub.media.core.storage

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalImageStorageManagerTest {
    private lateinit var tempDir: String
    private lateinit var manager: LocalImageStorageManager

    @BeforeTest
    fun setUp() =
        runTest {
            tempDir = createTestTempDir()
            manager = LocalImageStorageManager(tempDir)
        }

    @AfterTest
    fun tearDown() =
        runTest {
            cleanupTestTempDir(tempDir)
        }

    @Test
    fun knownVector_sha256Hash() =
        runTest {
            // Known SHA-256 vector: "hello world" -> b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
            val helloWorld = "hello world".encodeToByteArray()
            val result = manager.saveImage(helloWorld)

            assertTrue(result.isSuccess)
            assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9.jpg", result.getOrNull())
        }

    @Test
    fun fileIsWrittenToDisk() =
        runTest {
            val imageBytes = byteArrayOf((-1).toByte(), (-40).toByte(), (-1).toByte()) // JPEG magic
            val result = manager.saveImage(imageBytes)

            assertTrue(result.isSuccess)
            val filename = result.getOrNull()!!

            val file = File(tempDir, filename)
            assertTrue(file.exists())
            val readBytes = file.readBytes()
            assertEquals(imageBytes.toList(), readBytes.toList())
        }

    @Test
    fun deduplication_sameBytesTwice() =
        runTest {
            val imageBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()) // PNG magic
            val result1 = manager.saveImage(imageBytes)
            val result2 = manager.saveImage(imageBytes)

            assertTrue(result1.isSuccess)
            assertTrue(result2.isSuccess)
            assertEquals(result1.getOrNull(), result2.getOrNull())

            // Verify only one file exists
            val dir = File(tempDir)
            val files = dir.listFiles()
            assertEquals(1, files?.size, "Expected exactly 1 file in directory (deduplication)")
        }

    @Test
    fun differentBytes_differentFilenames() =
        runTest {
            val bytes1 = byteArrayOf(0x01, 0x02, 0x03)
            val bytes2 = byteArrayOf(0x04, 0x05, 0x06)

            val result1 = manager.saveImage(bytes1)
            val result2 = manager.saveImage(bytes2)

            assertTrue(result1.isSuccess)
            assertTrue(result2.isSuccess)

            val filename1 = result1.getOrNull()!!
            val filename2 = result2.getOrNull()!!
            assertFalse(filename1 == filename2, "Different bytes should produce different filenames")

            // Verify both files exist
            val dir = File(tempDir)
            val files = dir.listFiles()
            assertEquals(2, files?.size, "Expected 2 files in directory")
        }

    @Test
    fun emptyByteArray_returnsError() =
        runTest {
            val result = manager.saveImage(byteArrayOf())

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is IllegalArgumentException)
            assertTrue(exception?.message?.contains("cannot be empty") ?: false)
        }

    @Test
    fun baseDirectoryCreatedIfMissing() =
        runTest {
            val nonExistentDir = "$tempDir/nested/deep/storage"
            val managerWithNewDir = LocalImageStorageManager(nonExistentDir)

            val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val result = managerWithNewDir.saveImage(imageBytes)

            assertTrue(result.isSuccess)
            val file = File(nonExistentDir, result.getOrNull()!!)
            assertTrue(file.exists(), "File should exist in newly created directory")
        }
}
