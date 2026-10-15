# #201 — Decimal fields display the locale's separator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop comma-locale decimal fields from re-filtering their own canonical output. Show `canonical.replace('.', localeSeparator)` in all nine fields; storage stays canonical.

**Architecture:** One pure function in `shared` (`String.toDisplayDecimal(sep)`) and its app-side locale shim. Each of the nine `OutlinedTextField`s changes its `value =` only. The filter, ViewModels and storage don't change.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose, kotlin.test, Robolectric with `createComposeRule`.

**Spec:** `docs/superpowers/specs/2026-10-15-comma-decimal-input-design.md`

## Global Constraints

- **No change to these:** `filterDecimalInput`'s rules, any ViewModel, any parse helper, any stored value.
- **en-US is unchanged:** `toDisplayDecimal('.')` is the identity. If a golden moves, that is a finding to explain, never something to re-record away.
- **Test locations:** pure tests go in `shared/src/commonTest`; screen tests go in `app/src/test` (the Robolectric lane). Neither touches Room.
- **Editing:** use package-qualified test filters, and the Edit tool for source edits.
- **Before push:** `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.
- **Device:** `app/src/` changes, so run `adb devices`. If a device is attached, offer a `.sqlite` backup, then run `./gradlew :app:connectedDebugAndroidTest` (without the leave-installed flag), then `:app:seedDebugDevice`, then check by hand in a comma locale. The PR states the outcome, or why it wasn't run.

## Review Focus

1. **Every call site:** all nine `value =` lines use `.toDisplayDecimal()`, and no `onValueChange` changed.
2. **Cursor position:** the replacement is one character for one character, so a mid-string edit, such as backspacing in the middle of `19,99`, keeps the cursor where it was.
3. **Pre-fill of a whole number:** `20.0` from `Double.toString()` displays as `20,0` and round-trips to `20.0`.
4. **Empty field:** `""` displays as `""`.
5. **Locale change while backgrounded:** the shim reads the separator per call, so the display follows the new locale on the next recomposition.

---

### Task 1: `toDisplayDecimal`, proven against Compose's keystroke loop

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/NumericFormField.kt` (after `filterDecimalInput`, ~line 104)
- Test: `shared/src/commonTest/kotlin/com/hub/media/ui/NumericFormFieldTest.kt`

**Interfaces:** Produces `public fun String.toDisplayDecimal(decimalSeparator: Char): String`.

- [ ] **Step 1: Write the failing tests.** Append inside `class NumericFormFieldTest`:

```kotlin
    // --- #201: the field displays its value, and Compose feeds the displayed text back ---

    /**
     * Replays Compose's loop for a string-based field: each edit is applied to what the field
     * DISPLAYS, and the result goes through the filter into state. [display] is the call site's
     * `value =` mapping.
     */
    private fun replay(
        start: String,
        edits: List<(String) -> String>,
        separator: Char,
        display: (String) -> String,
    ): String =
        edits.fold(start) { canonical, edit -> edit(display(canonical)).filterDecimalInput(separator) }

    private fun typing(text: String): List<(String) -> String> = text.map { char -> { shown: String -> shown + char } }

    private val backspace: (String) -> String = { it.dropLast(1) }

    @Test
    fun displayingCanonicalText_commaLocale_reproducesTheBug() {
        // Today's call sites (#201): the field shows the canonical text, so the filter re-reads its
        // own '.' as a thousands mark. Kept as a named record of the defect the fix closes.
        assertEquals("1499", replay("", typing("14,99"), ',', display = { it }))
        assertEquals("199", replay("19.99", listOf(backspace), ',', display = { it }))
    }

    @Test
    fun displayingLocaleText_commaLocale_typingStoresTheDecimal() {
        assertEquals("14.99", replay("", typing("14,99"), ',', display = { it.toDisplayDecimal(',') }))
    }

    @Test
    fun displayingLocaleText_commaLocale_backspaceOnAPrefillKeepsTheDecimal() {
        assertEquals("19.9", replay("19.99", listOf(backspace), ',', display = { it.toDisplayDecimal(',') }))
    }

    @Test
    fun displayingLocaleText_commaLocale_aTypedPeriodIsStillAThousandsMark() {
        assertEquals("1499", replay("", typing("1.499"), ',', display = { it.toDisplayDecimal(',') }))
    }

    @Test
    fun displayingLocaleText_periodLocale_behavesExactlyAsBefore() {
        // Positive control: en-US was never broken, and the fix must not change it.
        assertEquals("14.99", replay("", typing("14.99"), '.', display = { it.toDisplayDecimal('.') }))
        assertEquals("19.9", replay("19.99", listOf(backspace), '.', display = { it.toDisplayDecimal('.') }))
        assertEquals("1499", replay("", typing("1,499"), '.', display = { it.toDisplayDecimal('.') }))
    }

    @Test
    fun toDisplayDecimal_thenFilter_isTheIdentityOnCanonicalText() {
        for (separator in listOf(',', '.')) {
            for (canonical in listOf("", "0", "14", "14.", "14.99", ".5", "20.0", "1499")) {
                assertEquals(
                    canonical,
                    canonical.toDisplayDecimal(separator).filterDecimalInput(separator),
                    "round trip of '$canonical' with '$separator'",
                )
            }
        }
    }

    @Test
    fun toDisplayDecimal_commaLocale_showsTheComma() {
        assertEquals("14,99", "14.99".toDisplayDecimal(','))
        assertEquals("20,0", "20.0".toDisplayDecimal(','))
        assertEquals("", "".toDisplayDecimal(','))
    }
```

- [ ] **Step 2: Run them to verify they fail.**
Run: `./gradlew :shared:jvmTest --tests 'com.hub.media.ui.NumericFormFieldTest'`
Expected: a compile failure, `Unresolved reference 'toDisplayDecimal'`.

- [ ] **Step 3: Implement.** Add after `filterDecimalInput`:

```kotlin
/**
 * How a decimal field should DISPLAY its canonical text: [filterDecimalInput]'s `.` shown as the
 * locale's [decimalSeparator].
 *
 * A Compose text field hands the whole displayed text back to `onValueChange` on every keystroke,
 * so whatever a field displays is what the filter reads next. Displaying the canonical text in a
 * comma locale made the filter re-read its own `.` as a thousands mark: "14,99" was stored as 1499,
 * and one backspace on a pre-filled "19.99" stored 199 (#201). Displaying this instead means the
 * filter only ever sees locale-style text, the one case it was written for, and
 * `toDisplayDecimal(sep).filterDecimalInput(sep)` is the identity on anything the filter produced.
 *
 * One character replaces one character, so the displayed string is the same length as the canonical
 * one and a string-based field keeps the cursor where it was.
 */
public fun String.toDisplayDecimal(decimalSeparator: Char): String = replace('.', decimalSeparator)
```

- [ ] **Step 4: Run to verify they pass.** Same command. Expected: PASS, with the bug-reproduction test also green (it uses `display = { it }`).

- [ ] **Step 5: Prove the fix tests can fail.** Temporarily change the body to `this`, so the display is the identity, and re-run. Expected: `typingStoresTheDecimal` and `backspaceOnAPrefillKeepsTheDecimal` fail with `1499` / `199`, and `showsTheComma` fails. Restore the body; PASS.

- [ ] **Step 6: Commit.** Run `./gradlew ktlintFormat`, then `git commit -m "toDisplayDecimal: what a decimal field shows, so the filter never re-reads its own output (#201)"`.

---

### Task 2: The nine fields display the locale form

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/text/NumericInput.kt` (the shim, and the KDoc paragraph that calls canonical display "deliberate for now")
- Modify the `value =` line, and add `import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal`, in:
  - `ui/screens/AddMovieScreen.kt:259`: `value = purchasePrice,` → `value = purchasePrice.toDisplayDecimal(),`
  - `ui/screens/AddTVShowScreen.kt:240`: `value = uiState.purchasePrice,` → `value = uiState.purchasePrice.toDisplayDecimal(),`
  - `ui/screens/EditBookScreen.kt:438`: `value = purchasePriceText,` → `value = purchasePriceText.toDisplayDecimal(),`
  - `ui/screens/EditMovieScreen.kt:231`: `value = form.purchasePrice,` → `value = form.purchasePrice.toDisplayDecimal(),`
  - `ui/screens/EditTVShowScreen.kt:214`: `value = form.purchasePrice,` → `value = form.purchasePrice.toDisplayDecimal(),`
  - `ui/screens/BookSessionDialogs.kt:491, :507, :958, :974`: `value = startUnitText,` / `value = endUnitText,` → `.toDisplayDecimal()` on each. There are two of each, so anchor each edit on its following `onValueChange` line plus enough context to be unique, and assert 4 replacements in total.
- Test (create): `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/DecimalFieldLocaleTest.kt`

**Interfaces:** Consumes `com.hub.media.ui.toDisplayDecimal(Char)` (Task 1). Produces `internal fun String.toDisplayDecimal(): String` in `ui/text/NumericInput.kt`.

- [ ] **Step 1: Write the failing screen test.**

```kotlin
package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals

/**
 * #201 through a real field: in a comma-decimal locale, a decimal field must display the locale's
 * separator, or Compose feeds the canonical "." back into the filter and it is dropped as a
 * thousands mark. One screen proves the wiring; the other eight fields are the same one-line change,
 * and the keystroke loop itself is tested in shared's NumericFormFieldTest.
 */
@RunWith(RobolectricTestRunner::class)
class DecimalFieldLocaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var previousLocale: Locale

    @Before
    fun useCommaLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private var storedPrice = ""

    private fun setUpEditMovie(prefill: String) {
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    EditMovieUiState.Editing(
                        title = "Interstellar",
                        releaseYear = "2014",
                        runtimeMinutes = "169",
                        purchasePrice = prefill,
                        status = WatchStatus.WATCHLIST,
                    ),
                )
            }
            storedPrice = state.purchasePrice
            EditMovieScreen(
                uiState = state,
                onTitleChange = {},
                onReleaseYearChange = {},
                onRuntimeChange = {},
                onPurchasePriceChange = { state = state.copy(purchasePrice = it) },
                onStatusChange = {},
                onSave = {},
                onErrorShown = {},
                onRetry = {},
                onNavigateBack = {},
            )
        }
    }

    private val priceLabel: String
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.add_movie_field_price)

    private fun priceField() = composeRule.onNode(hasSetTextAction() and hasText(priceLabel))

    @Test
    fun prefilledPrice_displaysTheLocaleDecimalComma() {
        setUpEditMovie(prefill = "19.99")
        priceField().assert(hasText("19,99"))
    }

    @Test
    fun typingAPriceKeystrokeByKeystroke_storesTheDecimal() {
        setUpEditMovie(prefill = "19.99")
        priceField().performTextClearance()
        for (char in "14,99") priceField().performTextInput(char.toString())
        composeRule.waitForIdle()
        assertEquals("14.99", storedPrice)
        priceField().assert(hasText("14,99"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails.**
Run: `./gradlew :app:testDebugUnitTest --tests 'com.github.maskedkunisquat.mediatracker.ui.screens.DecimalFieldLocaleTest'`
Expected: both FAIL. The first fails because the field shows `19.99`. The second fails with `expected 14.99, actual 1499`, the bug itself. If it fails for any other reason (label not found, no text action), fix the test before touching production code.

- [ ] **Step 3: Implement the shim.** In `NumericInput.kt` add, after `filterDecimalInput()`:

```kotlin
/**
 * What a decimal field displays: its canonical text with the device locale's separator. Pair it with
 * [filterDecimalInput] on every decimal field, `value = x.toDisplayDecimal()` and
 * `onValueChange = { x = it.filterDecimalInput() }`, so the filter only ever reads locale-style
 * text. See [com.hub.media.ui.toDisplayDecimal] for why displaying the canonical text corrupted
 * comma-locale input (#201).
 */
internal fun String.toDisplayDecimal(): String = toDisplayDecimal(localeDecimalSeparator())
```

Replace the KDoc paragraph on `filterDecimalInput()` that begins "Note the field then *displays* the normalised text" with:

```
 * The field must DISPLAY its text through [toDisplayDecimal], not as stored. Compose feeds the
 * displayed text back to `onValueChange`, and in a comma locale this filter reads a displayed `.`
 * as a thousands mark (#201). This used to say showing the canonical text was "deliberate for
 * now"; it was the bug.
```

- [ ] **Step 4: Change the nine `value =` lines** listed under Files, with imports.

- [ ] **Step 5: Run to verify it passes.** Same command as Step 2. Expected: PASS (2/2). Then confirm every call site has its display counterpart:
`grep -c "toDisplayDecimal()" app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/*.kt`: the total must be **9**, and `filterDecimalInput()` call sites must also total 9.

- [ ] **Step 6: Commit.** Run `./gradlew ktlintFormat && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug`; PASS, with no golden diff. Then `git commit -m "Decimal fields display the locale's separator (#201)"`.

---

### Task 3: CHANGELOG, gate, device, review, PR

- [ ] **Step 1: CHANGELOG.** Under `[Unreleased]`, add `### Fixed` above `### Internal`:

```markdown
### Fixed
- In languages that write decimals with a comma, prices and reading-session positions are no longer saved wrong while you type or edit them. Typing "14,99" saved 1499, and one backspace in a saved "19,99" saved 199. These fields now also show the decimal comma. (#201)
```

  Commit: `CHANGELOG for #201`.
- [ ] **Step 2: Full gate** (Global Constraints). Green.
- [ ] **Step 3: Device lane** per Global Constraints. Record the tally, or why it wasn't run.
- [ ] **Step 4: Fresh Opus review** of `git diff main...HEAD`, briefed with this plan's Review Focus.
- [ ] **Step 5: CodeRabbit:** `coderabbit review --agent --committed --base main -c AGENTS.md`.
- [ ] **Step 6: Comment on #201:** the six-to-nine scope correction, and the pre-existing `1.0E7` pre-fill note.
- [ ] **Step 7: Push and open the PR**, with base `main` and `Closes #201`.
- [ ] **Step 8: Outcomes table** in `C:\Users\blain\graphban-outcomes\201-comma-decimal.md`.
