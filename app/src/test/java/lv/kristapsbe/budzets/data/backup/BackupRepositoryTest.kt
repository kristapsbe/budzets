package lv.kristapsbe.budzets.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
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
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.BudgetScope
import lv.kristapsbe.budzets.domain.model.BudgetTarget
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.ok
import lv.kristapsbe.budzets.domain.ym
import lv.kristapsbe.budzets.ui.ViewModelTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class BackupRepositoryTest : ViewModelTest() {

    private val otherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var backups: BackupRepository
    private lateinit var safetyCopy: File

    @Before
    fun setUpBackups() {
        safetyCopy = File(tmp.root, "private/pre-restore-backup.json")
        backups = BackupRepository(db, settings, clock, "1.0", safetyCopy)
    }

    @After
    fun stopOther() = otherScope.cancel()

    /** A second, empty install: its own database, settings and safety copy. */
    private inner class CleanInstall {
        val db: BudzetsDatabase = Room.inMemoryDatabaseBuilder(context, BudzetsDatabase::class.java).build()
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = otherScope) { File(tmp.root, "other.preferences_pb") },
            hasMonetaryData = { db.expenseDao().hasMonetaryData() },
        )
        val budgets = BudgetRepository(db, clock, ids::next)
        val surplus = SurplusRepository(db, budgets, clock, ids::next)
        val categories = CategoryRepository(db, clock, ids::next)
        val backups = BackupRepository(db, settings, clock, "1.0", File(tmp.root, "other/pre-restore-backup.json"))
    }

    private fun decoded(bytes: ByteArray): Backup = (BackupCodec.decode(bytes) as? Outcome.Ok)?.value ?: throw AssertionError("not restorable")

    /** Every kind of record, including revisions, reopenings, released boosts and an archived category. */
    private suspend fun seedEverything() {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun", iconKey = "star")
        val travel = categories.create("Travel")
        budgets.setTarget(BudgetScope.Overall, BudgetTarget(monthlyMinor = eur(1_000).minor), ym("2026-02"))
        budgets.setTarget(BudgetScope.Category(food.id), BudgetTarget(dailyMinor = eur(10).minor, weeklyMinor = eur(60).minor), ym("2026-02"))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(monthlyMinor = eur(100).minor), ym("2026-03"))
        budgets.setTarget(BudgetScope.Category(fun_.id), BudgetTarget(), ym("2026-04")) // no budget from April

        expenses.add(eur(50), food.id, LocalDate.of(2026, 2, 3))
        val milk = expenses.add(eur(200), food.id, LocalDate.of(2026, 3, 5), note = "Milk “and” bread")
        expenses.add(eur(150), fun_.id, LocalDate.of(2026, 3, 6))
        expenses.add(eur(30), travel.id, LocalDate.of(2026, 3, 7))
        val gone = expenses.add(eur(1), food.id, LocalDate.of(2026, 3, 8))
        expenses.update(milk.id, eur(210), food.id, milk.date, "edited")
        expenses.delete(gone.id)

        surplus.closeMonth(ym("2026-02")).ok()
        surplus.reopenMonth(ym("2026-02")).ok()
        surplus.closeMonth(ym("2026-02")).ok()
        surplus.closeMonth(ym("2026-03")).ok()
        expenses.add(eur(5), food.id, LocalDate.of(2026, 3, 30))
        surplus.closeMonth(ym("2026-03")).ok() // adjustment, revision 2

        surplus.transfer(SurplusPocket.Unallocated, SurplusPocket.Category(fun_.id), eur(20), note = "cover fun").ok()
        surplus.allocateBoost(SurplusPocket.Unallocated, fun_.id, ym("2026-05"), eur(10)).ok()
        val released = surplus.allocateBoost(SurplusPocket.Unallocated, food.id, ym("2026-04"), eur(5)).ok()
        surplus.releaseBoost(released.id).ok()
        expenses.add(eur(12), food.id, LocalDate.of(2026, 4, 2))
        categories.archive(travel.id, ym("2026-04"))
        assertTrue(settings.setCurrency(CurrencySpec.of("USD")))
    }

    @Test
    fun `a full backup restores into a clean install with everything intact`() = runTest {
        seedEverything()
        val bytes = backups.export()
        val original = decoded(bytes)
        assertEquals(3, original.data.categories.size)
        assertEquals(2, original.data.monthClosures.count { it.month == "2026-03" })

        val clean = CleanInstall()
        clean.backups.restore(decoded(bytes))

        assertEquals(original.data, decoded(clean.backups.export()).data)
        assertEquals(CurrencySpec.of("USD"), clean.settings.currency.first())
        for (month in listOf("2026-02", "2026-03", "2026-04", "2026-05")) {
            assertEquals(month, budgets.loadMonthSummary(ym(month)), clean.budgets.loadMonthSummary(ym(month)))
        }
        assertEquals(surplus.balances.first(), clean.surplus.balances.first())
        assertEquals(surplus.boosts.first(), clean.surplus.boosts.first())
        assertEquals(surplus.closedMonths.first(), clean.surplus.closedMonths.first())
        assertEquals(categories.categories.first(), clean.categories.categories.first())
        assertEquals(db.expenseDao().lastAddedCategoryId(), clean.db.expenseDao().lastAddedCategoryId())
        clean.db.close()
    }

    @Test
    fun `restore replaces rather than merges, and undo puts the earlier data back`() = runTest {
        seedEverything()
        val backup = decoded(backups.export())
        val extra = categories.create("Added after the backup")
        expenses.add(eur(7), extra.id, LocalDate.of(2026, 4, 10))
        val beforeRestore = decoded(backups.export()).data
        assertNull(settings.lastRestoreAt.first())

        backups.restore(backup)
        assertEquals(backup.data, decoded(backups.export()).data)
        assertFalse(categories.categories.first().any { it.name == "Added after the backup" })
        assertEquals(clock.instant(), settings.lastRestoreAt.first())
        assertTrue(safetyCopy.exists())

        assertTrue(backups.undoRestore())
        assertEquals(beforeRestore, decoded(backups.export()).data)
        assertNull(settings.lastRestoreAt.first())
        assertFalse(safetyCopy.exists())
        assertFalse(backups.undoRestore()) // nothing left to undo
    }

    @Test
    fun `undo is only offered while the safety copy exists`() = runTest {
        seedEverything()
        backups.restore(decoded(backups.export()))
        assertEquals(clock.instant(), backups.lastRestoreAt.first())
        // As on a new phone after a device transfer: the marker came along, the private copy didn't.
        safetyCopy.delete()
        assertNull(backups.lastRestoreAt.first())
    }

    @Test
    fun `a restore that fails part-way leaves the data as it was`() = runTest {
        seedEverything()
        val before = decoded(backups.export()).data
        val good = decoded(backups.export())
        // Checks are bypassed here, so the database itself refuses the dangling category.
        val broken = Backup(1, "1.0", Instant.EPOCH, good.data.copy(expenses = good.data.expenses + good.data.expenses.first().copy(id = "x", categoryId = "missing")))

        try {
            backups.restore(broken)
            fail("restore should have failed")
        } catch (e: Exception) {
            // expected: foreign key constraint
        }
        assertEquals(before, decoded(backups.export()).data)
        assertNull(settings.lastRestoreAt.first())
    }

    @Test
    fun `a backup's currency is restored even where its decimals differ`() = runTest {
        val clean = CleanInstall()
        val yen = clean.categories.create("Food")
        clean.settings.setCurrency(CurrencySpec.of("JPY"))
        ExpenseRepository(clean.db, clock, ids::next).add(eur(1), yen.id, LocalDate.of(2026, 4, 1))
        val backup = decoded(clean.backups.export())

        val food = categories.create("Food")
        expenses.add(eur(5), food.id, LocalDate.of(2026, 4, 1))
        assertFalse(settings.setCurrency(CurrencySpec.of("JPY"))) // the normal switch is refused
        backups.restore(backup)
        assertEquals(CurrencySpec.of("JPY"), settings.currency.first())
        assertNotEquals(food.id, categories.categories.first().single().id)
        clean.db.close()
    }

    @Test
    fun `an empty install backs up and restores too`() = runTest {
        val backup = decoded(backups.export())
        assertTrue(backup.data.categories.isEmpty())
        assertEquals("EUR", backup.data.settings.currencyCode)
        seedEverything()
        backups.restore(backup)
        assertTrue(categories.categories.first().isEmpty())
        assertEquals(CurrencySpec.DEFAULT, settings.currency.first())
    }
}
