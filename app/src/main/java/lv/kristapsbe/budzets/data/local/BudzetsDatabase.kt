package lv.kristapsbe.budzets.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import lv.kristapsbe.budzets.data.local.dao.BudgetConfigDao
import lv.kristapsbe.budzets.data.local.dao.CategoryDao
import lv.kristapsbe.budzets.data.local.dao.ExpenseDao
import lv.kristapsbe.budzets.data.local.dao.SurplusDao
import lv.kristapsbe.budzets.data.local.entity.BoostEntity
import lv.kristapsbe.budzets.data.local.entity.BudgetConfigEntity
import lv.kristapsbe.budzets.data.local.entity.CategoryEntity
import lv.kristapsbe.budzets.data.local.entity.ExpenseEntity
import lv.kristapsbe.budzets.data.local.entity.LedgerEntryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureCategoryEntity
import lv.kristapsbe.budzets.data.local.entity.MonthClosureEntity

@Database(
    entities = [
        CategoryEntity::class,
        BudgetConfigEntity::class,
        ExpenseEntity::class,
        LedgerEntryEntity::class,
        BoostEntity::class,
        MonthClosureEntity::class,
        MonthClosureCategoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class BudzetsDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetConfigDao(): BudgetConfigDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun surplusDao(): SurplusDao

    companion object {
        const val NAME = "budzets.db"

        /**
         * Never add a destructive-migration fallback. A missing migration must fail loudly
         * rather than wipe user data. Add schema changes as explicit migrations, tested
         * against the exported schemas in `app/schemas/`.
         */
        fun build(context: Context): BudzetsDatabase =
            Room.databaseBuilder(context.applicationContext, BudzetsDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** v1 → v2: optional monthly budget target (Phase 5). Existing budgets keep daily/weekly only. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE budget_configs ADD COLUMN monthlyTargetMinor INTEGER")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
