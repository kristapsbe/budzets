package lv.kristapsbe.budzets.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.toDomain
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.ym
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migrations, checked against the schemas exported to `app/schemas/` and committed.
 *
 * Room's MigrationTestHelper only reads schemas from instrumentation assets, which Robolectric
 * can't see without shipping them in the debug APK. Instead the old database is built straight
 * from its JSON (as the helper would), and opening it with Room runs the migration and Room's
 * own schema validation, which throws if the result doesn't match the current entities.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Unit tests run with the module directory as the working directory. */
    private fun schema(version: Int): JSONObject =
        JSONObject(File("schemas/${BudzetsDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")

    private fun createDatabase(file: File, version: Int, seed: SQLiteDatabase.() -> Unit) {
        val schema = schema(version)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            db.seed()
        }
    }

    private fun columns(db: BudzetsDatabase, table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun expectedColumns(version: Int, table: String): Set<String> {
        val entities = schema(version).getJSONArray("entities")
        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
    }

    @Test
    fun `v1 to v2 adds the monthly target and keeps existing budgets and expenses`() = runTest {
        val file = File(tmp.root, "budzets-v1.db")
        createDatabase(file, version = 1) {
            execSQL("INSERT INTO categories VALUES ('food', 'Food', NULL, 0, NULL, 1, 1)")
            execSQL("INSERT INTO budget_configs VALUES ('b1', '@overall', NULL, 2000, NULL, '2026-03', 1, 1)")
            execSQL("INSERT INTO budget_configs VALUES ('b2', 'food', 'food', NULL, 7000, '2026-04', 1, 1)")
            execSQL("INSERT INTO expenses VALUES ('e1', 1250, 'food', '2026-04-02', 'bread', 1, 1)")
        }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), BudzetsDatabase::class.java, file.absolutePath)
            .addMigrations(*BudzetsDatabase.MIGRATIONS)
            .build()
        try {
            // Opening runs the migration and Room's schema validation.
            val configs = db.budgetConfigDao().observeAll().first().map { it.toDomain() }
            assertEquals(expectedColumns(2, "budget_configs"), columns(db, "budget_configs"))

            assertEquals(listOf(BudgetScope.Overall, BudgetScope.Category(CategoryId("food"))), configs.map { it.scope })
            assertEquals(BudgetTarget(dailyMinor = 2_000), configs[0].target)
            assertEquals(BudgetTarget(weeklyMinor = 7_000), configs[1].target)
            assertEquals(ym("2026-04"), configs[1].effectiveFrom)
            assertEquals("bread", db.expenseDao().get("e1")!!.note)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 to v3 adds deletedAt and leaves every category in place`() = runTest {
        val file = File(tmp.root, "budzets-v2.db")
        createDatabase(file, version = 2) {
            execSQL("INSERT INTO categories VALUES ('food', 'Food', NULL, 0, NULL, 1, 1)")
            execSQL("INSERT INTO categories VALUES ('old', 'Old', NULL, 1, '2026-02', 1, 1)")
            execSQL("INSERT INTO expenses VALUES ('e1', 1250, 'old', '2026-01-02', 'bread', 1, 1)")
        }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), BudzetsDatabase::class.java, file.absolutePath)
            .addMigrations(*BudzetsDatabase.MIGRATIONS)
            .build()
        try {
            val all = db.categoryDao().observeAll().first().map { it.toDomain() }
            assertEquals(expectedColumns(3, "categories"), columns(db, "categories"))

            assertEquals(listOf("Food", "Old"), all.map { it.name })
            assertEquals(listOf(false, false), all.map { it.isDeleted })
            assertEquals(ym("2026-02"), all[1].archivedFrom)
            assertEquals("bread", db.expenseDao().get("e1")!!.note)
        } finally {
            db.close()
        }
    }
}
