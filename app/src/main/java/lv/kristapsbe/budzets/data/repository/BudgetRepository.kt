package lv.kristapsbe.budzets.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.categoryColumn
import lv.kristapsbe.budzets.data.local.dao.CategorySpendRow
import lv.kristapsbe.budzets.data.local.dao.ClosureSnapshotRow
import lv.kristapsbe.budzets.data.local.dateRange
import lv.kristapsbe.budzets.data.local.entity.BoostEntity
import lv.kristapsbe.budzets.data.local.entity.BudgetConfigEntity
import lv.kristapsbe.budzets.data.local.entity.CategoryEntity
import lv.kristapsbe.budzets.data.local.scopeKey
import lv.kristapsbe.budzets.data.local.toDb
import lv.kristapsbe.budzets.data.local.toDomain
import lv.kristapsbe.budzets.data.local.toSnapshot
import lv.kristapsbe.budzets.data.local.toYearMonth
import lv.kristapsbe.budzets.domain.calculation.BudgetResolver
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummaryCalculator
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import java.time.Clock
import java.time.YearMonth

class BudgetRepository(
    private val db: BudzetsDatabase,
    private val clock: Clock,
    private val newId: () -> String,
) {
    private val configDao = db.budgetConfigDao()
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()
    private val surplusDao = db.surplusDao()

    val configs: Flow<List<BudgetConfig>> = configDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Sets [scope]'s targets from [effectiveFrom] on, until a later version. Re-saving the same
     * month updates that version in place. Earlier months keep their own versions, and closed
     * months keep the budgets they were closed with.
     */
    suspend fun setTarget(scope: BudgetScope, target: BudgetTarget, effectiveFrom: YearMonth = YearMonth.now(clock)) =
        db.withTransaction {
            val now = clock.instant().toDb()
            val existing = configDao.find(scope.scopeKey(), effectiveFrom.toDb())
            if (existing != null) {
                configDao.update(
                    existing.copy(
                        dailyTargetMinor = target.dailyMinor,
                        weeklyTargetMinor = target.weeklyMinor,
                        monthlyTargetMinor = target.monthlyMinor,
                        updatedAt = now,
                    )
                )
            } else {
                configDao.insert(
                    BudgetConfigEntity(
                        id = newId(),
                        scopeKey = scope.scopeKey(),
                        categoryId = scope.categoryColumn(),
                        dailyTargetMinor = target.dailyMinor,
                        weeklyTargetMinor = target.weeklyMinor,
                        effectiveFrom = effectiveFrom.toDb(),
                        createdAt = now,
                        updatedAt = now,
                        monthlyTargetMinor = target.monthlyMinor,
                    )
                )
            }
        }

    /**
     * Live summary of [month]; re-emits when categories, budgets, boosts or expenses change, or
     * when the month is closed or reopened. A closed month keeps the budgets it was closed with.
     */
    fun observeMonthSummary(month: YearMonth): Flow<MonthSummary> {
        val (from, to) = month.dateRange()
        return combine(
            categoryDao.observeAll(),
            configDao.observeAll(),
            surplusDao.observeBoostsFor(month.toDb()),
            expenseDao.observeSpending(from, to),
            surplusDao.observeClosureSnapshot(month.toDb()),
        ) { categories, configs, boosts, spending, closure -> summarize(month, categories, configs, boosts, spending, closure) }
    }

    /**
     * Live summaries of every month from the first one with spending through [until], months
     * without spending included; empty while there are no expenses. Each month is worked out as
     * [observeMonthSummary] does, so a closed month keeps the budgets it was closed with. Spending
     * dated after [until] (possible only after a clock change) is left out.
     */
    fun observeMonthSummaries(until: YearMonth): Flow<List<MonthSummary>> =
        combine(
            categoryDao.observeAll(),
            configDao.observeAll(),
            surplusDao.observeBoosts(),
            expenseDao.observeMonthlySpending(),
            surplusDao.observeClosureSnapshots(),
        ) { categories, configs, boosts, spending, closures ->
            val spendingByMonth = spending.groupBy { it.month.toYearMonth() }.filterKeys { it <= until }
            val first = spendingByMonth.keys.minOrNull() ?: return@combine emptyList()
            val boostsByMonth = boosts.groupBy { it.targetMonth.toYearMonth() }
            val closuresByMonth = closures.groupBy { it.month.toYearMonth() }
            generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= until }.map { month ->
                summarize(
                    month,
                    categories,
                    configs,
                    boostsByMonth[month].orEmpty(),
                    spendingByMonth[month].orEmpty().map { CategorySpendRow(it.categoryId, it.totalMinor) },
                    closuresByMonth[month].orEmpty(),
                )
            }.toList()
        }

    /** One-shot summary read in a single transaction (consistent snapshot). */
    suspend fun loadMonthSummary(month: YearMonth): MonthSummary = db.withTransaction {
        val (from, to) = month.dateRange()
        summarize(
            month,
            categoryDao.getAll(),
            configDao.getAll(),
            surplusDao.getBoostsFor(month.toDb()),
            expenseDao.getSpending(from, to),
            surplusDao.getClosureSnapshot(month.toDb()),
        )
    }

    private fun summarize(
        month: YearMonth,
        categories: List<CategoryEntity>,
        configs: List<BudgetConfigEntity>,
        boosts: List<BoostEntity>,
        spending: List<CategorySpendRow>,
        closure: List<ClosureSnapshotRow>,
    ): MonthSummary {
        val boosted = boosts.map { it.toDomain() }
        val spent = spending.associate { CategoryId(it.categoryId) to Money(it.totalMinor) }
        closure.toSnapshot(month)?.let { return MonthSummaryCalculator.summarizeClosed(it, boosted, spent) }
        val resolved = BudgetResolver.resolve(
            month,
            configs.map { it.toDomain() },
            categories.map { it.toDomain().toRef() },
        )
        return MonthSummaryCalculator.summarize(resolved, boosted, spent)
    }
}
