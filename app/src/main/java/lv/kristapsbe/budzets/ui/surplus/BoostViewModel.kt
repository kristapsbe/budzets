package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.util.amountInputChars
import lv.kristapsbe.budzets.util.formatForInput
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.YearMonth

data class BoostForm(
    val destination: CategoryId? = null,
    val source: SurplusPocket? = null,
    val amountText: String = "",
    val note: String = "",
)

sealed interface BoostState {
    data object Loading : BoostState
    data object Done : BoostState
    data class Editing(
        val currency: CurrencySpec,
        val form: BoostForm,
        val month: YearMonth,
        val isCurrentMonth: Boolean,
        /** A boost for a closed month would change its result, so it's refused (Q4). */
        val monthClosed: Boolean,
        /** Categories active in [month]. */
        val destinations: List<PocketChoice>,
        /** Pockets with surplus to use (positive balance). */
        val sources: List<PocketChoice>,
        /** The destination's budget for [month] before this boost (base + existing boosts). */
        val currentBudget: Money?,
        val amount: Money?,
        val amountError: AmountError?,
        val exceedsAvailable: Money?,
        val missingDestination: Boolean,
        val missingSource: Boolean,
        val isSaving: Boolean = false,
        val failed: Boolean = false,
    ) : BoostState {
        val available: Money? get() = sources.firstOrNull { it.label.pocket == form.source }?.balance
    }
}

/**
 * Raises one category's budget for one month with saved surplus (spec §6.7). Only the current
 * month and later months that aren't closed can be boosted; the amount is reserved in the
 * ledger, so it can't be used twice. Route arguments: `categoryId`, `month`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoostViewModel(
    private val savedState: SavedStateHandle,
    private val surplus: SurplusRepository,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) : ViewModel() {

    private val current = YearMonth.now(clock)
    private val selectedMonth = MutableStateFlow(
        (savedState.get<String>(KEY_MONTH) ?: savedState.get<String>(ARG_MONTH))?.let(YearMonth::parse)?.coerceAtLeast(current) ?: current,
    )

    private data class Reference(
        val currency: CurrencySpec,
        val balances: Map<SurplusPocket, Money>,
        val categories: List<Category>,
        val closed: Set<YearMonth>,
        val summary: MonthSummary,
    )

    private var reference by mutableStateOf<Reference?>(null)
    private var month by mutableStateOf(selectedMonth.value)
    private var attempted by mutableStateOf(savedState.get<Boolean>(KEY_ATTEMPTED) == true)
    private var saving by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var done by mutableStateOf(false)
    private var initialized = savedState.get<Boolean>(KEY_INITIALIZED) == true

    var form by mutableStateOf(
        BoostForm(
            destination = savedState.get<String>(KEY_DESTINATION)?.let(::CategoryId),
            source = savedState.get<String>(KEY_SOURCE)?.let(::pocketFromKey),
            amountText = savedState[KEY_AMOUNT] ?: "",
            note = savedState[KEY_NOTE] ?: "",
        ),
    )
        private set

    val state: BoostState
        get() {
            if (done) return BoostState.Done
            val ref = reference?.takeIf { it.summary.month == month } ?: return BoostState.Loading
            val byId = ref.categories.associateBy { it.id }
            val sources = ref.balances.filterValues { it.isPositive }.keys.sortedWith(pocketOrder(ref.categories))
                .map { PocketChoice(it.label(byId), ref.balances.getValue(it)) }
            val destinations = ref.categories.filter { it.toRef().isActiveIn(month) }.map { c ->
                PocketChoice(SurplusPocket.Category(c.id).label(byId), ref.balances[SurplusPocket.Category(c.id)] ?: Money.ZERO)
            }
            val parsed = AmountParser.parse(form.amountText, ref.currency)
            val amount = (parsed as? Outcome.Ok)?.value
            val available = sources.firstOrNull { it.label.pocket == form.source }?.balance
            val error = (parsed as? Outcome.Err)?.error
            return BoostState.Editing(
                currency = ref.currency,
                form = form,
                month = month,
                isCurrentMonth = month == current,
                monthClosed = month in ref.closed,
                destinations = destinations,
                sources = sources,
                currentBudget = form.destination?.let { ref.summary.categories[it]?.effective ?: Money.ZERO },
                amount = amount,
                amountError = error?.takeIf { attempted || it == AmountError.TOO_MANY_DECIMALS || it == AmountError.TOO_LARGE },
                exceedsAvailable = available?.takeIf { amount != null && amount > it },
                missingDestination = attempted && form.destination == null,
                missingSource = attempted && form.source == null,
                isSaving = saving,
                failed = failed,
            )
        }

    init {
        viewModelScope.launch {
            selectedMonth.flatMapLatest { m ->
                combine(settings.currency, surplus.balances, categories.categories, surplus.closedMonths, budgets.observeMonthSummary(m), ::Reference)
            }.collect { ref ->
                reference = ref
                if (!initialized) initialize(ref)
            }
        }
    }

    /** Preselects the category passed in, and as source its own pocket or else the only one with surplus. */
    private fun initialize(ref: Reference) {
        val positive = ref.balances.filterValues { it.isPositive }.keys
        val destination = savedState.get<String>(ARG_CATEGORY_ID)?.let(::CategoryId)?.takeIf { id -> ref.categories.any { it.id == id && !it.isArchived } }
        val source = destination?.let { SurplusPocket.Category(it) }?.takeIf { it in positive } ?: positive.singleOrNull()
        updateForm(form.copy(destination = destination, source = source))
        initialized = true
        savedState[KEY_INITIALIZED] = true
    }

    fun previousMonth() = changeMonth(month.minusMonths(1).coerceAtLeast(current))
    fun nextMonth() = changeMonth(month.plusMonths(1))
    fun goToCurrentMonth() = changeMonth(current)

    private fun changeMonth(value: YearMonth) {
        month = value
        selectedMonth.value = value
        savedState[KEY_MONTH] = value.toString()
    }

    fun selectDestination(id: CategoryId) = updateForm(form.copy(destination = id))
    fun selectSource(pocket: SurplusPocket) = updateForm(form.copy(source = pocket))
    fun onAmountChange(text: String) = updateForm(form.copy(amountText = text.amountInputChars()))
    fun onNoteChange(text: String) = updateForm(form.copy(note = text))

    fun useAll() {
        val editing = state as? BoostState.Editing ?: return
        val available = editing.available ?: return
        updateForm(form.copy(amountText = formatForInput(available, editing.currency, decimalSeparator)))
    }

    fun save() {
        val editing = state as? BoostState.Editing ?: return
        if (saving || editing.monthClosed) return
        attempted = true
        savedState[KEY_ATTEMPTED] = true
        val destination = form.destination ?: return
        val source = form.source ?: return
        val amount = editing.amount ?: return
        val available = editing.available ?: return
        if (amount > available) return
        saving = true
        failed = false
        viewModelScope.launch {
            val result = runCatching {
                surplus.allocateBoost(source, destination, editing.month, amount, form.note.trim().takeIf { it.isNotEmpty() })
            }.getOrNull()
            saving = false
            if (result is Outcome.Ok) done = true else failed = (result as? Outcome.Err)?.error !is SurplusError.InsufficientSurplus
        }
    }

    private fun updateForm(value: BoostForm) {
        form = value
        savedState[KEY_DESTINATION] = value.destination?.value
        savedState[KEY_SOURCE] = value.source?.key()
        savedState[KEY_AMOUNT] = value.amountText
        savedState[KEY_NOTE] = value.note
    }

    companion object {
        /** Match [lv.kristapsbe.budzets.ui.navigation.BoostRoute]'s properties. */
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_MONTH = "month"
        private const val KEY_MONTH = "form_month"
        private const val KEY_DESTINATION = "form_destination"
        private const val KEY_SOURCE = "form_source"
        private const val KEY_AMOUNT = "form_amount"
        private const val KEY_NOTE = "form_note"
        private const val KEY_INITIALIZED = "form_initialized"
        private const val KEY_ATTEMPTED = "form_attempted"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                BoostViewModel(createSavedStateHandle(), container.surplus, container.budgets, container.categories, container.settings, container.clock)
            }
        }
    }
}
