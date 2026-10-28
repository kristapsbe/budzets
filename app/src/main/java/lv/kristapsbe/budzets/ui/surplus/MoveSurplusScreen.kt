package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.ui.components.FormSection
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.util.MoneyFormatter

@Composable
fun MoveSurplusScreen(
    onClose: () -> Unit,
    viewModel: MoveSurplusViewModel = viewModel(factory = MoveSurplusViewModel.Factory),
) {
    val state = viewModel.state
    if (state == MoveSurplusState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    MoveSurplusContent(
        state,
        MoveSurplusActions(
            onFrom = viewModel::selectFrom,
            onTo = viewModel::selectTo,
            onAmountChange = viewModel::onAmountChange,
            onAll = viewModel::useAll,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::save,
            onClose = onClose,
        ),
    )
}

data class MoveSurplusActions(
    val onFrom: (SurplusPocket) -> Unit = {},
    val onTo: (SurplusPocket) -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onAll: () -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSurplusContent(state: MoveSurplusState, actions: MoveSurplusActions) {
    val editing = state as? MoveSurplusState.Editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.move_surplus)) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    if (editing != null) TextButton(onClick = actions.onSave, enabled = !editing.isSaving) { Text(stringResource(R.string.move)) }
                },
            )
        },
    ) { padding ->
        if (editing == null) {
            LoadingIndicator(Modifier.padding(padding))
            return@Scaffold
        }
        val locale = LocalConfiguration.current.locales[0]
        val money = remember(editing.currency, locale) { MoneyFormatter(editing.currency, locale) }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.move_explanation), style = MaterialTheme.typography.bodyMedium)
            FormSection(stringResource(R.string.move_from), stringResource(R.string.error_choose_pocket).takeIf { editing.missingFrom }) {
                if (editing.sources.isEmpty()) Text(stringResource(R.string.no_surplus_to_use))
                PocketChips(editing.sources, editing.form.from, money, showBalance = true, onSelect = actions.onFrom)
            }
            FormSection(stringResource(R.string.move_to), stringResource(R.string.error_choose_pocket).takeIf { editing.missingTo }) {
                PocketChips(editing.destinations, editing.form.to, money, showBalance = false, onSelect = actions.onTo)
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
            NoteField(editing.form.note, actions.onNoteChange, actions.onSave)
            if (editing.failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
