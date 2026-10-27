package lv.kristapsbe.budzets.util

import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyFormatterTest {

    @Test
    fun `formats minor units with currency precision`() {
        val eur = MoneyFormatter(CurrencySpec.DEFAULT, Locale.US)
        assertEquals("€12.50", eur.format(Money(1_250)))
        assertEquals("€0.05", eur.format(Money(5)))
        assertEquals("-€3.00", eur.format(Money(-300)))
        assertEquals("¥500", MoneyFormatter(CurrencySpec.of("JPY"), Locale.US).format(Money(500)))
        assertEquals("KWD1.234", MoneyFormatter(CurrencySpec.of("KWD"), Locale.US).format(Money(1_234)).replace(" ", ""))
    }

    @Test
    fun `uses the locale's conventions`() {
        val lv = MoneyFormatter(CurrencySpec.DEFAULT, Locale.forLanguageTag("lv-LV")).format(Money(123_456))
        // Latvian: space grouping, decimal comma, euro sign after the amount (spaces may be no-break).
        assertEquals("1 234,56 €", lv.replace('\u00A0', ' ').replace('\u202F', ' '))
    }

    @Test
    fun `month display name`() {
        assertEquals("April 2026", ym("2026-04").displayName(Locale.US))
        assertEquals("Aprīlis 2026", ym("2026-04").displayName(Locale.forLanguageTag("lv-LV")))
    }

    @Test
    fun `input text uses the locale separator and no symbol or grouping`() {
        val us = MoneyFormatter(CurrencySpec.DEFAULT, Locale.US)
        assertEquals("1234.50", us.formatForInput(Money(123_450)))
        assertEquals("0.05", us.formatForInput(Money(5)))
        assertEquals("0.00", us.inputPlaceholder)
        assertEquals("€" to "", us.inputAffixes)

        val lv = MoneyFormatter(CurrencySpec.DEFAULT, Locale.forLanguageTag("lv-LV"))
        assertEquals("1234,50", lv.formatForInput(Money(123_450)))
        assertEquals("0,00", lv.inputPlaceholder)
        assertEquals("" to "€", lv.inputAffixes)

        val yen = MoneyFormatter(CurrencySpec.of("JPY"), Locale.US)
        assertEquals("500", yen.formatForInput(Money(500)))
        assertEquals("0", yen.inputPlaceholder)
    }
}
