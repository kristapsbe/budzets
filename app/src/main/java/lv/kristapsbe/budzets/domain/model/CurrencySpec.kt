package lv.kristapsbe.budzets.domain.model

import java.util.Currency

/** The single display currency. [fractionDigits] defines how [Money.minor] is interpreted. */
data class CurrencySpec(val code: String, val fractionDigits: Int) {
    companion object {
        val DEFAULT: CurrencySpec = of("EUR")

        /** @throws IllegalArgumentException for an unknown ISO 4217 code. */
        fun of(code: String): CurrencySpec {
            val currency = Currency.getInstance(code)
            // Pseudo-currencies (e.g. XXX) report -1; treat them as having no minor unit.
            return CurrencySpec(currency.currencyCode, currency.defaultFractionDigits.coerceAtLeast(0))
        }
    }
}

/**
 * Stored amounts are minor units, and switching currency never converts them. Once data
 * exists, only a currency with the same number of fraction digits is allowed, so that
 * e.g. 1000 minor units can't silently change from €10.00 to ¥1000.
 */
fun canSwitchCurrency(from: CurrencySpec, to: CurrencySpec, hasData: Boolean): Boolean =
    !hasData || from.fractionDigits == to.fractionDigits
