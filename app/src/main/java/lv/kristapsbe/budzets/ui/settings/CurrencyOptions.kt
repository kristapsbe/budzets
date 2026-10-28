package lv.kristapsbe.budzets.ui.settings

import lv.kristapsbe.budzets.domain.model.CurrencySpec
import java.util.Currency
import java.util.Date
import java.util.Locale

data class CurrencyOption(val spec: CurrencySpec, val name: String)

/**
 * Currencies in use today (no historic ones such as LVL or DEM), named in [locale] and sorted by
 * name. Pseudo-currencies without a minor unit definition (XXX, XAU…) are left out.
 */
fun currencyOptions(locale: Locale, today: Date = Date()): List<CurrencyOption> =
    Currency.getAvailableCurrencies()
        .filter { it.defaultFractionDigits >= 0 && isInUse(it.currencyCode, today) }
        .map { CurrencyOption(CurrencySpec.of(it.currencyCode), it.getDisplayName(locale)) }
        .sortedBy { it.name.lowercase(locale) }

private fun isInUse(code: String, today: Date): Boolean =
    runCatching { android.icu.util.Currency.isAvailable(code, today, today) }.getOrDefault(false)

/** Matches a typed query against the name or the code. */
fun CurrencyOption.matches(query: String): Boolean =
    query.isBlank() || name.contains(query.trim(), ignoreCase = true) || spec.code.contains(query.trim(), ignoreCase = true)
