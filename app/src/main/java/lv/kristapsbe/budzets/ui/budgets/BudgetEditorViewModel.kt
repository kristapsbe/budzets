package lv.kristapsbe.budzets.ui.budgets

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
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.BudgetPlanning
import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlan
import lv.kristapsbe.budzets.domain.calculation.MonthlyPlanRule
import lv.kristapsbe.budzets.domain.calculation.TargetErrors
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.util.amountInputChars
import lv.kristapsbe.budzets.util.formatForInput
import java.text.DecimalFormatSymbols
import java.time.YearMonth

data class TargetForm(val daily: String = "", val weekly: String = "", val monthly: String = "")

sealed interface BudgetEditorState {
    data object Loading : BudgetEditorState

    /** The category no longer exists. */
    data object NotFound : BudgetEditorState

    data class Editing(
        /** null for the overall budget. */
        val categoryName: String?,
        val month: YearMonth,
        val currency: CurrencySpec,
        val form: TargetForm,
        /** The form parsed; null while a field doesn't parse. */
        val draft: BudgetTarget?,
        /** The draft's plan for [month]; null when the draft sets no budget or doesn't parse. */
        val preview: MonthlyPlan?,
        /** All fields empty: saving means "no budget from [month]". */
        val clearsBudget: Boolean,
        val errors: TargetErrors,
        /** When a later version takes over; this edit applies until then. */
        val nextChange: YearMonth?,
        /** Closed months in the range this edit applies to (Q3: close them again). */
        val closedMonthsAffected: List<YearMonth>,
        /** With the draft applied to [month]. */
        val categoryTotal: Money,
        val overallBase: Money?,
        val overallDerived: Boolean,
        val exceedsOverall: Boolean,
        /** Differs from the version in force, so saving would store something new. */
        val isChanged: Boolean,
        val isSaving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : BudgetEditorState

    data object Done : BudgetEditorState
}

/**
 * Edits one budget (overall, or a category's) from a given month on (Q11). Like the expense
 * editor, it exposes Compose snapshot state so text fields update synchronously, and mirrors
 * the form into [SavedStateHandle].
 *
 * Route arguments (also in [savedState]): `categoryId` (absent for overall) and `month`.
 */
class BudgetEditorViewModel(
    private val savedState: SavedStateHandle,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val surplus: SurplusRepository,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) : ViewModel() {

    private val categoryId = savedState.get<String>(ARG_CATEGORY_ID)?.let(::CategoryId)
    private val scope: BudgetScope = categoryId?.let(BudgetScope::Category) ?: BudgetScope.Overall
    private val month: YearMonth = YearMonth.parse(requireNotNull(savedState.get<String>(ARG_MONTH)) { "month is required" })

    private data class Reference(
        val currency: CurrencySpec,
        val configs: List<BudgetConfig>,
        val categories: List<Category>,
        val closedMonths: Set<YearMonth>,
    )

    private var reference by mutableStateOf<Reference?>(null)
    private var formReady by mutableStateOf(savedState.get<Boolean>(KEY_INITIALIZED) == true)
    private var attemptedSave by mutableStateOf(savedState.get<Boolean>(KEY_ATTEMPTED) == true)
    private var saving by mutableStateOf(false)
    private var saveFailed by mutableStateOf(false)
    private var done by mutableStateOf(false)

    var form by mutableStateOf(
        TargetForm(savedState[KEY_DAILY] ?: "", savedState[KEY_WEEKLY] ?: "", savedState[KEY_MONTHLY] ?: ""),
    )
        private set

    val state: BudgetEditorState
        get() {
            if (done) return BudgetEditorState.Done
            val ref = reference ?: return BudgetEditorState.Loading
            val category = categoryId?.let { id -> ref.categories.firstOrNull { it.id == id } ?: return BudgetEditorState.NotFound }
            if (!formReady) return BudgetEditorState.Loading

            val parsed = parse(ref.currency)
            val draft = (parsed as? Outcome.Ok)?.value
            val nextChange = BudgetPlanning.nextChangeAfter(ref.configs, scope, month)
            val resolved = BudgetPlanning.previewWith(month, ref.configs, ref.categories.map { it.toRef() }, scope, draft ?: inForce(ref))
            return BudgetEditorState.Editing(
                categoryName = category?.name,
                month = month,
                currency = ref.currency,
                form = form,
                draft = draft,
                preview = draft?.let { MonthlyPlanRule.plan(it, month) },
                clearsBudget = draft?.isConfigured == false,
                errors = visibleErrors(parsed),
                nextChange = nextChange,
                closedMonthsAffected = ref.closedMonths.filter { it >= month && (nextChange == null || it < nextChange) }.sorted(),
                categoryTotal = BudgetPlanning.categoryTotal(resolved),
                overallBase = resolved.overallBase,
                overallDerived = resolved.overallDerived,
                exceedsOverall = BudgetPlanning.categoryPlansExceedOverall(resolved),
                isChanged = draft == null || draft != inForce(ref),
                isSaving = saving,
                saveFailed = saveFailed,
            )
        }

    init {
        viewModelScope.launch {
            combine(settings.currency, budgets.configs, categories.categories, surplus.closedMonths, ::Reference).collect { ref ->
                reference = ref
                if (!formReady) {
                    // Start from the version in force for this month, which may have started earlier.
                    val current = inForce(ref)
                    fun text(minor: Long?) = minor?.let { formatForInput(Money(it), ref.currency, decimalSeparator) }.orEmpty()
                    updateForm(TargetForm(text(current.dailyMinor), text(current.weeklyMinor), text(current.monthlyMinor)))
                    formReady = true
                    savedState[KEY_INITIALIZED] = true
                }
            }
        }
    }

    /** The targets in force for [month] before this edit; unconfigured if there are none. */
    private fun inForce(ref: Reference): BudgetTarget =
        BudgetResolver.configFor(ref.configs, scope, month)?.target ?: BudgetTarget()

    private fun parse(currency: CurrencySpec) = BudgetPlanning.parseTargets(form.daily, form.weekly, form.monthly, currency)

    /** Before a save attempt, only errors that typing more can't fix; after it, all of them. */
    private fun visibleErrors(parsed: Outcome<BudgetTarget, TargetErrors>): TargetErrors {
        val errors = (parsed as? Outcome.Err)?.error ?: return TargetErrors()
        if (attemptedSave) return errors
        fun keep(e: AmountError?) = e?.takeIf { it == AmountError.TOO_MANY_DECIMALS || it == AmountError.TOO_LARGE }
        return TargetErrors(keep(errors.daily), keep(errors.weekly), keep(errors.monthly))
    }

    fun onDailyChange(text: String) = updateForm(form.copy(daily = text.amountInputChars()))
    fun onWeeklyChange(text: String) = updateForm(form.copy(weekly = text.amountInputChars()))
    fun onMonthlyChange(text: String) = updateForm(form.copy(monthly = text.amountInputChars()))
    fun clear() = updateForm(TargetForm())

    fun save() {
        val ref = reference ?: return
        val current = state as? BudgetEditorState.Editing ?: return
        if (saving || !current.isChanged) return
        attemptedSave = true
        savedState[KEY_ATTEMPTED] = true
        val target = (parse(ref.currency) as? Outcome.Ok)?.value ?: return
        saving = true
        saveFailed = false
        viewModelScope.launch {
            val result = runCatching { budgets.setTarget(scope, target, month) }
            saving = false
            if (result.isSuccess) done = true else saveFailed = true
        }
    }

    private fun updateForm(value: TargetForm) {
        form = value
        savedState[KEY_DAILY] = value.daily
        savedState[KEY_WEEKLY] = value.weekly
        savedState[KEY_MONTHLY] = value.monthly
    }

    companion object {
        /** Names match [lv.kristapsbe.budzets.ui.navigation.BudgetEditorRoute]'s properties. */
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_MONTH = "month"
        private const val KEY_DAILY = "form_daily"
        private const val KEY_WEEKLY = "form_weekly"
        private const val KEY_MONTHLY = "form_monthly"
        private const val KEY_INITIALIZED = "form_initialized"
        private const val KEY_ATTEMPTED = "form_attempted"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BudzetsApplication).container
                BudgetEditorViewModel(createSavedStateHandle(), container.budgets, container.categories, container.settings, container.surplus)
            }
        }
    }
}
