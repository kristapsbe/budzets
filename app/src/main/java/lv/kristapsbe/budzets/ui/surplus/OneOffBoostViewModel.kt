package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.container
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.ui.components.showsWhileTyping
import lv.kristapsbe.budzets.util.amountInputChars
import java.time.Clock
import java.time.YearMonth

data class OneOffForm(val amountText: String = "", val note: String = "")

sealed interface OneOffBoostState {
    data object Loading : OneOffBoostState
    data object Done : OneOffBoostState
    data class Editing(
        val currency: CurrencySpec,
        val form: OneOffForm,
        val month: YearMonth,
        val isCurrentMonth: Boolean,
        /** A closed month stays as it was closed, so it can't take a boost. */
        val monthClosed: Boolean,
        val amountError: AmountError?,
        val isSaving: Boolean = false,
        val failed: Boolean = false,
        /** The user's own text from Settings, shown under the amount (Q49); null when not set. */
        val infoNote: String? = null,
    ) : OneOffBoostState
}

/**
 * Adds money from outside the budget to a month. It goes straight into the unallocated pocket,
 * so unlike [BoostViewModel] there is no source or category to choose, and earlier months that
 * are still open can take one too.
 */
class OneOffBoostViewModel(
    private val savedState: SavedStateHandle,
    private val surplus: SurplusRepository,
    private val settings: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private val current = YearMonth.now(clock)

    private data class Reference(val currency: CurrencySpec, val closed: Set<YearMonth>, val infoNote: String?)

    private var reference by mutableStateOf<Reference?>(null)
    private var month by mutableStateOf(savedState.get<String>(KEY_MONTH)?.let(YearMonth::parse) ?: current)
    private var attempted by mutableStateOf(savedState.get<Boolean>(KEY_ATTEMPTED) == true)
    private var saving by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var done by mutableStateOf(false)

    var form by mutableStateOf(OneOffForm(savedState[KEY_AMOUNT] ?: "", savedState[KEY_NOTE] ?: ""))
        private set

    val state: OneOffBoostState
        get() {
            if (done) return OneOffBoostState.Done
            val ref = reference ?: return OneOffBoostState.Loading
            val error = (AmountParser.parse(form.amountText, ref.currency) as? Outcome.Err)?.error
            return OneOffBoostState.Editing(
                currency = ref.currency,
                form = form,
                month = month,
                isCurrentMonth = month == current,
                monthClosed = month in ref.closed,
                amountError = error?.takeIf { attempted || it.showsWhileTyping },
                isSaving = saving,
                failed = failed,
                infoNote = ref.infoNote,
            )
        }

    init {
        viewModelScope.launch {
            combine(settings.currency, surplus.closedMonths, settings.oneOffNote, ::Reference).collect { reference = it }
        }
    }

    fun previousMonth() = changeMonth(month.minusMonths(1))
    fun nextMonth() = changeMonth(month.plusMonths(1))
    fun goToCurrentMonth() = changeMonth(current)

    private fun changeMonth(value: YearMonth) {
        month = value
        savedState[KEY_MONTH] = value.toString()
    }

    fun onAmountChange(text: String) = updateForm(form.copy(amountText = text.amountInputChars()))
    fun onNoteChange(text: String) = updateForm(form.copy(note = text))

    fun save() {
        val editing = state as? OneOffBoostState.Editing ?: return
        if (saving || editing.monthClosed) return
        attempted = true
        savedState[KEY_ATTEMPTED] = true
        val amount = (AmountParser.parse(form.amountText, editing.currency) as? Outcome.Ok)?.value ?: return
        saving = true
        failed = false
        viewModelScope.launch {
            val result = runCatching { surplus.addOneOff(editing.month, amount, form.note.trim().takeIf { it.isNotEmpty() }) }.getOrNull()
            saving = false
            if (result is Outcome.Ok) done = true else failed = true
        }
    }

    private fun updateForm(value: OneOffForm) {
        form = value
        savedState[KEY_AMOUNT] = value.amountText
        savedState[KEY_NOTE] = value.note
    }

    companion object {
        private const val KEY_MONTH = "form_month"
        private const val KEY_AMOUNT = "form_amount"
        private const val KEY_NOTE = "form_note"
        private const val KEY_ATTEMPTED = "form_attempted"

        val Factory = viewModelFactory {
            initializer {
                OneOffBoostViewModel(createSavedStateHandle(), container.surplus, container.settings, container.clock)
            }
        }
    }
}
