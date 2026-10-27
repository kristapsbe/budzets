package lv.kristapsbe.budzets.util

import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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
    private val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator

    fun format(money: Money): String = format.format(BigDecimal.valueOf(money.minor, currency.fractionDigits))

    /** Plain, editable text for an amount field: no symbol or grouping, the locale's decimal separator. */
    fun formatForInput(money: Money): String = formatForInput(money, currency, decimalSeparator)

    /** "0.00" / "0,00" / "0", matching what [formatForInput] produces. */
    val inputPlaceholder: String =
        if (currency.fractionDigits == 0) "0" else "0$decimalSeparator" + "0".repeat(currency.fractionDigits)

    /** The currency symbol as this locale writes it: before the number (prefix) or after it (suffix). */
    val inputAffixes: Pair<String, String> = (format as? DecimalFormat)
        ?.let { it.positivePrefix.trim() to it.positiveSuffix.trim() }
        ?: ("" to currency.code)
}

/** Keeps only what an amount field accepts (digits and `,`/`.`), e.g. when text is pasted. */
fun String.amountInputChars(): String = filter { it.isDigit() || it == '.' || it == ',' }

fun formatForInput(money: Money, currency: CurrencySpec, decimalSeparator: Char): String =
    BigDecimal.valueOf(money.minor, currency.fractionDigits).toPlainString().replace('.', decimalSeparator)

/** "April 2026", using the standalone month name (correct nominative case in e.g. Latvian). */
fun YearMonth.displayName(locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(this)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
