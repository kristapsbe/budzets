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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.MonthSelector
import lv.kristapsbe.budzets.ui.components.rememberMoneyFormatter
import lv.kristapsbe.budzets.util.displayName

@Composable
fun OneOffBoostScreen(
    onClose: () -> Unit,
    viewModel: OneOffBoostViewModel = viewModel(factory = OneOffBoostViewModel.Factory),
) {
    val state = viewModel.state
    if (state == OneOffBoostState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    OneOffBoostContent(
        state,
        OneOffBoostActions(
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onCurrentMonth = viewModel::goToCurrentMonth,
            onAmountChange = viewModel::onAmountChange,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::save,
            onClose = onClose,
        ),
    )
}

data class OneOffBoostActions(
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onCurrentMonth: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneOffBoostContent(state: OneOffBoostState, actions: OneOffBoostActions) {
    val editing = state as? OneOffBoostState.Editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_one_off_boost)) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    if (editing != null) {
                        TextButton(onClick = actions.onSave, enabled = !editing.isSaving && !editing.monthClosed) { Text(stringResource(R.string.one_off_add)) }
                    }
                },
            )
        },
    ) { padding ->
        if (editing == null) {
            LoadingIndicator(Modifier.padding(padding))
            return@Scaffold
        }
        val locale = LocalConfiguration.current.locales[0]
        val money = rememberMoneyFormatter(editing.currency)
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.one_off_explanation), style = MaterialTheme.typography.bodyMedium)
            MonthSelector(editing.month, editing.isCurrentMonth, actions.onPreviousMonth, actions.onNextMonth, actions.onCurrentMonth)
            if (editing.monthClosed) {
                Banner(Icons.Filled.Lock, stringResource(R.string.one_off_month_closed, editing.month.displayName(locale)), warning = true)
            }
            // The user's note sits right under the field, closer than the form's other parts (Q49).
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AmountWithAll(
                    value = editing.form.amountText,
                    onChange = actions.onAmountChange,
                    error = editing.amountError,
                    exceedsAvailable = null,
                    available = null,
                    money = money,
                    fractionDigits = editing.currency.fractionDigits,
                    onAll = {},
                    onDone = actions.onSave,
                )
                editing.infoNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp).testTag(ONE_OFF_INFO_NOTE_TAG),
                    )
                }
            }
            NoteField(editing.form.note, actions.onNoteChange, actions.onSave)
            if (editing.failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

const val ONE_OFF_INFO_NOTE_TAG = "one_off_info_note"
