package lv.kristapsbe.budzets.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.data.backup.BackupCodec
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.canSwitchCurrency
import lv.kristapsbe.budzets.ui.components.Banner
import lv.kristapsbe.budzets.ui.components.ErrorState
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.util.dateTimeText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val texts = SettingsMessage.entries.associateWith { stringResource(it.text()) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(texts.getValue(it))
            viewModel.messageShown(it)
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SettingsViewModel.BACKUP_MIME_TYPE)) { uri: Uri? ->
        viewModel.backUp(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        viewModel.openBackup(uri)
    }
    SettingsContent(
        state = state,
        actions = SettingsActions(
            onBackUp = { saveFile.launch(viewModel.backupFileName()) },
            onRestore = { openFile.launch(SettingsViewModel.BACKUP_MIME_TYPES) },
            onConfirmRestore = viewModel::confirmRestore,
            onCancelRestore = viewModel::cancelRestore,
            onUndoRestore = viewModel::undoRestore,
            onCurrency = viewModel::setCurrency,
            onOneOffNote = viewModel::setOneOffNote,
        ),
        snackbarHostState = snackbar,
    )
}

data class SettingsActions(
    val onBackUp: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onConfirmRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
    val onUndoRestore: () -> Unit = {},
    val onCurrency: (CurrencySpec) -> Unit = {},
    /** Blank clears the note. */
    val onOneOffNote: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            SettingsUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
            is SettingsUiState.Error -> ErrorState(stringResource(R.string.error_loading), Modifier.padding(padding))
            is SettingsUiState.Ready -> ReadyList(state, actions, padding, zone)
        }
    }
}

@Composable
private fun ReadyList(state: SettingsUiState.Ready, actions: SettingsActions, padding: PaddingValues, zone: ZoneId) {
    val locale = LocalConfiguration.current.locales[0]
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    var editNote by rememberSaveable { mutableStateOf(false) }
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val time = { at: Instant -> at.dateTimeText(zone, locale) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "backup") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.backup_explanation), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.lastBackupAt?.let { stringResource(R.string.backup_last, time(it)) } ?: stringResource(R.string.backup_never),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val idle = state.busy == null
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.onBackUp, enabled = idle, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup_now)) }
                        OutlinedButton(onClick = actions.onRestore, enabled = idle, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.restore_from_backup))
                        }
                    }
                    state.busy?.let { busy ->
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(busy.text()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        state.lastRestoreAt?.let { restoredAt ->
            item(key = "undo") {
                Banner(
                    icon = Icons.Filled.Info,
                    text = stringResource(R.string.restore_undo_note, time(restoredAt)),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    action = stringResource(R.string.undo_restore).takeIf { state.busy == null },
                    onAction = { confirmUndo = true },
                )
            }
        }
        item(key = "currency") {
            Section(R.string.settings_currency)
            ListItem(
                headlineContent = { Text(currencyName(state.currency)) },
                supportingContent = if (state.currencyLocked) {
                    { Text(pluralStringResource(R.plurals.currency_locked_note, state.currency.fractionDigits, state.currency.fractionDigits)) }
                } else {
                    null
                },
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.choose_currency)) { pickCurrency = true },
            )
        }
        item(key = "one-off-note") {
            Section(R.string.one_off_boosts)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_one_off_note)) },
                supportingContent = { Text(state.oneOffNote ?: stringResource(R.string.one_off_note_none)) },
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.one_off_note_title)) { editNote = true },
            )
        }
        item(key = "budgets") {
            Section(R.string.settings_how_budgets_work)
            Paragraph(stringResource(R.string.how_budgets_work_text))
        }
        item(key = "privacy") {
            Section(R.string.settings_privacy)
            Paragraph(stringResource(R.string.privacy_text))
        }
        item(key = "about") {
            Section(R.string.settings_about)
            Paragraph(stringResource(R.string.about_version, state.appVersion))
            Paragraph(stringResource(R.string.about_data_version, BudzetsDatabase.VERSION, BackupCodec.SCHEMA_VERSION))
        }
    }

    state.pendingRestore?.let { preview ->
        AlertDialog(
            onDismissRequest = actions.onCancelRestore,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { RestoreSummary(preview, time) },
            confirmButton = { TextButton(onClick = actions.onConfirmRestore) { Text(stringResource(R.string.restore_confirm_button)) } },
            dismissButton = { TextButton(onClick = actions.onCancelRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmUndo && state.lastRestoreAt != null) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text(stringResource(R.string.undo_restore_title)) },
            text = { Text(stringResource(R.string.undo_restore_text, time(state.lastRestoreAt))) },
            confirmButton = {
                TextButton(onClick = { confirmUndo = false; actions.onUndoRestore() }) { Text(stringResource(R.string.undo_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (editNote) {
        OneOffNoteDialog(
            initial = state.oneOffNote.orEmpty(),
            onConfirm = { editNote = false; actions.onOneOffNote(it) },
            onDismiss = { editNote = false },
        )
    }
    if (pickCurrency) {
        CurrencyPicker(
            current = state.currency,
            locked = state.currencyLocked,
            onPick = { pickCurrency = false; actions.onCurrency(it) },
            onDismiss = { pickCurrency = false },
        )
    }
}

@Composable
private fun RestoreSummary(preview: RestorePreview, time: (Instant) -> String) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.restore_confirm_created, time(preview.createdAt)), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.restore_confirm_contents,
                pluralStringResource(R.plurals.restore_categories, preview.categories, preview.categories),
                pluralStringResource(R.plurals.restore_expenses, preview.expenses, preview.expenses),
                pluralStringResource(R.plurals.restore_closed_months, preview.closedMonths, preview.closedMonths),
            )
        )
        if (preview.firstExpense != null && preview.lastExpense != null) {
            val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            Text(
                if (preview.firstExpense == preview.lastExpense) {
                    stringResource(R.string.restore_confirm_one_day, dates.format(preview.firstExpense))
                } else {
                    stringResource(R.string.restore_confirm_range, dates.format(preview.firstExpense), dates.format(preview.lastExpense))
                }
            )
        }
        Text(stringResource(R.string.restore_confirm_currency, currencyName(preview.currency)))
        Text(stringResource(R.string.restore_confirm_warning), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CurrencyPicker(current: CurrencySpec, locked: Boolean, onPick: (CurrencySpec) -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val options = remember(locale) { currencyOptions(locale) }
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_currency)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (locked) {
                    Text(
                        pluralStringResource(R.plurals.currency_locked_note, current.fractionDigits, current.fractionDigits),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_currencies)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options.filter { it.matches(query) }, key = { it.spec.code }) { option ->
                        val allowed = canSwitchCurrency(current, option.spec, locked)
                        val selected = option.spec.code == current.code
                        ListItem(
                            headlineContent = { Text("${option.name} (${option.spec.code})") },
                            supportingContent = if (allowed) {
                                null
                            } else {
                                { Text(pluralStringResource(R.plurals.currency_decimals, option.spec.fractionDigits, option.spec.fractionDigits)) }
                            },
                            leadingContent = { RadioButton(selected = selected, onClick = null, enabled = allowed) },
                            modifier = Modifier.selectable(selected = selected, enabled = allowed, role = Role.RadioButton) { onPick(option.spec) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Edits the text shown under the amount when adding a one-off boost (Q49). Saving it empty clears it. */
@Composable
private fun OneOffNoteDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Selected on open, so typing replaces it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val max = SettingsRepository.MAX_ONE_OFF_NOTE_LENGTH
    val tooLong = field.text.trim().length > max
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.one_off_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.one_off_note_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.one_off_note_label)) },
                    minLines = 2,
                    maxLines = 6,
                    isError = tooLong,
                    supportingText = { Text(stringResource(R.string.one_off_note_length, field.text.trim().length, max)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ONE_OFF_NOTE_FIELD_TAG),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(field.text) }, enabled = !tooLong) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

const val ONE_OFF_NOTE_FIELD_TAG = "one_off_note"

@Composable
private fun Section(title: Int) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun currencyName(currency: CurrencySpec): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.currency_value, Currency.getInstance(currency.code).getDisplayName(locale), currency.code)
}

private fun Busy.text(): Int = when (this) {
    Busy.BACKING_UP -> R.string.busy_backing_up
    Busy.READING -> R.string.busy_reading
    Busy.RESTORING -> R.string.busy_restoring
    Busy.UNDOING -> R.string.busy_undoing
}

private fun SettingsMessage.text(): Int = when (this) {
    SettingsMessage.BACKUP_SAVED -> R.string.msg_backup_saved
    SettingsMessage.BACKUP_NOT_CONFIRMED -> R.string.msg_backup_not_confirmed
    SettingsMessage.BACKUP_FAILED -> R.string.msg_backup_failed
    SettingsMessage.NOT_A_BACKUP -> R.string.msg_not_a_backup
    SettingsMessage.BACKUP_TOO_NEW -> R.string.msg_backup_too_new
    SettingsMessage.BACKUP_DAMAGED -> R.string.msg_backup_damaged
    SettingsMessage.FILE_TOO_LARGE -> R.string.msg_file_too_large
    SettingsMessage.FILE_UNREADABLE -> R.string.msg_file_unreadable
    SettingsMessage.RESTORED -> R.string.msg_restored
    SettingsMessage.RESTORE_FAILED -> R.string.msg_restore_failed
    SettingsMessage.UNDONE -> R.string.msg_undone
    SettingsMessage.UNDO_FAILED -> R.string.msg_undo_failed
    SettingsMessage.CURRENCY_REFUSED -> R.string.msg_currency_refused
}
