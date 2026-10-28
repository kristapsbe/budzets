package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.ui.components.LoadingIndicator
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.dayName
import lv.kristapsbe.budzets.util.displayName
import java.time.LocalDate
import java.util.Locale

@Composable
fun SurplusScreen(
    onMove: (SurplusPocket?) -> Unit,
    onBoost: () -> Unit,
    viewModel: SurplusViewModel = viewModel(factory = SurplusViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val texts = mapOf(
        SurplusMessage.BOOST_RELEASED to stringResource(R.string.boost_released),
        SurplusMessage.FAILED to stringResource(R.string.action_failed),
    )
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(texts.getValue(it))
            viewModel.messageShown(it)
        }
    }
    SurplusContent(
        state = state,
        actions = SurplusActions(onMove = onMove, onBoost = onBoost, onFilter = viewModel::setFilter, onRelease = viewModel::releaseBoost),
        snackbarHostState = snackbar,
    )
}

data class SurplusActions(
    val onMove: (SurplusPocket?) -> Unit = {},
    val onBoost: () -> Unit = {},
    val onFilter: (SurplusPocket?) -> Unit = {},
    val onRelease: (BoostId) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurplusContent(
    state: SurplusUiState,
    actions: SurplusActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_surplus)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            SurplusUiState.Loading -> LoadingIndicator(Modifier.padding(padding))
            is SurplusUiState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
                Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
            }
            is SurplusUiState.Ready -> ReadyList(state, actions, padding)
        }
    }
}

@Composable
private fun ReadyList(state: SurplusUiState.Ready, actions: SurplusActions, padding: PaddingValues) {
    val locale = LocalConfiguration.current.locales[0]
    val money = remember(state.currency, locale) { MoneyFormatter(state.currency, locale) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "total") {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.saved_surplus), style = MaterialTheme.typography.titleMedium)
                    Text(money.format(state.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.saved_surplus_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onMove(state.filter) }, enabled = state.hasAvailable, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.move_surplus))
                }
                Button(onClick = actions.onBoost, enabled = state.hasAvailable, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.boost_a_budget))
                }
            }
        }

        if (state.pockets.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.surplus_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@LazyColumn
        }

        item(key = "pockets-header") { Header(stringResource(R.string.surplus_by_category)) }
        items(state.pockets, key = { "pocket-${it.label.pocket.key()}" }) { row -> PocketItem(row, money) }

        if (state.boosts.isNotEmpty()) {
            item(key = "boosts-header") { Header(stringResource(R.string.active_boosts)) }
            items(state.boosts, key = { "boost-${it.id.value}" }) { row -> BoostItem(row, money, locale, actions.onRelease) }
        }

        item(key = "history-header") { Header(stringResource(R.string.surplus_history)) }
        item(key = "history-filter") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = state.filter == null, onClick = { actions.onFilter(null) }, label = { Text(stringResource(R.string.all_categories)) }) }
                items(state.pockets, key = { "chip-${it.label.pocket.key()}" }) { row ->
                    FilterChip(
                        selected = state.filter == row.label.pocket,
                        onClick = { actions.onFilter(row.label.pocket) },
                        label = { Text(row.label.text()) },
                    )
                }
            }
        }
        items(state.history, key = { "entry-${it.id.value}" }) { row -> HistoryItem(row, money, locale, showPocket = state.filter == null) }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp).semantics { heading() })
}

@Composable
private fun PocketItem(row: PocketRow, money: MoneyFormatter) {
    val b = row.breakdown
    val parts = listOfNotNull(
        b.fromClosing.takeIf { !it.isZero }?.let { stringResource(R.string.breakdown_closing, signed(it, money)) },
        b.moved.takeIf { !it.isZero }?.let { stringResource(R.string.breakdown_moved, signed(it, money)) },
        b.boosts.takeIf { !it.isZero }?.let { stringResource(R.string.breakdown_boosts, signed(it, money)) },
    )
    ListItem(
        headlineContent = { Text(row.label.text()) },
        supportingContent = {
            Column {
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                if (b.available.isNegative) Text(stringResource(R.string.pocket_negative_hint), color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            Text(
                if (b.available.isNegative) stringResource(R.string.amount_debit, money.format(-b.available)) else money.format(b.available),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (b.available.isNegative) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
    )
}

@Composable
private fun BoostItem(row: BoostRow, money: MoneyFormatter, locale: Locale, onRelease: (BoostId) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.boost_row, money.format(row.amount), row.destination.text(), row.month.displayName(locale))) },
        supportingContent = { Text(stringResource(R.string.boost_from, row.source.text())) },
        trailingContent = {
            if (row.canRelease) TextButton(onClick = { onRelease(row.id) }) { Text(stringResource(R.string.release_boost)) }
        },
    )
}

@Composable
private fun HistoryItem(row: HistoryRow, money: MoneyFormatter, locale: Locale, showPocket: Boolean) {
    val month = row.month?.displayName(locale).orEmpty()
    val other = row.counterpart?.text().orEmpty()
    val title = when (row.kind) {
        HistoryKind.CLOSE_CREDIT -> stringResource(R.string.history_close_credit, month)
        HistoryKind.CLOSE_DEBIT -> stringResource(R.string.history_close_debit, month)
        HistoryKind.RECLOSE_ADJUSTMENT -> stringResource(R.string.history_reclose, month)
        HistoryKind.REOPEN -> stringResource(R.string.history_reopen, month)
        HistoryKind.MOVED_OUT -> stringResource(R.string.history_moved_out, other)
        HistoryKind.MOVED_IN -> stringResource(R.string.history_moved_in, other)
        HistoryKind.BOOST_RESERVED -> stringResource(R.string.history_boost, other, month)
        HistoryKind.BOOST_RELEASED -> stringResource(R.string.history_boost_released, other, month)
    }
    val details = listOfNotNull(
        row.date.dayName(locale, LocalDate.now().year, short = true),
        row.pocket.text().takeIf { showPocket },
        row.note,
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(details.joinToString(" · ")) },
        trailingContent = {
            Text(
                signed(row.amount, money),
                fontWeight = FontWeight.SemiBold,
                color = if (row.amount.isNegative) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
    )
}

/** "+€10.00" / "−€5.00": the sign is always written out. */
@Composable
private fun signed(amount: Money, money: MoneyFormatter): String = when {
    amount.isNegative -> "−" + money.format(-amount)
    amount.isPositive -> "+" + money.format(amount)
    else -> money.format(amount)
}
