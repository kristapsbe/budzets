package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.ui.components.FormSection
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.MonthSelector
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName

@Composable
fun BoostScreen(
    onClose: () -> Unit,
    viewModel: BoostViewModel = viewModel(factory = BoostViewModel.Factory),
) {
    val state = viewModel.state
    if (state == BoostState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    BoostContent(
        state,
        BoostActions(
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::goToCurrentMonth,
            onDestination = viewModel::selectDestination,
            onSource = viewModel::selectSource,
            onAmountChange = viewModel::onAmountChange,
            onAll = viewModel::useAll,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::save,
            onClose = onClose,
        ),
    )
}

data class BoostActions(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onCurrentMonth: () -> Unit = {},
    val onDestination: (CategoryId) -> Unit = {},
    val onSource: (SurplusPocket) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onAll: () -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoostContent(state: BoostState, actions: BoostActions) {
    val editing = state as? BoostState.Editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boost_a_budget)) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    if (editing != null) {
                        TextButton(onClick = actions.onSave, enabled = !editing.isSaving && !editing.monthClosed) { Text(stringResource(R.string.boost)) }
                    }
                },
            )
        },
    ) { padding ->
        if (editing == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val locale = LocalConfiguration.current.locales[0]
        val money = remember(editing.currency, locale) { MoneyFormatter(editing.currency, locale) }
        val month = editing.month.displayName(locale)
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.boost_explanation), style = MaterialTheme.typography.bodyMedium)
            // Earlier months can't be boosted, so "back" stops at the current month.
            MonthSelector(editing.month, editing.isCurrentMonth, actions.onPreviousMonth, actions.onNextMonth, actions.onCurrentMonth)
            if (editing.monthClosed) Banner(Icons.Filled.Lock, stringResource(R.string.boost_month_closed, month), warning = true)

            FormSection(stringResource(R.string.boost_for), stringResource(R.string.error_category_missing).takeIf { editing.missingDestination }) {
                PocketChips(
                    editing.destinations,
                    editing.form.destination?.let { SurplusPocket.Category(it) },
                    money,
                    showBalance = false,
                    onSelect = { (it as? SurplusPocket.Category)?.let { p -> actions.onDestination(p.id) } },
                )
            }
            FormSection(stringResource(R.string.boost_from_pocket), stringResource(R.string.error_choose_pocket).takeIf { editing.missingSource }) {
                if (editing.sources.isEmpty()) Text(stringResource(R.string.no_surplus_to_use))
                PocketChips(editing.sources, editing.form.source, money, showBalance = true, onSelect = actions.onSource)
            }
            AmountWithAll(
                value = editing.form.amountText,
                onChange = actions.onAmountChange,
                error = editing.amountError,
                exceedsAvailable = editing.exceedsAvailable,
                available = editing.available,
                money = money,
                fractionDigits = editing.currency.fractionDigits,
                onAll = actions.onAll,
                onDone = actions.onSave,
            )
            val destinationName = editing.destinations.firstOrNull { (it.label.pocket as? SurplusPocket.Category)?.id == editing.form.destination }?.label?.text()
            val before = editing.currentBudget
            val amount = editing.amount
            if (destinationName != null && before != null && amount != null) {
                Banner(
                    Icons.Filled.Info,
                    stringResource(R.string.boost_preview, destinationName, month, money.format(before), money.format(amount), money.format(before + amount)),
                )
            }
            NoteField(editing.form.note, actions.onNoteChange, actions.onSave)
            if (editing.failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
