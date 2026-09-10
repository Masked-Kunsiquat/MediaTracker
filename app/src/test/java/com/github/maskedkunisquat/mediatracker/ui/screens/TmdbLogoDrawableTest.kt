package com.github.maskedkunisquat.mediatracker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the one defect in `tmdb_logo.xml` that **no rendering test can see** (#137).
 *
 * ## The bug this exists for
 *
 * TMDB's logo is one path made of many subpaths. In their published SVG, seven of those subpaths
 * open with a *relative* `m` immediately after a `Z`. The SVG spec is clear about what that means:
 * after a closepath, the current point returns to the **start of the subpath just closed**, so the
 * relative move is measured from there. Android's `PathParser` measures it from the last point
 * *drawn* instead, so all seven land in the wrong place.
 *
 * On a real device the wordmark rendered as **"THE M●V = DB"** -- the `I` vanished, the `E`
 * collapsed to an equals sign, and the `D` grew an artifact. Every mangled glyph was a subpath
 * beginning `Zm`; every correct one began with an absolute `M`.
 *
 * ## Why this is a source assertion rather than a golden
 *
 * **The golden renders the broken path correctly.** Roborazzi draws through Skia, which follows the
 * spec, so `about.png` was recorded from the same path data that was visibly wrong on the phone and
 * showed nothing amiss. That is not a gap in the golden lane to be fixed by recording more images:
 * the two renderers genuinely disagree, and the screenshot is taken by the one that is right. The
 * only place the defect is visible without a device is in the path data itself.
 *
 * So this asserts a property of the file. It is the cheap half of a bug whose expensive half was
 * found by hand on an SM-G975U1, and it exists because the obvious way to update this asset --
 * re-fetch their SVG and copy the `d` attribute across, which is what the file's own comment tells
 * you to do -- reintroduces it every time.
 *
 * ## What a failure means
 *
 * Not "the logo is wrong". It means the path data has gone back to relative subpath starts, which
 * *will* render wrong on Android and right everywhere you are likely to check. Re-run the absolute
 * conversion described in the drawable's comment rather than deleting this test.
 */
class TmdbLogoDrawableTest {
    @Test
    fun noSubpathStartsRelativeToAClosedSubpath() {
        val relativeStarts = Regex("[Zz]\\s*m").findAll(pathData).count()

        assertEquals(
            "tmdb_logo.xml has $relativeStarts subpath(s) starting with a relative 'm' after a " +
                "close. Android's PathParser resolves those against the wrong point and the " +
                "wordmark renders as 'THE M-V = DB' on a device, while Skia -- and therefore the " +
                "golden -- draws it correctly. Convert each subpath start to an absolute 'M'.",
            0,
            relativeStarts,
        )
    }

    @Test
    fun theArtworkItselfIsUnchanged() {
        // The conversion above is a re-spelling, not an edit, and these are the fixed points that
        // say so: the viewport is theirs, and three subpaths close back onto an absolute
        // coordinate that has to equal the start computed for them. If a future edit moved the
        // artwork rather than re-expressing it, these are what would notice.
        assertTrue("viewport", drawable.contains("android:viewportWidth=\"423.04\""))
        assertTrue("viewport", drawable.contains("android:viewportHeight=\"35.4\""))
        assertTrue("the E of MOVIE closes at its own start", pathData.contains("H296.3Z"))
        assertTrue("the D closes at its own start", pathData.contains("H351.29Z"))
        assertTrue("M296.3 start", pathData.contains("M296.3,0"))
        assertTrue("M351.3 start", pathData.contains("M351.3,0"))

        // Their published gradient stops, unmodified in colour as their branding rules require.
        listOf("#90CEA1", "#3CBEC9", "#00B3E5").forEach {
            assertTrue("gradient stop $it", drawable.contains(it))
        }
    }

    private companion object {
        /**
         * Read from the source tree, the same carve-out `RealChangelogTest` uses: the point is to
         * assert against the file a maintainer edits, not against a compiled resource that has
         * already been through the parser this test distrusts.
         */
        val drawable: String =
            Path.of("src", "main", "res", "drawable", "tmdb_logo.xml").let { path ->
                check(Files.exists(path)) { "Expected tmdb_logo.xml at ${path.toAbsolutePath()}" }
                Files.readString(path)
            }

        val pathData: String =
            Regex("android:pathData=\"([^\"]*)\"")
                .find(drawable)
                ?.groupValues
                ?.get(1)
                ?: error("tmdb_logo.xml has no android:pathData")
    }
}
