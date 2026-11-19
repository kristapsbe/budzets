package lv.kristapsbe.budzets.domain.forecast

import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.LedgerEntryType
import lv.kristapsbe.budzets.domain.surplus.MonthSurplus
import java.time.YearMonth

/**
 * A forecast month in the surplus chart.
 *
 * @property result what the month would likely add to (positive) or take from the surplus; null
 *   when it has no budget and nothing else counted in it.
 * @property runningTotal the likely surplus after it.
 */
data class SurplusProjection(val month: YearMonth, val result: Estimate?, val runningTotal: Money)

/** Where the month under way would likely leave the surplus once it ends (Q55). */
data class MonthEndSurplus(val result: Estimate, val runningTotal: Money)

object SurplusForecast {

    /**
     * The month under way at its end: its "if closed now" result (Q45) less what is still likely
     * to be spent. The more is spent, the less is added, so the range turns round. Null when
     * closing it gives nothing (no budget).
     */
    fun monthEnd(current: MonthSurplus, rest: Estimate): MonthEndSurplus? {
        val now = current.result ?: return null
        val result = Estimate(now - rest.expected, now - rest.high, now - rest.low)
        return MonthEndSurplus(result, current.runningTotal - now + result.expected)
    }

    /**
     * Each forecast month's likely result, as `SurplusHistory` counts a month: what closing it
     * would give plus its boosts and one-offs. Boosts reserved for a month come back in its closing,
     * so with a budget that is the month's plan (category amounts and the overall amount not
     * assigned to them) less the likely spending, plus one-offs already added for it. Without a
     * budget only its boosts and one-offs count, and with none of those it gives nothing.
     *
     * The total runs on from [start]: the month under way's likely end, where there is one.
     */
    fun months(planned: Map<YearMonth, MonthSummary>, forecast: SpendingForecast, ledger: List<LedgerEntry>, start: Money): List<SurplusProjection> {
        fun net(types: Set<LedgerEntryType>, month: YearMonth): Money? =
            ledger.filter { it.type in types && it.targetMonth == month }.takeIf { it.isNotEmpty() }?.map { it.signedAmount }?.sumMoney()
        var total = start
        return forecast.months.map { month ->
            val summary = planned[month]
            val oneOffs = net(ONE_OFF_TYPES, month)
            val result = if (summary?.overallBase?.isPositive == true) {
                val plan = summary.categoryBaseTotal + summary.unallocatedBase + (oneOffs ?: Money.ZERO)
                val spending = forecast.estimate(month)
                Estimate(plan - spending.expected, plan - spending.high, plan - spending.low)
            } else {
                val boosts = net(BOOST_TYPES, month)
                if (boosts == null && oneOffs == null) null
                else ((boosts ?: Money.ZERO) + (oneOffs ?: Money.ZERO)).let { Estimate(it, it, it) }
            }
            total += result?.expected ?: Money.ZERO
            SurplusProjection(month, result, total)
        }
    }

    private val BOOST_TYPES = setOf(LedgerEntryType.BOOST_RESERVE, LedgerEntryType.BOOST_RELEASE)
    private val ONE_OFF_TYPES = setOf(LedgerEntryType.ONE_OFF_CREDIT, LedgerEntryType.ONE_OFF_REVERSAL)
}
