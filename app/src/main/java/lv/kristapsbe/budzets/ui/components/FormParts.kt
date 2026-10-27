package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.AmountError

/** A titled block of a form, with an optional error line under its content. */
@Composable
fun FormSection(title: String, error: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        content()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The message for an amount that didn't parse, in terms of the currency's precision. */
@Composable
fun amountErrorText(error: AmountError, fractionDigits: Int): String = when (error) {
    AmountError.EMPTY -> stringResource(R.string.error_amount_empty)
    AmountError.NOT_A_NUMBER -> stringResource(R.string.error_amount_not_number)
    AmountError.TOO_MANY_DECIMALS ->
        if (fractionDigits == 0) stringResource(R.string.error_amount_whole)
        else pluralStringResource(R.plurals.error_amount_decimals, fractionDigits, fractionDigits)
    AmountError.NOT_POSITIVE -> stringResource(R.string.error_amount_not_positive)
    AmountError.TOO_LARGE -> stringResource(R.string.error_amount_too_large)
}
