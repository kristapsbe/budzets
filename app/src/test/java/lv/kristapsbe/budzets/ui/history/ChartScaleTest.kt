package lv.kristapsbe.budzets.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {

    private fun eurScale(maxUnits: Double) = ChartScale.of((maxUnits * 100).toLong(), 100)

    @Test
    fun `the top is the lowest round amount that fits`() {
        assertEquals(ChartScale(75_000, 25_000), eurScale(612.0))
        assertEquals(ChartScale(100_000, 25_000), eurScale(1000.0))
        assertEquals(ChartScale(1_000_000, 250_000), eurScale(901.0 * 10))
        assertEquals(ChartScale(200_000, 50_000), eurScale(1_840.0))
    }

    @Test
    fun `ticks are whole currency units`() {
        assertEquals(listOf(0L, 100L, 200L, 300L), eurScale(2.5).ticks)
        assertEquals(listOf(0L, 500L, 1_000L), eurScale(9.99).ticks)
        eurScale(37.0).ticks.forEach { assertEquals(0L, it % 100) }
    }

    @Test
    fun `a tick between zero and the top, where one fits`() {
        // €5 in one step of €5 would leave no tick between; €6 in steps of €2 has two.
        assertEquals(ChartScale(600, 200), ChartScale.of(401, 100))
        assertEquals(ChartScale(100, 100), ChartScale.of(100, 100))
    }

    @Test
    fun `a currency without a minor unit, and nothing spent`() {
        assertEquals(ChartScale(6_000, 2_000), ChartScale.of(5_400, 1))
        assertEquals(ChartScale(100, 100), ChartScale.of(0, 100))
    }

    @Test
    fun `a scale around zero reaches both sides in round steps`() {
        // -€125 to +€160: steps of €100 need 2 + 2; steps of €250 would span more.
        val scale = ChartScale.around(-12_500, 16_000, 100)
        assertEquals(ChartScale(top = 20_000, step = 10_000, bottom = -20_000), scale)
        assertEquals(listOf(-20_000L, -10_000L, 0L, 10_000L, 20_000L), scale.ticks)
        assertEquals(0.5f, scale.fraction(0))
    }

    @Test
    fun `only below zero`() {
        val scale = ChartScale.around(-40_000, 0, 100)
        assertEquals(0L, scale.top)
        assertTrue(scale.bottom <= -40_000 && scale.ticks.size in 3..5)
        assertEquals(1f, scale.fraction(0))
    }

    @Test
    fun `never more than four steps`() {
        for (max in listOf(1L, 7L, 99L, 101L, 333L, 12_345L, 987_654L, 5_000_001L)) {
            val scale = ChartScale.of(max, 1)
            assertTrue("$max → $scale", scale.top >= max && scale.top / scale.step in 1..4)
        }
    }
}
