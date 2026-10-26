package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CategoryRef
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.YearMonth

/**
 * The base (pre-boost) budgets that apply to one month.
 *
 * @property overallBase null when neither an overall nor any category budget is configured.
 * @property overallDerived true when no overall budget is configured and [overallBase] is the
 *   sum of the category plans.
 */
data class ResolvedBudget(
    val month: YearMonth,
    val overallBase: Money?,
    val overallPlan: MonthlyPlan?,
    val overallDerived: Boolean,
    val categoryPlans: Map<CategoryId, MonthlyPlan>,
)

object BudgetResolver {

    /** The version of [scope]'s budget in force for [month]: the latest one with effectiveFrom <= month. */
    fun configFor(configs: Iterable<BudgetConfig>, scope: BudgetScope, month: YearMonth): BudgetConfig? =
        configs.filter { it.scope == scope && it.effectiveFrom <= month }.maxByOrNull { it.effectiveFrom }

    fun resolve(month: YearMonth, configs: List<BudgetConfig>, categories: List<CategoryRef>): ResolvedBudget {
        val categoryPlans = categories
            .filter { it.isActiveIn(month) }
            .mapNotNull { category ->
                configFor(configs, BudgetScope.Category(category.id), month)
                    ?.let { MonthlyPlanRule.plan(it.target, month) }
                    ?.let { category.id to it }
            }
            .toMap()

        val overallPlan = configFor(configs, BudgetScope.Overall, month)
            ?.let { MonthlyPlanRule.plan(it.target, month) }

        return when {
            overallPlan != null -> ResolvedBudget(month, overallPlan.amount, overallPlan, false, categoryPlans)
            categoryPlans.isNotEmpty() -> ResolvedBudget(
                month = month,
                overallBase = categoryPlans.values.map { it.amount }.sumMoney(),
                overallPlan = null,
                overallDerived = true,
                categoryPlans = categoryPlans,
            )
            else -> ResolvedBudget(month, null, null, false, categoryPlans)
        }
    }
}
