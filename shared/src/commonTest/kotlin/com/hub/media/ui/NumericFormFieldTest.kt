package com.hub.media.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The filter half of these is what #78 was filed about: a comma-decimal locale typing "14,99" into
 * a purchase price stored 1499. The locale table below is the regression guard for that, in both
 * directions — fixing the comma locale by accepting `,` everywhere would corrupt en-US thousands
 * separators the same silent way.
 */
class NumericFormFieldTest {
    // ---- filterDecimalInput: the money bug, both locales -------------------------------------

    @Test
    fun filterDecimalInput_commaLocale_readsCommaAsDecimalPointRatherThanDroppingIt() {
        // The #78 defect: dropping the comma turned 14,99 into 1499 -- a hundred times the price,
        // saved without an error because toDoubleOrNull() reads "1499" perfectly well.
        assertEquals("14.99", "14,99".filterDecimalInput(decimalSeparator = ','))
    }

    @Test
    fun filterDecimalInput_commaLocale_dropsPeriodAsAThousandsSeparator() {
        assertEquals("1499", "1.499".filterDecimalInput(decimalSeparator = ','))
    }

    @Test
    fun filterDecimalInput_periodLocale_readsPeriodAsDecimalPoint() {
        assertEquals("14.99", "14.99".filterDecimalInput(decimalSeparator = '.'))
    }

    @Test
    fun filterDecimalInput_periodLocale_dropsCommaAsAThousandsSeparator() {
        // The other direction of the same mistake: "1,499" in en-US means 1499, not 1.499.
        assertEquals("1499", "1,499".filterDecimalInput(decimalSeparator = '.'))
    }

    // ---- filterDecimalInput: shape ------------------------------------------------------------

    @Test
    fun filterDecimalInput_keepsOnlyTheFirstSeparator() {
        assertEquals("1.23", "1.2.3".filterDecimalInput(decimalSeparator = '.'))
        assertEquals("1.23", "1,2,3".filterDecimalInput(decimalSeparator = ','))
    }

    @Test
    fun filterDecimalInput_stripsEverythingThatIsNotADigitOrTheSeparator() {
        assertEquals("1499", "£14 99".filterDecimalInput(decimalSeparator = ','))
        assertEquals("14.99", "\$14.99abc".filterDecimalInput(decimalSeparator = '.'))
    }

    @Test
    fun filterDecimalInput_blankStaysBlank() {
        // Blank is "unknown" and must survive filtering as blank -- see parseOptionalNumber.
        assertEquals("", "".filterDecimalInput(decimalSeparator = '.'))
    }

    @Test
    fun filterDecimalInput_leadingSeparatorIsKept() {
        // ".5" is mid-typing on the way to "0.5"; the filter must not swallow it or the field
        // becomes untypeable.
        assertEquals(".5", ",5".filterDecimalInput(decimalSeparator = ','))
    }

    // ---- filterIntegerInput -------------------------------------------------------------------

    @Test
    fun filterIntegerInput_keepsDigitsOnly() {
        assertEquals("2015", "2015".filterIntegerInput())
        assertEquals("2015", "20a15-".filterIntegerInput())
        assertEquals("1499", "1,499".filterIntegerInput())
        assertEquals("", "".filterIntegerInput())
    }

    // ---- the parse half, previously untested --------------------------------------------------

    @Test
    fun parseOptionalNumber_blankIsKnownUnknownRatherThanUnreadable() {
        val result = assertNotNull(parseOptionalNumber("   ", String::toIntOrNull), "blank is not an unreadable field")
        assertNull(result.value, "blank must parse to a present result holding null")
    }

    @Test
    fun parseOptionalNumber_unreadableIsDistinctFromBlank() {
        // The distinction this file exists to preserve: collapsing these is how an edit form
        // silently erases the value the user opened it to correct.
        assertNull(parseOptionalNumber("19999999999", String::toIntOrNull))
    }

    @Test
    fun parseOptionalNumber_readsTheNumber() {
        assertEquals(2015, parseOptionalNumber("2015", String::toIntOrNull)?.value)
        assertEquals(14.99, parseOptionalNumber(" 14.99 ", String::toDoubleOrNull)?.value)
    }

    @Test
    fun parseRequiredInt_blankAndUnreadableBothCollapseToNull() {
        assertNull(parseRequiredInt(""))
        assertNull(parseRequiredInt("   "))
        assertNull(parseRequiredInt("abc"))
        assertEquals(10, parseRequiredInt(" 10 "))
    }

    @Test
    fun parseOptionalFiniteDouble_blankIsUnknown_notUnreadable() {
        // The distinction #73 established: a blank field means "I do not know", which is a value
        // the caller may legitimately store, and must not collapse into the error case.
        assertNotNull(parseOptionalFiniteDouble(""))
        assertNull(parseOptionalFiniteDouble("")?.value)
        assertNull(parseOptionalFiniteDouble("   ")?.value)
    }

    @Test
    fun parseOptionalFiniteDouble_readsAFiniteNumber() {
        assertEquals(42.0, parseOptionalFiniteDouble("42")?.value)
        assertEquals(78.5, parseOptionalFiniteDouble(" 78.5 ")?.value)
        assertEquals(0.0, parseOptionalFiniteDouble("0")?.value)
    }

    @Test
    fun parseOptionalFiniteDouble_unparseableIsUnreadable() {
        assertNull(parseOptionalFiniteDouble("."))
        assertNull(parseOptionalFiniteDouble("abc"))
    }

    @Test
    fun parseOptionalFiniteDouble_overflowIsUnreadable_notInfinity() {
        // The case this function exists for. toDoubleOrNull does not fail here -- it succeeds and
        // returns POSITIVE_INFINITY -- so a caller checking only "did it parse" would accept it.
        // The field is digit-filtered, so "Infinity" cannot be typed, but a long run of digits can.
        val overflowing = "9".repeat(400)
        assertEquals(Double.POSITIVE_INFINITY, overflowing.toDoubleOrNull())
        assertNull(parseOptionalFiniteDouble(overflowing))
    }
}
