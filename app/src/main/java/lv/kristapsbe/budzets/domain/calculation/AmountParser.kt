package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import java.math.BigDecimal

enum class AmountError { EMPTY, NOT_A_NUMBER, TOO_MANY_DECIMALS, NOT_POSITIVE, TOO_LARGE }

/**
 * Parses user-entered amounts into minor units. Both `,` and `.` are accepted as the decimal
 * separator. Grouping separators are not supported, so at most one separator is allowed.
 */
object AmountParser {
    /** Sanity cap: 1e11 minor units, i.e. 1 000 000 000.00 in a 2-digit currency. */
    const val MAX_MINOR = 100_000_000_000L

    private val pattern = Regex("""-?(\d+[.,]?\d*|[.,]\d+)""")

    fun parse(input: String, currency: CurrencySpec, allowZero: Boolean = false): Outcome<Money, AmountError> {
        val text = input.trim().filterNot { it == ' ' || it == ' ' }
        if (text.isEmpty()) return Outcome.Err(AmountError.EMPTY)
        if (!pattern.matches(text)) return Outcome.Err(AmountError.NOT_A_NUMBER)

        val value = BigDecimal(text.replace(',', '.'))
        if (value.signum() < 0 || (value.signum() == 0 && !allowZero)) return Outcome.Err(AmountError.NOT_POSITIVE)
        if (value.stripTrailingZeros().scale() > currency.fractionDigits) return Outcome.Err(AmountError.TOO_MANY_DECIMALS)

        val minor = value.movePointRight(currency.fractionDigits).setScale(0)
        if (minor > BigDecimal.valueOf(MAX_MINOR)) return Outcome.Err(AmountError.TOO_LARGE)
        return Outcome.Ok(Money(minor.longValueExact()))
    }
}
