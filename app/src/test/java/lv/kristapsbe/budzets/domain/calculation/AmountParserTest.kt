package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.canSwitchCurrency
import lv.kristapsbe.budzets.domain.ok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountParserTest {

    private val eur = CurrencySpec.DEFAULT
    private val jpy = CurrencySpec.of("JPY")
    private val kwd = CurrencySpec.of("KWD")

    private fun parse(s: String, c: CurrencySpec = eur, allowZero: Boolean = false) = AmountParser.parse(s, c, allowZero)

    @Test
    fun `default currency is EUR with 2 digits`() {
        assertEquals(CurrencySpec("EUR", 2), CurrencySpec.DEFAULT)
        assertEquals(0, jpy.fractionDigits)
        assertEquals(3, kwd.fractionDigits)
    }

    @Test
    fun `comma and dot both work as decimal separator`() {
        assertEquals(Money(1_250), parse("12,5").ok())
        assertEquals(Money(1_250), parse("12.50").ok())
        assertEquals(Money(1_200), parse(" 12 ").ok())
        assertEquals(Money(1_200), parse("12.").ok())
        assertEquals(Money(50), parse(",5").ok())
        assertEquals(Money(1_200), parse("12.000").ok()) // trailing zeros are fine
    }

    @Test
    fun `respects currency precision`() {
        assertEquals(AmountError.TOO_MANY_DECIMALS, parse("1.234").err())
        assertEquals(Money(1_234), parse("1.234", kwd).ok())
        assertEquals(Money(500), parse("500", jpy).ok())
        assertEquals(AmountError.TOO_MANY_DECIMALS, parse("500.5", jpy).err())
    }

    @Test
    fun `rejects empty, garbage, non-positive and too large`() {
        assertEquals(AmountError.EMPTY, parse("  ").err())
        assertEquals(AmountError.NOT_A_NUMBER, parse("abc").err())
        assertEquals(AmountError.NOT_A_NUMBER, parse("1.000,50").err())
        assertEquals(AmountError.NOT_A_NUMBER, parse("1e5").err())
        assertEquals(AmountError.NOT_POSITIVE, parse("0").err())
        assertEquals(AmountError.NOT_POSITIVE, parse("-5").err())
        assertEquals(Money.ZERO, parse("0", allowZero = true).ok())
        assertEquals(Money(AmountParser.MAX_MINOR), parse("1000000000").ok())
        assertEquals(AmountError.TOO_LARGE, parse("1000000000.01").err())
        assertEquals(AmountError.TOO_LARGE, parse("99999999999999999999999999").err())
    }

    @Test
    fun `currency switching only between equal precision once data exists`() {
        assertTrue(canSwitchCurrency(eur, jpy, hasData = false))
        assertTrue(canSwitchCurrency(eur, CurrencySpec.of("USD"), hasData = true))
        assertFalse(canSwitchCurrency(eur, jpy, hasData = true))
        assertFalse(canSwitchCurrency(eur, kwd, hasData = true))
    }
}
