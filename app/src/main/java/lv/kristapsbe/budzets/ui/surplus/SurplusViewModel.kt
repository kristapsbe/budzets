package lv.kristapsbe.budzets.ui.surplus

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.LedgerEntryType
import lv.kristapsbe.budzets.domain.surplus.MonthFinalizer
import lv.kristapsbe.budzets.domain.surplus.PocketBreakdown
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

data class PocketRow(val label: PocketLabel, val breakdown: PocketBreakdown) {
    /** A category that can be given surplus (Q46): not Unallocated, archived or deleted. */
    val canAddTo: Boolean get() = label.name != null && !label.isArchived && !label.isDeleted
}

data class BoostRow(
    val id: BoostId,
    val month: YearMonth,
    val destination: PocketLabel,
    val source: PocketLabel,
    val amount: Money,
    /** A boost for a closed month is part of that month's result and can't be released. */
    val canRelease: Boolean,
)

/** A one-off boost for a month that isn't closed; [id] is its credit entry. */
data class OneOffRow(val id: LedgerEntryId, val month: YearMonth, val amount: Money, val note: String?)

/** What a ledger entry was, in the user's terms. */
enum class HistoryKind {
    CLOSE_CREDIT, CLOSE_DEBIT, RECLOSE_ADJUSTMENT, REOPEN, MOVED_OUT, MOVED_IN, BOOST_RESERVED, BOOST_RELEASED, ONE_OFF_ADDED, ONE_OFF_REMOVED
}

data class HistoryRow(
    val id: LedgerEntryId,
    val kind: HistoryKind,
    val date: LocalDate,
    val pocket: PocketLabel,
    /** Signed: what the entry did to [pocket]'s balance. */
    val amount: Money,
    /** The month a closing entry came from, or the month a boost or one-off boost applies to. */
    val month: YearMonth?,
    /** The other side of a transfer, or the boosted category. */
    val counterpart: PocketLabel?,
    val note: String?,
)

sealed interface SurplusUiState {
    data object Loading : SurplusUiState
    data class Error(val message: String?) : SurplusUiState
    data class Ready(
        val currency: CurrencySpec,
        /** The pockets above zero, added up. */
        val saved: Money,
        /** What the pockets below zero owe in all (Q43). Kept apart from [saved], not netted. */
        val debt: Money = Money.ZERO,
        val pockets: List<PocketRow>,
        val boosts: List<BoostRow>,
        val oneOffs: List<OneOffRow> = emptyList(),
        /** Newest first; only [filter]'s pocket when one is selected. */
        val history: List<HistoryRow>,
        val filter: SurplusPocket?,
        /** Some pocket has a positive balance, so surplus can be moved or used for a boost. */
        val hasAvailable: Boolean,
    ) : SurplusUiState
}

enum class SurplusMessage { BOOST_RELEASED, ONE_OFF_REMOVED, ONE_OFF_USED, FAILED }

/**
 * Saved surplus per pocket, active boosts and the ledger history. Deleted categories' money counts
 * as Unallocated, while their history rows keep the category's name. The history filter (a pocket
 * key) lives in [SavedStateHandle].
 */
class SurplusViewModel(
    private val savedState: SavedStateHandle,
    private val surplus: SurplusRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val filterKey = savedState.getStateFlow<String?>(KEY_FILTER, null)
    private val _message = MutableStateFlow<SurplusMessage?>(null)
    val message: StateFlow<SurplusMessage?> = _message.asStateFlow()

    val uiState: StateFlow<SurplusUiState> =
        combine(surplus.ledger, surplus.boosts, surplus.closedMonths, categories.categories, settings.currency) { ledger, boosts, closed, all, currency ->
            Triple(ledger, boosts, closed) to (all to currency)
        }
            .combine(filterKey) { (data, meta), key ->
                val (ledger, boosts, closed) = data
                val (all, currency) = meta
                val byId = all.associateBy { it.id }
                val order = pocketOrder(all)
                val deleted = all.filter { it.isDeleted }.map { it.id }.toSet()
                val breakdown = SurplusLedger.breakdown(SurplusLedger.merged(ledger, deleted))
                val filter = key?.let(::pocketFromKey)?.let { SurplusLedger.effectivePocket(it, deleted) }
                val balances = breakdown.mapValues { (_, b) -> b.available }
                SurplusUiState.Ready(
                    currency = currency,
                    saved = SurplusLedger.saved(balances),
                    debt = SurplusLedger.owed(balances),
                    pockets = breakdown.keys.sortedWith(order).map { PocketRow(it.label(byId), breakdown.getValue(it)) },
                    boosts = boosts.filter { it.status == BoostStatus.ACTIVE }.sortedBy { it.targetMonth }.map { b ->
                        BoostRow(b.id, b.targetMonth, SurplusPocket.Category(b.destination).label(byId), b.source.label(byId), b.amount, b.targetMonth !in closed)
                    },
                    oneOffs = SurplusLedger.activeOneOffs(ledger).filter { it.targetMonth !in closed }.sortedBy { it.targetMonth }.map {
                        OneOffRow(it.id, requireNotNull(it.targetMonth), it.amount, it.note)
                    },
                    history = history(ledger, boosts, byId, filter, deleted),
                    filter = filter,
                    hasAvailable = breakdown.values.any { it.available.isPositive },
                ) as SurplusUiState
            }
            .catch { emit(SurplusUiState.Error(it.message)) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SurplusUiState.Loading)

    private fun history(
        ledger: List<LedgerEntry>,
        boosts: List<Boost>,
        byId: Map<CategoryId, Category>,
        filter: SurplusPocket?,
        deleted: Set<CategoryId>,
    ): List<HistoryRow> {
        val entries = ledger.associateBy { it.id }
        val boostById = boosts.associateBy { it.id }
        val zone = clock.zone
        return ledger.asReversed()
            .filter { filter == null || SurplusLedger.effectivePocket(it.pocket, deleted) == filter }
            .map { e ->
                val kind = when (e.type) {
                    LedgerEntryType.FINALIZE_CREDIT -> HistoryKind.CLOSE_CREDIT
                    LedgerEntryType.FINALIZE_DEBIT -> HistoryKind.CLOSE_DEBIT
                    LedgerEntryType.ADJUSTMENT_CREDIT, LedgerEntryType.ADJUSTMENT_DEBIT ->
                        if (e.note == MonthFinalizer.REOPEN_NOTE) HistoryKind.REOPEN else HistoryKind.RECLOSE_ADJUSTMENT
                    LedgerEntryType.TRANSFER_OUT -> HistoryKind.MOVED_OUT
                    LedgerEntryType.TRANSFER_IN -> HistoryKind.MOVED_IN
                    LedgerEntryType.BOOST_RESERVE -> HistoryKind.BOOST_RESERVED
                    LedgerEntryType.BOOST_RELEASE -> HistoryKind.BOOST_RELEASED
                    LedgerEntryType.ONE_OFF_CREDIT -> HistoryKind.ONE_OFF_ADDED
                    LedgerEntryType.ONE_OFF_REVERSAL -> HistoryKind.ONE_OFF_REMOVED
                }
                val counterpart = when (kind) {
                    HistoryKind.MOVED_OUT, HistoryKind.MOVED_IN -> e.relatedEntryId?.let(entries::get)?.pocket?.label(byId)
                    HistoryKind.BOOST_RESERVED, HistoryKind.BOOST_RELEASED ->
                        e.boostId?.let(boostById::get)?.let { SurplusPocket.Category(it.destination).label(byId) }
                    else -> null
                }
                HistoryRow(
                    id = e.id,
                    kind = kind,
                    date = e.createdAt.atZone(zone).toLocalDate(),
                    pocket = e.pocket.label(byId),
                    amount = e.signedAmount,
                    month = e.sourceMonth ?: e.targetMonth,
                    counterpart = counterpart,
                    // The reopen marker is shown through the kind, not as a note.
                    note = e.note?.takeIf { kind != HistoryKind.REOPEN },
                )
            }
    }

    fun setFilter(pocket: SurplusPocket?) {
        savedState[KEY_FILTER] = pocket?.key()
    }

    fun releaseBoost(id: BoostId) {
        viewModelScope.launch {
            val result = runCatching { surplus.releaseBoost(id) }.getOrNull()
            _message.value = if (result is Outcome.Ok) SurplusMessage.BOOST_RELEASED else SurplusMessage.FAILED
        }
    }

    fun removeOneOff(id: LedgerEntryId) {
        viewModelScope.launch {
            val result = runCatching { surplus.removeOneOff(id) }.getOrNull()
            _message.value = when {
                result is Outcome.Ok -> SurplusMessage.ONE_OFF_REMOVED
                (result as? Outcome.Err)?.error is SurplusError.InsufficientSurplus -> SurplusMessage.ONE_OFF_USED
                else -> SurplusMessage.FAILED
            }
        }
    }

    fun messageShown(message: SurplusMessage) {
        _message.compareAndSet(message, null)
    }

    companion object {
        private const val KEY_FILTER = "pocket"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                SurplusViewModel(createSavedStateHandle(), container.surplus, container.categories, container.settings, container.clock)
            }
        }
    }
}
