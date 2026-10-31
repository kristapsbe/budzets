package lv.kristapsbe.budzets.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lv.kristapsbe.budzets.domain.ym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonthStatesTest {

    private val april = ym("2026-04")
    private val may = ym("2026-05")

    @Test
    fun `each month has one state, observed once`() = runTest {
        val observed = mutableListOf<Any>()
        val months = MonthStates(backgroundScope, "loading") { month ->
            observed += month
            flowOf(month.toString())
        }

        assertSame(months[april], months[april])
        assertNotSame(months[april], months[may])
        assertEquals(listOf<Any>(april, may), observed)
    }

    @Test
    fun `a month keeps its last value after its page leaves, and stops observing`() = runTest {
        val source = MutableSharedFlow<String>(replay = 1)
        val months = MonthStates(backgroundScope, "loading") { source }
        val state = months[april]
        assertEquals("loading", state.value)

        val page = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        runCurrent()
        source.emit("figures")
        runCurrent()
        assertEquals("figures", state.value)
        assertEquals(1, source.subscriptionCount.value)

        page.cancel()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("kept running briefly, for a rotation", 1, source.subscriptionCount.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, source.subscriptionCount.value)
        assertEquals("figures", state.value)
    }
}
