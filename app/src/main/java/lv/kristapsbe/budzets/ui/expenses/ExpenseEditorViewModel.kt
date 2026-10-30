package lv.kristapsbe.budzets.ui.expenses

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.expense.DraftErrors
import lv.kristapsbe.budzets.domain.expense.ExpenseDraft
import lv.kristapsbe.budzets.domain.expense.validate
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Expense
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.util.amountInputChars
import lv.kristapsbe.budzets.util.formatForInput
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

data class ExpenseForm(
    val amountText: String = "",
    val categoryId: CategoryId? = null,
    val date: LocalDate,
    val note: String = "",
) {
    fun toDraft() = ExpenseDraft(amountText, categoryId, date, note)
}

data class CategoryOption(val id: CategoryId, val name: String, val isArchived: Boolean, val isDeleted: Boolean = false)

enum class EditorFailure { SAVE, DELETE }

sealed interface ExpenseEditorState {
    data object Loading : ExpenseEditorState

    /** The expense to edit no longer exists. */
    data object NotFound : ExpenseEditorState

    data class Editing(
        val isNew: Boolean,
        val form: ExpenseForm,
        val currency: CurrencySpec,
        val categories: List<CategoryOption>,
        val today: LocalDate,
        /** Closed months this save changes: the chosen date's month and, when editing, the original one. */
        val closedMonthsAffected: List<YearMonth>,
        /** Only the errors worth showing right now (see [ExpenseEditorViewModel.visibleErrors]). */
        val errors: DraftErrors,
        val isSaving: Boolean = false,
        val failure: EditorFailure? = null,
    ) : ExpenseEditorState

    /** Saved or deleted; the screen should close. */
    data object Done : ExpenseEditorState
}

/**
 * Add or edit one expense. Unlike other ViewModels this exposes Compose snapshot state rather than
 * a StateFlow: text-field values must update synchronously, or typing can lose characters and
 * move the cursor. The form is mirrored into [SavedStateHandle] so it survives process death.
 *
 * Route arguments (also in [savedState]): `expenseId` to edit, `categoryId` to preselect.
 */
class ExpenseEditorViewModel(
    private val savedState: SavedStateHandle,
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val surplus: SurplusRepository,
    private val clock: Clock,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) : ViewModel() {

    private val expenseId = savedState.get<String>(ARG_EXPENSE_ID)?.let(::ExpenseId)

    private data class Reference(val currency: CurrencySpec, val categories: List<Category>, val closedMonths: Set<YearMonth>)

    private var reference by mutableStateOf<Reference?>(null)
    private var original by mutableStateOf<Expense?>(null)
    private var notFound by mutableStateOf(false)
    private var formReady by mutableStateOf(savedState.get<Boolean>(KEY_INITIALIZED) == true)
    private var attemptedSave by mutableStateOf(savedState.get<Boolean>(KEY_ATTEMPTED) == true)
    private var saving by mutableStateOf(false)
    private var failure by mutableStateOf<EditorFailure?>(null)
    private var done by mutableStateOf(false)

    var form by mutableStateOf(restoreForm())
        private set

    val state: ExpenseEditorState
        get() {
            if (done) return ExpenseEditorState.Done
            if (notFound) return ExpenseEditorState.NotFound
            val ref = reference
            if (ref == null || !formReady || (expenseId != null && original == null)) return ExpenseEditorState.Loading
            val today = LocalDate.now(clock)
            return ExpenseEditorState.Editing(
                isNew = expenseId == null,
                form = form,
                currency = ref.currency,
                categories = options(ref.categories),
                today = today,
                closedMonthsAffected = listOfNotNull(YearMonth.from(form.date), original?.date?.let(YearMonth::from))
                    .distinct().filter { it in ref.closedMonths }.sorted(),
                errors = visibleErrors(ref.currency, today),
                isSaving = saving,
                failure = failure,
            )
        }

    init {
        viewModelScope.launch {
            combine(settings.currency, categories.categories, surplus.closedMonths, ::Reference).collect { reference = it }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (expenseId != null) {
            val expense = expenses.get(expenseId)
            if (expense == null) {
                notFound = true
                return
            }
            original = expense
            if (!formReady) {
                val currency = settings.currency.first()
                updateForm(
                    ExpenseForm(
                        amountText = formatForInput(expense.amount, currency, decimalSeparator),
                        categoryId = expense.categoryId,
                        date = expense.date,
                        note = expense.note.orEmpty(),
                    ),
                )
            }
        } else if (!formReady) {
            // The category passed in (e.g. from a category's spending list) wins over the last used one.
            val preferred = savedState.get<String>(ARG_CATEGORY_ID)?.let(::CategoryId) ?: expenses.lastUsedCategory()
            val active = categories.categories.first().filterNot { it.isArchived }.map { it.id }
            updateForm(form.copy(categoryId = preferred?.takeIf { it in active }))
        }
        formReady = true
        savedState[KEY_INITIALIZED] = true
    }

    /** Active categories, plus the selected one if it has been archived or deleted (an old expense being edited). */
    private fun options(all: List<Category>): List<CategoryOption> =
        all.filter { !it.isArchived || it.id == form.categoryId }.map { CategoryOption(it.id, it.name, it.isArchived, it.isDeleted) }

    /**
     * Before the first save attempt, only errors that typing more can't fix are shown (too many
     * decimals, too large), so a half-typed "0," doesn't flash an error. After an attempt, all are.
     */
    private fun visibleErrors(currency: CurrencySpec, today: LocalDate): DraftErrors {
        val errors = (form.toDraft().validate(currency, today) as? Outcome.Err)?.error ?: return DraftErrors()
        if (attemptedSave) return errors
        return errors.copy(
            amount = errors.amount?.takeIf { it == AmountError.TOO_MANY_DECIMALS || it == AmountError.TOO_LARGE },
            missingCategory = false,
        )
    }

    fun onAmountChange(text: String) = updateForm(form.copy(amountText = text.amountInputChars()))
    fun onCategorySelect(id: CategoryId) = updateForm(form.copy(categoryId = id))
    fun onDateSelect(date: LocalDate) = updateForm(form.copy(date = date))
    fun onNoteChange(text: String) = updateForm(form.copy(note = text))

    fun save() {
        val ref = reference ?: return
        if (saving || state !is ExpenseEditorState.Editing) return
        attemptedSave = true
        savedState[KEY_ATTEMPTED] = true
        val valid = when (val result = form.toDraft().validate(ref.currency, LocalDate.now(clock))) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return
        }
        saving = true
        failure = null
        viewModelScope.launch {
            val saved = runCatching {
                if (expenseId == null) expenses.add(valid.amount, valid.categoryId, valid.date, valid.note)
                else expenses.update(expenseId, valid.amount, valid.categoryId, valid.date, valid.note)
            }
            saving = false
            if (saved.isSuccess) done = true else failure = EditorFailure.SAVE
        }
    }

    /** The screen confirms first, so there is no undo here. */
    fun delete() {
        val id = expenseId ?: return
        if (saving) return
        saving = true
        failure = null
        viewModelScope.launch {
            val deleted = runCatching { expenses.delete(id) }
            saving = false
            if (deleted.isSuccess) done = true else failure = EditorFailure.DELETE
        }
    }

    private fun restoreForm() = ExpenseForm(
        amountText = savedState[KEY_AMOUNT] ?: "",
        categoryId = savedState.get<String>(KEY_CATEGORY)?.let(::CategoryId),
        date = savedState.get<String>(KEY_DATE)?.let(LocalDate::parse) ?: LocalDate.now(clock),
        note = savedState[KEY_NOTE] ?: "",
    )

    private fun updateForm(value: ExpenseForm) {
        form = value
        savedState[KEY_AMOUNT] = value.amountText
        savedState[KEY_CATEGORY] = value.categoryId?.value
        savedState[KEY_DATE] = value.date.toString()
        savedState[KEY_NOTE] = value.note
    }

    companion object {
        /** Names match [lv.kristapsbe.budzets.ui.navigation.ExpenseEditorRoute]'s properties. */
        const val ARG_EXPENSE_ID = "expenseId"
        const val ARG_CATEGORY_ID = "categoryId"
        private const val KEY_AMOUNT = "form_amount"
        private const val KEY_CATEGORY = "form_category"
        private const val KEY_DATE = "form_date"
        private const val KEY_NOTE = "form_note"
        private const val KEY_INITIALIZED = "form_initialized"
        private const val KEY_ATTEMPTED = "form_attempted"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                ExpenseEditorViewModel(
                    createSavedStateHandle(),
                    container.expenses,
                    container.categories,
                    container.settings,
                    container.surplus,
                    container.clock,
                )
            }
        }
    }
}
