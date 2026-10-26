package lv.kristapsbe.budzets.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.repository.BudgetRepository
import lv.kristapsbe.budzets.data.repository.CategoryRepository
import lv.kristapsbe.budzets.data.repository.ExpenseRepository
import lv.kristapsbe.budzets.data.repository.SurplusRepository
import lv.kristapsbe.budzets.domain.SequentialIds
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Base for Room tests on the JVM (Robolectric) against an in-memory database. "Now" is
 * 2026-04-15 in Europe/Riga.
 */
@RunWith(RobolectricTestRunner::class)
abstract class DbTest {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val clock: Clock = Clock.fixed(Instant.parse("2026-04-15T09:00:00Z"), ZoneId.of("Europe/Riga"))
    protected val ids = SequentialIds()

    protected lateinit var db: BudzetsDatabase
    protected lateinit var categories: CategoryRepository
    protected lateinit var expenses: ExpenseRepository
    protected lateinit var budgets: BudgetRepository
    protected lateinit var surplus: SurplusRepository

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(context, BudzetsDatabase::class.java).build()
        categories = CategoryRepository(db, clock, ids::next)
        expenses = ExpenseRepository(db, clock, ids::next)
        budgets = BudgetRepository(db, clock, ids::next)
        surplus = SurplusRepository(db, budgets, clock, ids::next)
    }

    @After
    fun closeDb() {
        db.close()
    }
}
