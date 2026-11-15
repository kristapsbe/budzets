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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.util.amountInputChars
import lv.kristapsbe.budzets.util.formatForInput
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.YearMonth

/** A pocket offered in a picker, with its current balance. */
data class PocketChoice(val label: PocketLabel, val balance: Money)

data class MoveForm(
    val from: SurplusPocket? = null,
    val to: SurplusPocket? = null,
    val amountText: String = "",
    val note: String = "",
)

sealed interface MoveSurplusState {
    data object Loading : MoveSurplusState
    data object Done : MoveSurplusState
    data class Editing(
        val currency: CurrencySpec,
        val form: MoveForm,
        /** Pockets with surplus to move (positive balance). */
        val sources: List<PocketChoice>,
        /** Active categories, the unallocated pocket and any pocket in debt, except the source. */
        val destinations: List<PocketChoice>,
        val amountError: AmountError?,
        /** Set when the amount is more than the source has: its balance. */
        val exceedsAvailable: Money?,
        val missingFrom: Boolean,
        val missingTo: Boolean,
        val isSaving: Boolean = false,
        val failed: Boolean = false,
    ) : MoveSurplusState {
        val available: Money? get() = sources.firstOrNull { it.label.pocket == form.from }?.balance

        /** The chosen destination when it is in debt, so the form can say what it owes (Q43). */
        val payingOff: PocketChoice? get() = destinations.firstOrNull { it.label.pocket == form.to }?.takeIf { it.balance.isNegative }
    }
}

/**
 * Moves all or part of one pocket's surplus to another (spec §6.6). The ledger records both legs
 * in one transaction. Compose state for the text fields, mirrored into [SavedStateHandle], as in
 * the other editors. Route arguments, both pocket keys: `from` preselects the source; `to` pays
 * off a pocket in debt, preselecting it and filling in what it owes (Q43).
 */
class MoveSurplusViewModel(
    private val savedState: SavedStateHandle,
    private val surplus: SurplusRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) : ViewModel() {

    private data class Reference(val currency: CurrencySpec, val balances: Map<SurplusPocket, Money>, val categories: List<Category>)

    private var reference by mutableStateOf<Reference?>(null)
    private var attempted by mutableStateOf(savedState.get<Boolean>(KEY_ATTEMPTED) == true)
    private var saving by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var done by mutableStateOf(false)
    private var initialized = savedState.get<Boolean>(KEY_INITIALIZED) == true

    var form by mutableStateOf(
        MoveForm(
            from = savedState.get<String>(KEY_FROM)?.let(::pocketFromKey),
            to = savedState.get<String>(KEY_TO)?.let(::pocketFromKey),
            amountText = savedState[KEY_AMOUNT] ?: "",
            note = savedState[KEY_NOTE] ?: "",
        ),
    )
        private set

    val state: MoveSurplusState
        get() {
            if (done) return MoveSurplusState.Done
            val ref = reference ?: return MoveSurplusState.Loading
            val byId = ref.categories.associateBy { it.id }
            val order = pocketOrder(ref.categories)
            val sources = ref.balances.filterValues { it.isPositive }.keys.sortedWith(order)
                .map { PocketChoice(it.label(byId), ref.balances.getValue(it)) }
            val month = YearMonth.now(clock)
            // A pocket in debt can always be paid off, an archived category's included.
            val active = ref.categories.filter { !it.isDeleted && it.toRef().isActiveIn(month) }.map { SurplusPocket.Category(it.id) }
            val destinations = (active + SurplusPocket.Unallocated + SurplusLedger.debts(ref.balances).keys)
                .distinct()
                .filter { it != form.from }
                .sortedWith(order)
                .map { PocketChoice(it.label(byId), ref.balances[it] ?: Money.ZERO) }
            val parsed = AmountParser.parse(form.amountText, ref.currency)
            val amount = (parsed as? Outcome.Ok)?.value
            val available = sources.firstOrNull { it.label.pocket == form.from }?.balance
            val error = (parsed as? Outcome.Err)?.error
            return MoveSurplusState.Editing(
                currency = ref.currency,
                form = form,
                sources = sources,
                destinations = destinations,
                amountError = error?.takeIf { attempted || it == AmountError.TOO_MANY_DECIMALS || it == AmountError.TOO_LARGE },
                exceedsAvailable = available?.takeIf { amount != null && amount > it },
                missingFrom = attempted && form.from == null,
                missingTo = attempted && form.to == null,
                isSaving = saving,
                failed = failed,
            )
        }

    init {
        viewModelScope.launch {
            combine(settings.currency, surplus.balances, categories.categories, ::Reference).collect { ref ->
                reference = ref
                if (!initialized) {
                    // The pocket passed in, or the only pocket with surplus.
                    val positive = ref.balances.filterValues { it.isPositive }.keys
                    val from = savedState.get<String>(ARG_FROM)?.let(::pocketFromKey)?.takeIf { it in positive }
                        ?: positive.singleOrNull()
                    // Paying off: the pocket passed in, if it still owes, and what it owes, or as
                    // much of that as the source has.
                    val debts = SurplusLedger.debts(ref.balances)
                    val to = savedState.get<String>(ARG_TO)?.let(::pocketFromKey)?.takeIf { it in debts }
                    val amount = to?.let { debts.getValue(it) }?.let { owed -> from?.let { minOf(owed, ref.balances.getValue(it)) } ?: owed }
                    updateForm(
                        form.copy(
                            from = from,
                            to = to ?: form.to,
                            amountText = amount?.let { formatForInput(it, ref.currency, decimalSeparator) } ?: form.amountText,
                        ),
                    )
                    initialized = true
                    savedState[KEY_INITIALIZED] = true
                }
            }
        }
    }

    fun selectFrom(pocket: SurplusPocket) = updateForm(form.copy(from = pocket, to = form.to?.takeIf { it != pocket }))
    fun selectTo(pocket: SurplusPocket) = updateForm(form.copy(to = pocket))
    fun onAmountChange(text: String) = updateForm(form.copy(amountText = text.amountInputChars()))
    fun onNoteChange(text: String) = updateForm(form.copy(note = text))

    /** Fills in the source's whole balance ("move all"). */
    fun useAll() {
        val editing = state as? MoveSurplusState.Editing ?: return
        val available = editing.available ?: return
        updateForm(form.copy(amountText = formatForInput(available, editing.currency, decimalSeparator)))
    }

    fun save() {
        val editing = state as? MoveSurplusState.Editing ?: return
        if (saving) return
        attempted = true
        savedState[KEY_ATTEMPTED] = true
        val from = form.from ?: return
        val to = form.to ?: return
        val amount = (AmountParser.parse(form.amountText, editing.currency) as? Outcome.Ok)?.value ?: return
        val available = editing.available ?: return
        if (amount > available) return
        saving = true
        failed = false
        viewModelScope.launch {
            val result = runCatching { surplus.transfer(from, to, amount, form.note.trim().takeIf { it.isNotEmpty() }) }.getOrNull()
            saving = false
            // InsufficientSurplus can only mean the balance changed meanwhile; the form shows the new one.
            if (result is Outcome.Ok) done = true else failed = (result as? Outcome.Err)?.error !is SurplusError.InsufficientSurplus
        }
    }

    private fun updateForm(value: MoveForm) {
        form = value
        savedState[KEY_FROM] = value.from?.key()
        savedState[KEY_TO] = value.to?.key()
        savedState[KEY_AMOUNT] = value.amountText
        savedState[KEY_NOTE] = value.note
    }

    companion object {
        /** These two match [lv.kristapsbe.budzets.ui.navigation.MoveSurplusRoute]'s properties. */
        const val ARG_FROM = "from"
        const val ARG_TO = "to"
        private const val KEY_FROM = "form_from"
        private const val KEY_TO = "form_to"
        private const val KEY_AMOUNT = "form_amount"
        private const val KEY_NOTE = "form_note"
        private const val KEY_INITIALIZED = "form_initialized"
        private const val KEY_ATTEMPTED = "form_attempted"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                MoveSurplusViewModel(createSavedStateHandle(), container.surplus, container.categories, container.settings, container.clock)
            }
        }
    }
}
