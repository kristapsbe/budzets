package lv.kristapsbe.budzets.data.local

import lv.kristapsbe.budzets.data.local.dao.ClosureSnapshotRow
import lv.kristapsbe.budzets.data.local.entity.BoostEntity
import lv.kristapsbe.budzets.data.local.entity.BudgetConfigEntity
import lv.kristapsbe.budzets.data.local.entity.CategoryEntity
import lv.kristapsbe.budzets.data.local.entity.ExpenseEntity
import lv.kristapsbe.budzets.data.local.entity.LedgerEntryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureCategoryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureEntity
import lv.kristapsbe.budzets.domain.calculation.CategorySummary
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.BudgetConfig
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Expense
import lv.kristapsbe.budzets.domain.model.ExpenseId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.LedgerEntryType
import lv.kristapsbe.budzets.domain.surplus.MonthClosure
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

internal fun YearMonth.toDb(): String = toString()
internal fun String.toYearMonth(): YearMonth = YearMonth.parse(this)
internal fun Instant.toDb(): Long = toEpochMilli()
internal fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

/** Half-open ISO date range covering [this] month, for expense queries. */
internal fun YearMonth.dateRange(): Pair<String, String> =
    atDay(1).toString() to plusMonths(1).atDay(1).toString()

internal fun SurplusPocket.categoryColumn(): String? = (this as? SurplusPocket.Category)?.id?.value
internal fun pocketOf(categoryId: String?): SurplusPocket =
    categoryId?.let { SurplusPocket.Category(CategoryId(it)) } ?: SurplusPocket.Unallocated

internal fun CategoryEntity.toDomain() = Category(
    id = CategoryId(id),
    name = name,
    iconKey = iconKey,
    sortOrder = sortOrder,
    archivedFrom = archivedFrom?.toYearMonth(),
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    deletedAt = deletedAt?.toInstant(),
)

internal fun Category.toEntity() = CategoryEntity(
    id = id.value,
    name = name,
    iconKey = iconKey,
    sortOrder = sortOrder,
    archivedFrom = archivedFrom?.toDb(),
    createdAt = createdAt.toDb(),
    updatedAt = updatedAt.toDb(),
    deletedAt = deletedAt?.toDb(),
)

internal fun BudgetConfigEntity.toDomain() = BudgetConfig(
    scope = categoryId?.let { BudgetScope.Category(CategoryId(it)) } ?: BudgetScope.Overall,
    target = BudgetTarget(dailyTargetMinor, weeklyTargetMinor, monthlyTargetMinor),
    effectiveFrom = effectiveFrom.toYearMonth(),
)

internal fun BudgetScope.scopeKey(): String = when (this) {
    BudgetScope.Overall -> BudgetConfigEntity.OVERALL_SCOPE
    is BudgetScope.Category -> id.value
}

internal fun BudgetScope.categoryColumn(): String? = (this as? BudgetScope.Category)?.id?.value

internal fun ExpenseEntity.toDomain() = Expense(
    id = ExpenseId(id),
    amount = Money(amountMinor),
    categoryId = CategoryId(categoryId),
    date = LocalDate.parse(expenseDate),
    note = note,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

internal fun Expense.toEntity() = ExpenseEntity(
    id = id.value,
    amountMinor = amount.minor,
    categoryId = categoryId.value,
    expenseDate = date.toString(),
    note = note,
    createdAt = createdAt.toDb(),
    updatedAt = updatedAt.toDb(),
)

internal fun LedgerEntryEntity.toDomain() = LedgerEntry(
    id = LedgerEntryId(id),
    type = LedgerEntryType.valueOf(type),
    pocket = pocketOf(pocketCategoryId),
    amount = Money(amountMinor),
    createdAt = createdAt.toInstant(),
    sourceMonth = sourceMonth?.toYearMonth(),
    targetMonth = targetMonth?.toYearMonth(),
    relatedEntryId = relatedEntryId?.let(::LedgerEntryId),
    boostId = boostId?.let(::BoostId),
    note = note,
)

internal fun LedgerEntry.toEntity() = LedgerEntryEntity(
    id = id.value,
    type = type.name,
    pocketCategoryId = pocket.categoryColumn(),
    amountMinor = amount.minor,
    sourceMonth = sourceMonth?.toDb(),
    targetMonth = targetMonth?.toDb(),
    relatedEntryId = relatedEntryId?.value,
    boostId = boostId?.value,
    note = note,
    createdAt = createdAt.toDb(),
)

internal fun BoostEntity.toDomain() = Boost(
    id = BoostId(id),
    targetMonth = targetMonth.toYearMonth(),
    destination = CategoryId(destinationCategoryId),
    source = pocketOf(sourceCategoryId),
    amount = Money(amountMinor),
    status = BoostStatus.valueOf(status),
    reserveEntryId = LedgerEntryId(reserveEntryId),
    createdAt = createdAt.toInstant(),
    releasedAt = releasedAt?.toInstant(),
)

internal fun Boost.toEntity() = BoostEntity(
    id = id.value,
    targetMonth = targetMonth.toDb(),
    destinationCategoryId = destination.value,
    sourceCategoryId = source.categoryColumn(),
    amountMinor = amount.minor,
    status = status.name,
    reserveEntryId = reserveEntryId.value,
    createdAt = createdAt.toDb(),
    releasedAt = releasedAt?.toDb(),
)

internal fun MonthClosure.toEntity() = MonthClosureEntity(
    month = month.toDb(),
    revision = revision,
    closedAt = closedAt.toDb(),
    reopenedAt = reopenedAt?.toDb(),
    overallBaseMinor = snapshot.overallBase?.minor,
    overallDerived = snapshot.overallDerived,
)

internal fun MonthClosure.toCategoryRows() = snapshot.categories.values.map {
    MonthClosureCategoryEntity(
        month = month.toDb(),
        revision = revision,
        categoryId = it.categoryId.value,
        baseMinor = it.base?.minor,
        boostMinor = it.boost.minor,
        spentMinor = it.spent.minor,
    )
}

/** The budgets a closed month keeps; null when [this] is empty, meaning the month isn't closed. */
internal fun List<ClosureSnapshotRow>.toSnapshot(month: YearMonth): MonthSummary? {
    val closure = firstOrNull() ?: return null
    val categories = mapNotNull { row ->
        row.categoryId?.let { CategoryId(it) }?.let { id ->
            id to CategorySummary(id, row.baseMinor?.let(::Money), Money(row.boostMinor ?: 0), Money(row.spentMinor ?: 0))
        }
    }.toMap()
    return MonthSummary(month, closure.overallBaseMinor?.let(::Money), closure.overallDerived, categories)
}

internal fun MonthClosureEntity.toDomain(rows: List<MonthClosureCategoryEntity>): MonthClosure {
    val month = month.toYearMonth()
    val categories = rows.associate { row ->
        val id = CategoryId(row.categoryId)
        id to CategorySummary(id, row.baseMinor?.let(::Money), Money(row.boostMinor), Money(row.spentMinor))
    }
    return MonthClosure(
        month = month,
        revision = revision,
        closedAt = closedAt.toInstant(),
        snapshot = MonthSummary(month, overallBaseMinor?.let(::Money), overallDerived, categories),
        reopenedAt = reopenedAt?.toInstant(),
    )
}
