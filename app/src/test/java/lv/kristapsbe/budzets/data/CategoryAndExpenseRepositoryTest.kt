package lv.kristapsbe.budzets.data

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CategoryAndExpenseRepositoryTest : DbTest() {

    @Test
    fun `category create, rename, reorder`() = runTest {
        val food = categories.create("  Food ")
        val fun_ = categories.create("Fun")
        val travel = categories.create("Travel")
        assertEquals("Food", food.name)
        assertEquals(listOf(0, 1, 2), listOf(food, fun_, travel).map { it.sortOrder })

        categories.rename(fun_.id, "Entertainment")
        categories.reorder(listOf(travel.id, food.id))
        assertEquals(listOf("Travel", "Food", "Entertainment"), categories.categories.first().map { it.name })

        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { categories.create("   ") } }
    }

    @Test
    fun `archive keeps history visible and records the archive month`() = runTest {
        val food = categories.create("Food")
        expenses.add(eur(10), food.id, LocalDate.of(2026, 4, 2))
        categories.archive(food.id)
        val archived = categories.get(food.id)!!
        assertEquals(ym("2026-04"), archived.archivedFrom)
        assertTrue(archived.isArchived)
        // archiving again keeps the original month
        categories.archive(food.id, ym("2026-09"))
        assertEquals(ym("2026-04"), categories.get(food.id)!!.archivedFrom)

        assertEquals(1, expenses.observeMonth(ym("2026-04")).first().size)
        categories.unarchive(food.id)
        assertFalse(categories.get(food.id)!!.isArchived)
    }

    @Test
    fun `category with history cannot be deleted, unused one can`() = runTest {
        val used = categories.create("Used")
        val unused = categories.create("Unused")
        expenses.add(eur(1), used.id, LocalDate.of(2026, 4, 1))

        assertFalse(categories.deleteIfUnused(used.id))
        assertTrue(categories.deleteIfUnused(unused.id))
        assertEquals(listOf("Used"), categories.categories.first().map { it.name })
    }

    @Test
    fun `foreign keys are enforced`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { expenses.add(eur(1), CategoryId("missing"), LocalDate.of(2026, 4, 1)) }
        }
        val food = categories.create("Food")
        expenses.add(eur(1), food.id, LocalDate.of(2026, 4, 1))
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.categoryDao().delete(food.id.value) }
        }
    }

    @Test
    fun `expense CRUD with month boundaries by local date`() = runTest {
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        val march31 = expenses.add(eur(5), food.id, LocalDate.of(2026, 3, 31), note = "  ")
        val april1 = expenses.add(eur(7), food.id, LocalDate.of(2026, 4, 1), note = " bread ")
        expenses.add(eur(9), fun_.id, LocalDate.of(2026, 4, 30))
        expenses.add(eur(11), fun_.id, LocalDate.of(2026, 5, 1))

        assertNull(march31.note)
        assertEquals("bread", april1.note)
        assertEquals(listOf(march31.id), expenses.observeMonth(ym("2026-03")).first().map { it.id })
        assertEquals(listOf(eur(9), eur(7)), expenses.observeMonth(ym("2026-04")).first().map { it.amount })
        assertEquals(listOf(eur(7)), expenses.observeMonth(ym("2026-04"), food.id).first().map { it.amount })

        val moved = expenses.update(april1.id, eur(8), fun_.id, LocalDate.of(2026, 3, 15), "moved")
        assertEquals(april1.createdAt, moved.createdAt)
        assertEquals(2, expenses.observeMonth(ym("2026-03")).first().size)
        assertEquals(1, expenses.observeMonth(ym("2026-04")).first().size)

        val deleted = expenses.delete(moved.id)!!
        assertNull(expenses.get(moved.id))
        expenses.restore(deleted)
        assertEquals(deleted, expenses.get(moved.id))
        assertNull(expenses.delete(lv.kristapsbe.budzets.domain.model.ExpenseId("nope")))
    }

    @Test
    fun `expense amount validated`() = runTest {
        val food = categories.create("Food")
        for (bad in listOf(Money.ZERO, Money(-1), Money(100_000_000_001))) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { expenses.add(bad, food.id, LocalDate.of(2026, 4, 1)) }
            }
        }
    }

    @Test
    fun `last used category is the most recently added expense's, not the last edited`() = runTest {
        assertNull(expenses.lastUsedCategory())
        val food = categories.create("Food")
        val fun_ = categories.create("Fun")
        val first = expenses.add(eur(1), food.id, LocalDate.of(2026, 4, 1))
        // Same createdAt (fixed clock): insertion order breaks the tie.
        expenses.add(eur(2), fun_.id, LocalDate.of(2026, 3, 1))
        assertEquals(fun_.id, expenses.lastUsedCategory())

        expenses.update(first.id, eur(3), food.id, first.date, null)
        assertEquals(fun_.id, expenses.lastUsedCategory())
    }
}
