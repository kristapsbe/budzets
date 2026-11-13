package lv.kristapsbe.budzets.domain.calculation

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.FUN
import lv.kristapsbe.budzets.domain.TRAVEL
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SpendingHistoryTest {

    private val order = mapOf(FOOD to 0, FUN to 1, TRAVEL to 2)

    private fun month(
        month: YearMonth,
        spent: Map<CategoryId, Money>,
        overall: Money? = null,
        derived: Boolean = false,
        boosts: Map<CategoryId, Money> = emptyMap(),
    ) = MonthSummary(
        month,
        overall,
        derived,
        (spent.keys + boosts.keys).associateWith { CategorySummary(it, null, boosts[it] ?: Money.ZERO, spent[it] ?: Money.ZERO) },
    )

    private fun MonthStack.order() = segments.map { it.series }

    @Test
    fun `the largest category is on top, and the order follows each month's amounts`() {
        val stacks = SpendingHistory.stacks(
            listOf(
                month(ym("2026-03"), mapOf(FOOD to eur(300), FUN to eur(50), TRAVEL to eur(120))),
                month(ym("2026-04"), mapOf(FOOD to eur(200), FUN to eur(20), TRAVEL to eur(400))),
            ),
            order,
        )
        assertEquals(listOf(FUN, TRAVEL, FOOD), stacks.months[0].order())
        assertEquals(listOf(FUN, FOOD, TRAVEL), stacks.months[1].order())
        assertEquals(eur(470), stacks.months[0].total)
    }

    @Test
    fun `each segment starts where the one below ends`() {
        val stack = SpendingHistory.stacks(
            listOf(month(ym("2026-03"), mapOf(FOOD to eur(300), FUN to eur(50), TRAVEL to eur(120)))),
            order,
        ).months.single()
        assertEquals(listOf(Money.ZERO, eur(50), eur(170)), stack.segments.map { it.below })
        assertEquals(eur(470), stack.segments.last().top)
    }

    @Test
    fun `equal amounts keep the display order in every month`() {
        val stacks = SpendingHistory.stacks(
            listOf(
                month(ym("2026-03"), mapOf(TRAVEL to eur(10), FOOD to eur(10))),
                month(ym("2026-04"), mapOf(FOOD to eur(10), TRAVEL to eur(10))),
            ),
            order,
        )
        assertEquals(listOf(FOOD, TRAVEL), stacks.months[0].order())
        assertEquals(listOf(FOOD, TRAVEL), stacks.months[1].order())
    }

    @Test
    fun `categories with nothing spent are left out, and a month without spending is an empty bar`() {
        val stacks = SpendingHistory.stacks(
            listOf(
                month(ym("2026-03"), mapOf(FOOD to eur(10), FUN to Money.ZERO)),
                month(ym("2026-04"), emptyMap(), overall = eur(500)),
            ),
            order,
        )
        assertEquals(listOf(FOOD), stacks.months[0].order())
        assertTrue(stacks.months[1].segments.isEmpty())
        assertEquals(Money.ZERO, stacks.months[1].total)
        assertEquals(listOf(FOOD), stacks.series)
    }

    @Test
    fun `the budget includes boosts, and is missing when there is none`() {
        val stacks = SpendingHistory.stacks(
            listOf(
                month(ym("2026-03"), mapOf(FOOD to eur(10)), overall = eur(500), boosts = mapOf(FUN to eur(25))),
                month(ym("2026-04"), mapOf(FOOD to eur(10))),
                month(ym("2026-05"), mapOf(FOOD to eur(10)), overall = Money.ZERO),
            ),
            order,
        )
        assertEquals(eur(525), stacks.months[0].budget)
        assertNull(stacks.months[1].budget)
        assertNull(stacks.months[2].budget)
    }

    @Test
    fun `the bands are listed in display order`() {
        val stacks = SpendingHistory.stacks(
            listOf(month(ym("2026-03"), mapOf(TRAVEL to eur(1), FOOD to eur(3), FUN to eur(2)))),
            order,
        )
        assertEquals(listOf(FOOD, FUN, TRAVEL), stacks.series)
        assertFalse(stacks.hasOthers)
    }

    @Test
    fun `past the palette's size, the categories spent on least share one band`() {
        val ids = (1..10).map { CategoryId("c$it") }
        val displayOrder = ids.withIndex().associate { (i, id) -> id to i }
        // Over both months c2 (20 + 5) and c1 (10 + 20) have spent least; c3 has 30 + 5, and so on.
        val march = ids.associateWith { id -> eur(id.value.drop(1).toLong() * 10) }
        val april = ids.associateWith { eur(5) } + (CategoryId("c1") to eur(20))
        val stacks = SpendingHistory.stacks(listOf(month(ym("2026-03"), march), month(ym("2026-04"), april)), displayOrder)

        assertTrue(stacks.hasOthers)
        assertEquals(ids.drop(2), stacks.series)
        val folded = stacks.months[0].segments.single { it.series == null }
        assertEquals(eur(10 + 20), folded.amount)
        // In April the folded band (20 + 5) is the largest, so it goes on top.
        assertNull(stacks.months[1].segments.last().series)
        assertEquals(eur(25), stacks.months[1].segments.last().amount)
        assertEquals(stacks.months[0].total, march.values.fold(Money.ZERO, Money::plus))
    }

    @Test
    fun `no months gives no bars`() {
        val stacks = SpendingHistory.stacks(emptyList(), order)
        assertTrue(stacks.months.isEmpty())
        assertTrue(stacks.series.isEmpty())
    }
}
