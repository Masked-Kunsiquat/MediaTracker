package com.hub.media.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests [immutableFileUri], the `file:` URI a staged restore candidate is opened through (#210).
 * Pure string work; `RestoreDatabaseUseCaseTest` (`jvmTest`) opens real files through it.
 */
class ImmutableFileUriTest {
    @Test
    fun anAndroidPath_becomesAnImmutableFileUri() {
        assertEquals(
            "file:///data/user/0/app/cache/restore-incoming-1.tmp?immutable=1",
            immutableFileUri("/data/user/0/app/cache/restore-incoming-1.tmp"),
        )
    }

    @Test
    fun aWindowsPath_gainsForwardSlashesAndALeadingSlash() {
        assertEquals(
            "file:///C:/Users/me/Temp/candidate.db?immutable=1",
            immutableFileUri("C:\\Users\\me\\Temp\\candidate.db"),
        )
    }

    /** A relative path stays relative, naming the same file the header read opened (#242 review). */
    @Test
    fun aRelativePath_staysRelative() {
        assertEquals(
            "file:cache/restore-incoming-1.tmp?immutable=1",
            immutableFileUri("cache/restore-incoming-1.tmp"),
        )
    }

    @Test
    fun theCharactersSqliteReadsAsUriSyntax_areEscaped() {
        assertEquals(
            "file:///cache/a%3Fb%23c%25d.tmp?immutable=1",
            immutableFileUri("/cache/a?b#c%d.tmp"),
        )
    }
}
