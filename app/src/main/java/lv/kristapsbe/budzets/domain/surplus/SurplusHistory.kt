package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.sumMoney
import java.time.YearMonth

/**
 * One month in the surplus chart (Q39–Q42).
 *
 * @property result what the month added to (positive) or took from (negative) the saved surplus;
 *   null when it has nothing to give: a month with no budget that was never closed and has no
 *   boost or one-off boost.
 * @property isClosed false while the month isn't closed. [result] is then what closing it now
 *   would give, with its boosts and one-off boosts, which are already in the surplus. That
 *   includes the month under way, as it stands now (Q45).
 * @property runningTotal the surplus after this month.
 * @property boosts the part of [result] from boosts funded by the surplus (reserved, less released).
 * @property oneOffs the part of [result] from one-off boosts.
 */
data class MonthSurplus(
    val month: YearMonth,
    val result: Money?,
    val isClosed: Boolean,
    val runningTotal: Money,
    val boosts: Money = Money.ZERO,
    val oneOffs: Money = Money.ZERO,
) {
    /** The part of [result] from closing the month, actual or projected; null when it has none. */
    val closing: Money? get() = result?.let { it - boosts - oneOffs }
}

object SurplusHistory {

    /** Entries that belong to the month they apply to (their target month) rather than to a close. */
    private val boostTypes = setOf(LedgerEntryType.BOOST_RESERVE, LedgerEntryType.BOOST_RELEASE)
    private val oneOffTypes = setOf(LedgerEntryType.ONE_OFF_CREDIT, LedgerEntryType.ONE_OFF_REVERSAL)

    /**
     * Each month of [summaries] (oldest first, consecutive) as the surplus sees it.
     *
     * A month's result is its closing part plus its boosts and one-off boosts. The closing part of
     * a closed month is what its closes posted; of an open month with a budget, what closing it now
     * would post ([closingEffect]). That goes for the month under way too, as it stands today
     * (Q45), the way the spending chart shows its spending so far. Debt isn't taken off it: debt is
     * a pocket below zero, already in the total. An open month without a budget is left out, as
     * [ClosingSchedule] leaves it out of reminders: closing it would only turn spending into
     * debits. Transfers between pockets don't change the total and are ignored.
     *
     * The running total starts from what earlier months gave. With every finished month closed, it
     * ends at the saved surplus plus what closing the month under way now would add, less any
     * boosts and one-off boosts for later months.
     */
    fun months(
        summaries: List<MonthSummary>,
        ledger: List<LedgerEntry>,
        closedMonths: Set<YearMonth>,
    ): List<MonthSurplus> {
        val first = summaries.firstOrNull()?.month ?: return emptyList()
        val posted = ledger.filter { it.type.isClosure }.netBy { it.sourceMonth }
        val boosts = ledger.filter { it.type in boostTypes }.netBy { it.targetMonth }
        val oneOffs = ledger.filter { it.type in oneOffTypes }.netBy { it.targetMonth }

        // A reopened month's closes net to zero, so earlier months can be summed without asking.
        var total = listOf(posted, boosts, oneOffs).map { byMonth -> byMonth.filterKeys { it < first }.values.sumMoney() }.sumMoney()

        return summaries.map { summary ->
            val month = summary.month
            val isClosed = month in closedMonths
            val closing = when {
                isClosed -> posted[month] ?: Money.ZERO
                summary.overallBase?.isPositive == true -> closingEffect(summary).values.sumMoney()
                else -> null
            }
            val boost = boosts[month] ?: Money.ZERO
            val oneOff = oneOffs[month] ?: Money.ZERO
            val hasEntries = month in boosts || month in oneOffs
            val result = if (closing == null && !hasEntries) null else (closing ?: Money.ZERO) + boost + oneOff
            total += result ?: Money.ZERO
            MonthSurplus(month, result, isClosed, total, boost, oneOff)
        }
    }

    private fun List<LedgerEntry>.netBy(month: (LedgerEntry) -> YearMonth?): Map<YearMonth, Money> =
        mapNotNull { entry -> month(entry)?.let { it to entry.signedAmount } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sumMoney() }
}
