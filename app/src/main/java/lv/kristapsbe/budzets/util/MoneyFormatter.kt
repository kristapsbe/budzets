package lv.kristapsbe.budzets.util

import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * The one shared currency formatter. Formatting is presentation only; formatted strings are
 * never stored. Not thread-safe (it wraps [NumberFormat]), so create one per screen.
 */
class MoneyFormatter(private val currency: CurrencySpec, locale: Locale = Locale.getDefault()) {
    private val format = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance(this@MoneyFormatter.currency.code)
        minimumFractionDigits = this@MoneyFormatter.currency.fractionDigits
        maximumFractionDigits = this@MoneyFormatter.currency.fractionDigits
    }

    fun format(money: Money): String = format.format(BigDecimal.valueOf(money.minor, currency.fractionDigits))
}

/** "April 2026", using the standalone month name (correct nominative case in e.g. Latvian). */
fun YearMonth.displayName(locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(this)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
