package lv.kristapsbe.budzets.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Storage conventions:
 * - money: Long minor units
 * - months: "YYYY-MM"
 * - dates: ISO "YYYY-MM-DD" (local calendar date)
 * - instants: epoch milliseconds
 * - a null category column on surplus rows means the Unallocated pocket
 *
 * A category with history is never really deleted: `deletedAt` hides it, because closed months,
 * old expenses and the ledger still name it. Every reference to it is RESTRICT, except budget
 * configs, which cascade when a category without history is removed.
 */

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String?,
    val sortOrder: Int,
    val archivedFrom: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Added in schema v3 (last, matching `ALTER TABLE … ADD COLUMN`). */
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "budget_configs",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["scopeKey", "effectiveFrom"], unique = true), Index("categoryId")],
)
data class BudgetConfigEntity(
    @PrimaryKey val id: String,
    /** [OVERALL_SCOPE] or the category id. Lets the unique index cover the overall budget too. */
    val scopeKey: String,
    val categoryId: String?,
    val dailyTargetMinor: Long?,
    val weeklyTargetMinor: Long?,
    val effectiveFrom: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Added in schema v2 (last, matching `ALTER TABLE … ADD COLUMN`). */
    val monthlyTargetMinor: Long? = null,
) {
    companion object {
        const val OVERALL_SCOPE = "@overall"
    }
}

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("expenseDate"), Index("categoryId")],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val categoryId: String,
    val expenseDate: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "surplus_ledger",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["pocketCategoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("pocketCategoryId"), Index("sourceMonth"), Index("boostId")],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val pocketCategoryId: String?,
    val amountMinor: Long,
    val sourceMonth: String?,
    val targetMonth: String?,
    val relatedEntryId: String?,
    val boostId: String?,
    val note: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "boosts",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationCategoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceCategoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("targetMonth"), Index("destinationCategoryId"), Index("sourceCategoryId")],
)
data class BoostEntity(
    @PrimaryKey val id: String,
    val targetMonth: String,
    val destinationCategoryId: String,
    val sourceCategoryId: String?,
    val amountMinor: Long,
    val status: String,
    val reserveEntryId: String,
    val createdAt: Long,
    val releasedAt: Long?,
)

/** One row per close of a month. Only [reopenedAt] is ever updated. */
@Entity(tableName = "month_closures", primaryKeys = ["month", "revision"])
data class MonthClosureEntity(
    val month: String,
    val revision: Int,
    val closedAt: Long,
    val reopenedAt: Long?,
    val overallBaseMinor: Long?,
    val overallDerived: Boolean,
)

/** Per-category snapshot of a closure. */
@Entity(
    tableName = "month_closure_categories",
    primaryKeys = ["month", "revision", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MonthClosureEntity::class,
            parentColumns = ["month", "revision"],
            childColumns = ["month", "revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId")],
)
data class MonthClosureCategoryEntity(
    val month: String,
    val revision: Int,
    val categoryId: String,
    val baseMinor: Long?,
    val boostMinor: Long,
    val spentMinor: Long,
)
