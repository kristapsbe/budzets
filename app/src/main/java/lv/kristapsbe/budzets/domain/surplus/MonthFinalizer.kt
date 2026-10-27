package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import java.time.Instant
import java.time.YearMonth

/**
 * Closing a month. [snapshot] keeps the figures the closure was based on, so a closed month
 * stays stable. [revision] increases each time the month is closed again.
 */
data class MonthClosure(
    val month: YearMonth,
    val revision: Int,
    val closedAt: Instant,
    val snapshot: MonthSummary,
    val reopenedAt: Instant? = null,
) {
    val isClosed get() = reopenedAt == null
}

sealed interface ClosureError {
    data object MonthInFuture : ClosureError
    data object NothingToChange : ClosureError
    data object NotClosed : ClosureError

    /** Reopening would take these pockets below zero (or further below, if already negative). */
    data class WouldGoNegative(val pockets: Map<SurplusPocket, Money>) : ClosureError
}

data class ClosureResult(val closure: MonthClosure, val entries: List<LedgerEntry>)

/**
 * What closing [summary]'s month means for the ledger, for the review screen.
 *
 * @property target per pocket, the net effect the month's closure entries should have.
 * @property recorded per pocket, what earlier closes of the month have already posted.
 */
data class CloseReview(
    val summary: MonthSummary,
    val target: Map<SurplusPocket, Money>,
    val recorded: Map<SurplusPocket, Money>,
) {
    /** What closing now would post: [target] minus [recorded], per pocket. */
    val pending: Map<SurplusPocket, Money> get() = closureChanges(target, recorded)
}

/** Per pocket, [target] minus [recorded]; zero differences are omitted. */
fun closureChanges(target: Map<SurplusPocket, Money>, recorded: Map<SurplusPocket, Money>): Map<SurplusPocket, Money> =
    (target.keys + recorded.keys)
        .associateWith { (target[it] ?: Money.ZERO) - (recorded[it] ?: Money.ZERO) }
        .filterValues { !it.isZero }

/**
 * Month closing rules:
 * - Each category's remaining amount is credited (if positive) or debited (if negative) to its own
 *   surplus pocket. A debit may make the balance negative. A boost's unspent money is part of
 *   "remaining", so it lands in the destination category.
 * - The overall base not assigned to categories is credited to [SurplusPocket.Unallocated].
 * - Closing again after changes posts only the per-pocket difference, as adjustment entries.
 */
class MonthFinalizer(
    private val newId: () -> String,
    private val now: () -> Instant,
) {

    /** The net ledger effect closing [summary] should have, per pocket. Zero pockets are omitted. */
    fun preview(summary: MonthSummary): Map<SurplusPocket, Money> = buildMap {
        summary.categories.values.forEach { c ->
            if (!c.remaining.isZero) put(SurplusPocket.Category(c.categoryId), c.remaining)
        }
        if (summary.unallocatedBase.isPositive) put(SurplusPocket.Unallocated, summary.unallocatedBase)
    }

    fun review(ledger: List<LedgerEntry>, summary: MonthSummary): CloseReview =
        CloseReview(summary, preview(summary), SurplusLedger.closureNet(ledger, summary.month))

    /** True when closing [summary] now would post anything new for its month. */
    fun hasChangesSinceClosing(ledger: List<LedgerEntry>, summary: MonthSummary): Boolean =
        deltas(ledger, summary).isNotEmpty()

    /**
     * Closes a month for the first time, re-closes it after changes, or closes it again after a reopen.
     * [latest] is the most recent closure record for the month, if any.
     */
    fun close(
        ledger: List<LedgerEntry>,
        latest: MonthClosure?,
        summary: MonthSummary,
        currentMonth: YearMonth,
    ): Outcome<ClosureResult, ClosureError> {
        val month = summary.month
        if (month > currentMonth) return Outcome.Err(ClosureError.MonthInFuture)

        val deltas = deltas(ledger, summary)
        if (latest != null && latest.isClosed && deltas.isEmpty()) return Outcome.Err(ClosureError.NothingToChange)

        val firstClosure = ledger.none { it.type.isClosure && it.sourceMonth == month }
        val at = now()
        val entries = deltas.map { (pocket, delta) ->
            val type = when {
                firstClosure && delta.isPositive -> LedgerEntryType.FINALIZE_CREDIT
                firstClosure -> LedgerEntryType.FINALIZE_DEBIT
                delta.isPositive -> LedgerEntryType.ADJUSTMENT_CREDIT
                else -> LedgerEntryType.ADJUSTMENT_DEBIT
            }
            val amount = if (delta.isNegative) -delta else delta
            LedgerEntry(LedgerEntryId(newId()), type, pocket, amount, at, sourceMonth = month)
        }
        val closure = MonthClosure(month, (latest?.revision ?: 0) + 1, at, summary)
        return Outcome.Ok(ClosureResult(closure, entries))
    }

    /**
     * Reverses a closed month's net ledger effect. Refused if any pocket would drop below zero
     * because its surplus has already been moved or reserved.
     */
    fun reopen(ledger: List<LedgerEntry>, closure: MonthClosure): Outcome<ClosureResult, ClosureError> {
        if (!closure.isClosed) return Outcome.Err(ClosureError.NotClosed)

        val net = SurplusLedger.closureNet(ledger, closure.month)
        val balances = SurplusLedger.balances(ledger)
        val shortfalls = net.filterValues { it.isPositive }
            .mapValues { (pocket, credit) -> (balances[pocket] ?: Money.ZERO) - credit }
            .filterValues { it.isNegative }
        if (shortfalls.isNotEmpty()) return Outcome.Err(ClosureError.WouldGoNegative(shortfalls))

        val at = now()
        val entries = net.map { (pocket, value) ->
            val type = if (value.isPositive) LedgerEntryType.ADJUSTMENT_DEBIT else LedgerEntryType.ADJUSTMENT_CREDIT
            val amount = if (value.isNegative) -value else value
            LedgerEntry(LedgerEntryId(newId()), type, pocket, amount, at, sourceMonth = closure.month, note = REOPEN_NOTE)
        }
        return Outcome.Ok(ClosureResult(closure.copy(reopenedAt = at), entries))
    }

    private fun deltas(ledger: List<LedgerEntry>, summary: MonthSummary): Map<SurplusPocket, Money> = review(ledger, summary).pending

    companion object {
        /** Marks the adjustment entries a reopen posts, so history can tell them from a re-close. */
        const val REOPEN_NOTE = "Month reopened"
    }
}
