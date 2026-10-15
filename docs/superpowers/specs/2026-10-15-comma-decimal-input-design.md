# #201: decimal fields show the locale's separator, so the filter never re-reads its own output

**Issue:** [#201](https://github.com/Masked-Kunsiquat/MediaTracker/issues/201).
**Branch:** `fix/201-comma-decimal-input`, from `main` at `e9d85e9`.

## The defect, precisely

`String.filterDecimalInput(decimalSeparator)` (`shared/.../ui/NumericFormField.kt`) is correct for
what it was designed for: **locale-style text in, canonical text out.** It keeps the digits, turns
the locale's decimal separator into `.`, and drops the other separator as a thousands mark.

The defect is at the call sites. Each decimal `OutlinedTextField` **displays the canonical text**.
Compose then hands the whole displayed text back to `onValueChange` on the next keystroke, so the
filter receives its own output. In a comma locale it reads that output's `.` as a thousands
separator and drops it:

| keystroke | field shows (today) | onValueChange gets | filter (`,`) | stored |
| --- | --- | --- | --- | --- |
| `1` `4` | `14` | `14,` | `14.` | `14.` |
| `9` | `14.` | `14.9` | `149` | `149` |
| `9` | `149` | `1499` | `1499` | **`1499`** |
| edit prefill, backspace | `19.99` | `19.9` | `199` | **`199`** |

In en-US the separator *is* `.`, so the filter is idempotent there, and nothing on any screen looks
wrong in either locale.

## Scope: nine fields, three more than #201 names

Every `filterDecimalInput()` call site in `app/src/main` (the app-side locale shim):

| screen | field | value source |
| --- | --- | --- |
| `AddMovieScreen.kt:262` | purchase price | local `purchasePrice` state |
| `AddTVShowScreen.kt:241` | purchase price | hoisted, `onPurchasePriceChange` |
| `EditBookScreen.kt:439` | purchase price | local `purchasePriceText` state |
| `EditMovieScreen.kt:234` | purchase price | `EditMovieUiState.Editing.purchasePrice` |
| `EditTVShowScreen.kt:217` | purchase price | hoisted, `onPurchasePriceChange` |
| `BookSessionDialogs.kt:492, :508` | session start / end position (log dialog) | local state |
| `BookSessionDialogs.kt:959, :975` | session start / end position (edit dialog) | local state |

- **Wider than the issue:** the four session-position fields carry e-reader *percentages*, so a
  comma-locale reader typing `45,5` gets the same corruption.
- **Narrower than the issue:** `AddBookScreen` has no price field. It adds by ISBN lookup.

## Design: display the locale form, store the canonical form

The stored and state text stays canonical (`14.99`). Each field *displays*
`canonical.replace('.', localeSeparator)`. The filter then only ever receives locale-style text,
which is the one case it was written for, so feeding its output back through it is a fixed point.

```kotlin
// shared/.../ui/NumericFormField.kt -- pure, testable without a locale
public fun String.toDisplayDecimal(decimalSeparator: Char): String = replace('.', decimalSeparator)

// app/.../ui/text/NumericInput.kt -- the shim, beside filterDecimalInput()
internal fun String.toDisplayDecimal(): String = toDisplayDecimal(localeDecimalSeparator())
```

At each of the nine fields:

```kotlin
OutlinedTextField(
    value = purchasePrice.toDisplayDecimal(),                       // was: purchasePrice
    onValueChange = { purchasePrice = it.filterDecimalInput() },    // unchanged
    ...
)
```

**Why this is safe.** Canonical text holds only digits and at most one `.`, because it only ever
comes out of the filter or from a ViewModel's `Double.toString()` pre-fill. (One pre-existing
exception: `toString()` writes `1.0E7` for values of ten million and above, which the filter already
garbles in every locale today. This change neither causes nor fixes it; see Out of scope.) So
`replace('.', sep)`
maps it to exactly the locale form, and the filter maps that back:
`filter(toDisplay(c)) == c` for every canonical `c`. Both are pure functions over that small
alphabet, and the round-trip test pins it.

**What does not change.**
- The filter's rules, including the thousands-separator table in its KDoc.
- ViewModels, parsing (`parseOptionalNumber` / `toDoubleOrNull` keep reading canonical text) and
  storage.
- en-US, where `toDisplayDecimal('.')` is the identity, so the Roborazzi goldens should not move.
  If any do, that is a finding to explain, not to re-record away.

**The one visible change.** Comma-locale users now see `14,99` rather than `14.99`. The shim's KDoc
currently calls showing the canonical text "deliberate for now", and says a localised display
"needs separate display and edit representations, which is a larger change". That KDoc is
rewritten: the two representations turn out to be one function, and showing canonical text is what
caused this bug.

**String fields, not `TextFieldValue`.** The replacement is one character for one character, so the
displayed string has the same length as the text the user edited. Compose's string-based field
therefore keeps the cursor where it was.

## Testing (TDD)

1. **`shared/src/commonTest/.../ui/NumericFormFieldTest.kt`** (pure Kotlin) adds a helper that
   reproduces Compose's loop:
   ```kotlin
   private fun typeInto(start: String, edits: List<(String) -> String>, sep: Char): String
   // displayed = canonical.toDisplayDecimal(sep); next = edit(displayed).filterDecimalInput(sep)
   ```
   with tests that are red before the fix (the helper calls the new function, so red first means
   "does not compile", then a deliberately identity-display run shows the behavioural failure):
   - de-DE (`,`): typing `1` `4` `,` `9` `9` stores `14.99`.
   - de-DE: prefilled `19.99`, one backspace, stores `19.9`.
   - de-DE: a typed `.` is still dropped as a thousands mark (`1.499` → `1499`).
   - en-US (`.`): the same keystrokes store `14.99`, and backspace gives `19.9`. A positive control
     that the fix changes nothing there.
   - Round trip: `filterDecimalInput(sep)(toDisplayDecimal(sep)(c)) == c` for a table of canonical
     values and both separators.
   - To see the behavioural red, run the loop once with `display = identity` (today's call sites)
     and assert it yields `1499` / `199`, which proves the harness reproduces the bug. It is kept
     as a named test documenting the defect.
2. **Robolectric screen test (`app/src/test/.../ui/screens/`)**, `Locale.GERMANY` set in `@Before`
   and restored in `@After`. It drives `EditMovieScreen` through a stateful fixture whose
   `onPurchasePriceChange` writes back into `EditMovieUiState.Editing.purchasePrice`:
   - The prefilled `19.99` displays as `19,99`.
   - Clearing the field and typing `1` `4` `,` `9` `9` one `performTextInput` at a time leaves the
     state at `14.99` and the field showing `14,99`.
   - Red before the call-site change (`1499`), green after.

   One screen proves the wiring pattern. The other eight are the identical one-line change, and the
   pure test covers the logic. The plan lists each call site for review.
3. **Device:** `app/src/` changes, so if a device is attached, run the full
   `connectedDebugAndroidTest` (after a `.sqlite` backup offer), then `seedDebugDevice`, then check
   by hand with the phone switched to a comma locale. `AddTVShowScreenTest` already touches
   `filterDecimalInput`; it must still pass.

## Also in this PR

- `CHANGELOG.md` `[Unreleased]` → `### Fixed`, since this is user-visible. Wording: in locales that
  write decimals with a comma, prices and reading positions are no longer stored 100× too large
  (or otherwise wrong) while typing or editing, and those fields now show the decimal comma.
- The shim's KDoc in `NumericInput.kt`, as above.

## Out of scope

Formatting numbers for *display elsewhere* (detail screens, stats). Grouping separators in the
input fields. `TextFieldValue`-based fields. Edit pre-fills of values of 10⁷ and above, which
`Double.toString()` writes in scientific notation; that is pre-existing in every locale and
implausible for a price, but it is noted on #201 so it is not lost.

## Amendment (owner-approved, after the device check)

The hand check on a Samsung SM-G975U1 (Android 12, de-DE) confirmed the `,` path: `14,99` typed and
displayed correctly, and backspace walked `14,99 → 14,9 → 14, → 14`. But it also found that
**Samsung's decimal keypad in de-DE disables `,` and offers only `.`**. With the original rule, `.`
was dropped as a thousands mark, so those users could not type a decimal at all (`14.99` → 1499),
the same as on `main`.

So the filter's rule changes, against "What does not change" above. **In a comma locale, both `,`
and `.` are read as the decimal point, and the first one typed wins.** A `.` locale is unchanged:
there `,` is still dropped as a thousands mark.

- The cost: a comma-locale user typing `1.499` to mean one thousand four hundred ninety-nine gets
  1.499. It is not silent, because the field displays `1,499`.
- Tests: `filterDecimalInput_commaLocale_dropsPeriodAsAThousandsSeparator` becomes
  `…_readsPeriodAsADecimalPointToo`. The replay test's "a typed `.` is still a thousands mark"
  becomes "typing with a period key stores the decimal". The bug-reproduction test now pins that
  the filter alone also prevents the defect.
- New tests: a mixed-separator test (first wins), and a Robolectric test typing `14.99` with the
  `.` key under `Locale.GERMANY`.
- Each was red first: three shared failures, and the screen test stored `1499`.
