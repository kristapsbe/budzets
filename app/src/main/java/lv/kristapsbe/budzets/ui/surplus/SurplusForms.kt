package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.ui.components.amountErrorText
import lv.kristapsbe.budzets.util.MoneyFormatter

/**
 * Pockets as chips, each with its balance when [showBalance].
 *
 * @param leading a chip of the caller's, before the pockets (Move surplus's "All saved surplus").
 */
@Composable
internal fun PocketChips(
    choices: List<PocketChoice>,
    selected: SurplusPocket?,
    money: MoneyFormatter,
    showBalance: Boolean,
    onSelect: (SurplusPocket) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        choices.forEach { choice ->
            val name = choice.label.text()
            CheckChip(
                text = if (showBalance) stringResource(R.string.pocket_with_balance, name, money.format(choice.balance)) else name,
                selected = choice.label.pocket == selected,
                onClick = { onSelect(choice.label.pocket) },
            )
        }
    }
}

/** A filter chip that shows a tick while selected. */
@Composable
internal fun CheckChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

/** Amount field with an "All" shortcut that fills in the source's whole balance. */
@Composable
internal fun AmountWithAll(
    value: String,
    onChange: (String) -> Unit,
    error: AmountError?,
    exceedsAvailable: Money?,
    available: Money?,
    money: MoneyFormatter,
    fractionDigits: Int,
    onAll: () -> Unit,
    onDone: () -> Unit,
) {
    val (prefix, suffix) = money.inputAffixes
    val message = when {
        error != null -> amountErrorText(error, fractionDigits)
        exceedsAvailable != null -> stringResource(R.string.error_more_than_available, money.format(exceedsAvailable))
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.amount)) },
            placeholder = { Text(money.inputPlaceholder) },
            prefix = prefix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            suffix = suffix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            singleLine = true,
            isError = message != null,
            supportingText = message?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f).testTag(SURPLUS_AMOUNT_TAG),
        )
        if (available != null) {
            TextButton(onClick = onAll) { Text(stringResource(R.string.use_all, money.format(available))) }
        }
    }
}

@Composable
internal fun NoteField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.note_optional)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

const val SURPLUS_AMOUNT_TAG = "surplus_amount"
