package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.YearMonth

/** Per-field parse errors for the budget editor; null means the field is fine. */
data class TargetErrors(val daily: AmountError? = null, val weekly: AmountError? = null, val monthly: AmountError? = null) {
    val isEmpty: Boolean get() = daily == null && weekly == null && monthly == null
}

/** What the budget editor needs beyond [MonthlyPlanRule]: draft parsing and "what if" resolution. */
object BudgetPlanning {

    /** The month a later version of [scope] takes over after [month], if any. */
    fun nextChangeAfter(configs: Iterable<BudgetConfig>, scope: BudgetScope, month: YearMonth): YearMonth? =
        configs.filter { it.scope == scope && it.effectiveFrom > month }.minOfOrNull { it.effectiveFrom }

    /**
     * Resolves [month] as if [draft] were saved for [scope] from [month], replacing a version
     * saved for that same month, as saving does.
     */
    fun previewWith(
        month: YearMonth,
        configs: List<BudgetConfig>,
        categories: List<CategoryRef>,
        scope: BudgetScope,
        draft: BudgetTarget,
    ): ResolvedBudget {
        val withDraft = configs.filterNot { it.scope == scope && it.effectiveFrom == month } + BudgetConfig(scope, draft, month)
        return BudgetResolver.resolve(month, withDraft, categories)
    }

    /** Same rule as [MonthSummary.categoryPlansExceedOverall], applied to a resolved (possibly draft) budget. */
    fun categoryPlansExceedOverall(resolved: ResolvedBudget): Boolean =
        !resolved.overallDerived && resolved.overallBase != null && categoryTotal(resolved) > resolved.overallBase

    fun categoryTotal(resolved: ResolvedBudget): Money = resolved.categoryPlans.values.map { it.amount }.sumMoney()

    /**
     * Empty fields are "not set"; zero is a real target. All three empty gives an unconfigured
     * target, meaning "no budget from this month".
     */
    fun parseTargets(daily: String, weekly: String, monthly: String, currency: CurrencySpec): Outcome<BudgetTarget, TargetErrors> {
        fun field(text: String): Outcome<Long?, AmountError> =
            if (text.isBlank()) Outcome.Ok(null)
            else when (val parsed = AmountParser.parse(text, currency, allowZero = true)) {
                is Outcome.Ok -> Outcome.Ok(parsed.value.minor)
                is Outcome.Err -> parsed
            }

        val d = field(daily)
        val w = field(weekly)
        val m = field(monthly)
        val errors = TargetErrors((d as? Outcome.Err)?.error, (w as? Outcome.Err)?.error, (m as? Outcome.Err)?.error)
        if (!errors.isEmpty) return Outcome.Err(errors)
        return Outcome.Ok(BudgetTarget((d as Outcome.Ok).value, (w as Outcome.Ok).value, (m as Outcome.Ok).value))
    }
}
