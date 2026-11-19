package lv.kristapsbe.budzets.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SettingsRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.SequentialIds
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PersistenceAndSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-04-15T09:00:00Z"), ZoneId.of("Europe/Riga"))

    @Test
    fun `data survives closing and reopening the database file`() = runTest {
        val file = File(tmp.root, "budzets.db")
        val ids = SequentialIds()

        Room.databaseBuilder(context, BudzetsDatabase::class.java, file.absolutePath).build().let { db ->
            val categories = CategoryRepository(db, clock, ids::next)
            val budgets = BudgetRepository(db, clock, ids::next)
            val food = categories.create("Food")
            budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = 1_000), ym("2026-03"))
            ExpenseRepository(db, clock, ids::next).add(eur(10), food.id, LocalDate.of(2026, 3, 1), "x")
            SurplusRepository(db, budgets, clock, ids::next).closeMonth(ym("2026-03")).ok()
            db.close()
        }

        Room.databaseBuilder(context, BudzetsDatabase::class.java, file.absolutePath).build().let { db ->
            val budgets = BudgetRepository(db, clock, ids::next)
            val surplus = SurplusRepository(db, budgets, clock, ids::next)
            assertEquals(listOf("Food"), CategoryRepository(db, clock, ids::next).categories.first().map { it.name })
            assertEquals(1, budgets.configs.first().size)
            assertEquals(eur(10), ExpenseRepository(db, clock, ids::next).observeMonth(ym("2026-03")).first().single().amount)
            assertEquals(eur(300), surplus.balances.first().values.single())
            assertTrue(surplus.latestClosure(ym("2026-03"))!!.isClosed)
            db.close()
        }
    }

    @Test
    fun `settings default to EUR and currency switching follows the minor-unit rule`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var hasData = false
        try {
            val settings = SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
                hasMonetaryData = { hasData },
            )
            assertEquals(CurrencySpec.DEFAULT, settings.currency.first())
            assertNull(settings.lastBackupAt.first())

            assertTrue(settings.setCurrency(CurrencySpec.of("JPY"))) // no data yet: anything goes
            assertEquals("JPY", settings.currency.first().code)

            hasData = true
            assertFalse(settings.setCurrency(CurrencySpec.of("EUR"))) // 0 -> 2 digits refused
            assertTrue(settings.setCurrency(CurrencySpec.of("KRW"))) // 0 -> 0 digits ok
            assertEquals("KRW", settings.currency.first().code)

            val at = Instant.parse("2026-04-15T10:00:00Z")
            settings.recordBackupSuccess(at)
            assertEquals(at, settings.lastBackupAt.first())

            // Q49: trimmed, capped, and cleared by blank text.
            assertNull(settings.oneOffNote.first())
            settings.setOneOffNote("  Gifts go to Fun \n")
            assertEquals("Gifts go to Fun", settings.oneOffNote.first())
            settings.setOneOffNote("x".repeat(SettingsRepository.MAX_ONE_OFF_NOTE_LENGTH + 5))
            assertEquals(SettingsRepository.MAX_ONE_OFF_NOTE_LENGTH, settings.oneOffNote.first()!!.length)
            settings.setOneOffNote("   ")
            assertNull(settings.oneOffNote.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `monetary data detection`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, BudzetsDatabase::class.java).build()
        val ids = SequentialIds()
        assertFalse(db.expenseDao().hasMonetaryData())
        CategoryRepository(db, clock, ids::next).create("Food")
        assertFalse(db.expenseDao().hasMonetaryData()) // a category alone carries no amounts
        BudgetRepository(db, clock, ids::next).setTarget(BudgetScope.Overall, BudgetTarget(dailyMinor = 1))
        assertTrue(db.expenseDao().hasMonetaryData())
        db.close()
    }
}
