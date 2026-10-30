package lv.kristapsbe.budzets.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.categoryName
import lv.kristapsbe.budzets.ui.components.FormSection
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.ui.components.amountErrorText
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.dayName
import lv.kristapsbe.budzets.util.displayName
import lv.kristapsbe.budzets.util.pickerMillisToDate
import lv.kristapsbe.budzets.util.toPickerMillis
import java.time.LocalDate
import java.util.Locale

@Composable
fun ExpenseEditorScreen(
    onClose: () -> Unit,
    viewModel: ExpenseEditorViewModel = viewModel(factory = ExpenseEditorViewModel.Factory),
) {
    val state = viewModel.state
    if (state == ExpenseEditorState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    ExpenseEditorContent(
        state = state,
        actions = EditorActions(
            onAmountChange = viewModel::onAmountChange,
            onCategorySelect = viewModel::onCategorySelect,
            onDateSelect = viewModel::onDateSelect,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onClose = onClose,
        ),
    )
}

data class EditorActions(
    val onAmountChange: (String) -> Unit = {},
    val onCategorySelect: (CategoryId) -> Unit = {},
    val onDateSelect: (LocalDate) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditorContent(state: ExpenseEditorState, actions: EditorActions) {
    val editing = state as? ExpenseEditorState.Editing
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (editing?.isNew == false) R.string.edit_expense else R.string.add_expense)) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (editing != null) {
                        if (!editing.isNew) {
                            IconButton(onClick = { confirmDelete = true }, enabled = !editing.isSaving) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                        TextButton(onClick = actions.onSave, enabled = !editing.isSaving) { Text(stringResource(R.string.save)) }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ExpenseEditorState.Loading, ExpenseEditorState.Done -> LoadingIndicator(Modifier.padding(padding))
            ExpenseEditorState.NotFound -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.expense_not_found), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = actions.onClose) { Text(stringResource(R.string.close)) }
            }
            is ExpenseEditorState.Editing -> EditorForm(state, actions, Modifier.padding(padding))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_expense_title)) },
            text = { Text(stringResource(R.string.delete_expense_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EditorForm(state: ExpenseEditorState.Editing, actions: EditorActions, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }
    val form = state.form
    val amountFocus = remember { FocusRequester() }
    if (state.isNew) {
        LaunchedEffect(Unit) { amountFocus.requestFocus() }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (prefix, suffix) = money.inputAffixes
        OutlinedTextField(
            value = form.amountText,
            onValueChange = actions.onAmountChange,
            label = { Text(stringResource(R.string.amount)) },
            placeholder = { Text(money.inputPlaceholder) },
            prefix = prefix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            suffix = suffix.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.headlineMedium,
            singleLine = true,
            isError = state.errors.amount != null,
            supportingText = state.errors.amount?.let { { Text(amountErrorText(it, state.currency.fractionDigits)) } },
            // Done saves: amount + the preselected category is the whole common path.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus).testTag(AMOUNT_FIELD_TAG),
        )

        FormSection(stringResource(R.string.category), error = stringResource(R.string.error_category_missing).takeIf { state.errors.missingCategory }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { option ->
                    val selected = option.id == form.categoryId
                    FilterChip(
                        selected = selected,
                        onClick = { actions.onCategorySelect(option.id) },
                        label = { Text(categoryName(option.name, option.isArchived, option.isDeleted)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
        }

        FormSection(stringResource(R.string.date), error = stringResource(R.string.error_date_future).takeIf { state.errors.futureDate }) {
            DateChips(form.date, state.today, locale, actions.onDateSelect)
        }

        OutlinedTextField(
            value = form.note,
            onValueChange = actions.onNoteChange,
            label = { Text(stringResource(R.string.note_optional)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSave() }),
            modifier = Modifier.fillMaxWidth().testTag(NOTE_FIELD_TAG),
        )

        if (state.closedMonthsAffected.isNotEmpty()) {
            Banner(
                Icons.Filled.Lock,
                stringResource(R.string.closed_month_edit_hint, state.closedMonthsAffected.joinToString { it.displayName(locale) }),
            )
        }

        state.failure?.let {
            Text(
                stringResource(if (it == EditorFailure.SAVE) R.string.save_failed else R.string.delete_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChips(date: LocalDate, today: LocalDate, locale: Locale, onSelect: (LocalDate) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val yesterday = today.minusDays(1)
    val isOther = date != today && date != yesterday

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = date == today, onClick = { onSelect(today) }, label = { Text(stringResource(R.string.today)) })
        FilterChip(selected = date == yesterday, onClick = { onSelect(yesterday) }, label = { Text(stringResource(R.string.yesterday)) })
        FilterChip(
            selected = isOther,
            // Clearing focus closes the keyboard, which would otherwise cover half the picker.
            onClick = { focusManager.clearFocus(); showPicker = true },
            label = { Text(if (isOther) date.dayName(locale, today.year, short = true) else stringResource(R.string.pick_date)) },
            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }

    if (showPicker) {
        // Future dates are not selectable (Phase 4 review).
        val selectable = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !pickerMillisToDate(utcTimeMillis).isAfter(today)
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toPickerMillis(), selectableDates = selectable)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onSelect(pickerMillisToDate(it)) }
                        showPicker = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

const val AMOUNT_FIELD_TAG = "expense_amount"
const val NOTE_FIELD_TAG = "expense_note"
