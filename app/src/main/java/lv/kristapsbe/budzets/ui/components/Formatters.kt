package lv.kristapsbe.budzets.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.util.MoneyFormatter

/** A formatter for [currency] in the app's language, made again only when either changes. */
@Composable
fun rememberMoneyFormatter(currency: CurrencySpec): MoneyFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(currency, locale) { MoneyFormatter(currency, locale) }
}
