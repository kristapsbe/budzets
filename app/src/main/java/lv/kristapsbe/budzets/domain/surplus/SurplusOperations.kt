package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import java.time.Instant
import java.time.YearMonth

sealed interface SurplusError {
    data object NotPositive : SurplusError
    data object SamePocket : SurplusError
    data class InsufficientSurplus(val available: Money) : SurplusError
    data object MonthInPast : SurplusError
    data object MonthClosed : SurplusError
    data object AlreadyReleased : SurplusError
}

data class BoostAllocation(val boost: Boost, val entry: LedgerEntry)
data class BoostRelease(val boost: Boost, val entry: LedgerEntry)

/**
 * Pure planning of surplus movements. Each successful result is a set of records that the
 * persistence layer must commit in a single transaction. Nothing is written here.
 */
class SurplusOperations(
    private val newId: () -> String,
    private val now: () -> Instant,
) {

    /** Moves surplus between pockets. Returns the two linked legs (out, in). */
    fun transfer(
        ledger: List<LedgerEntry>,
        from: SurplusPocket,
        to: SurplusPocket,
        amount: Money,
        note: String? = null,
    ): Outcome<List<LedgerEntry>, SurplusError> {
        if (!amount.isPositive) return Outcome.Err(SurplusError.NotPositive)
        if (from == to) return Outcome.Err(SurplusError.SamePocket)
        checkAvailable(ledger, from, amount)?.let { return Outcome.Err(it) }

        val outId = LedgerEntryId(newId())
        val inId = LedgerEntryId(newId())
        val at = now()
        return Outcome.Ok(
            listOf(
                LedgerEntry(outId, LedgerEntryType.TRANSFER_OUT, from, amount, at, relatedEntryId = inId, note = note),
                LedgerEntry(inId, LedgerEntryType.TRANSFER_IN, to, amount, at, relatedEntryId = outId, note = note),
            )
        )
    }

    /**
     * Reserves surplus from [source] to raise [destination]'s budget for [month]. Allowed for the
     * current month and future months that are not closed. The reservation lowers the source
     * balance, so the same surplus can't be allocated twice.
     */
    fun allocateBoost(
        ledger: List<LedgerEntry>,
        source: SurplusPocket,
        destination: CategoryId,
        month: YearMonth,
        amount: Money,
        currentMonth: YearMonth,
        closedMonths: Set<YearMonth>,
        note: String? = null,
    ): Outcome<BoostAllocation, SurplusError> {
        if (!amount.isPositive) return Outcome.Err(SurplusError.NotPositive)
        if (month < currentMonth) return Outcome.Err(SurplusError.MonthInPast)
        if (month in closedMonths) return Outcome.Err(SurplusError.MonthClosed)
        checkAvailable(ledger, source, amount)?.let { return Outcome.Err(it) }

        val boostId = BoostId(newId())
        val entryId = LedgerEntryId(newId())
        val at = now()
        val entry = LedgerEntry(
            entryId, LedgerEntryType.BOOST_RESERVE, source, amount, at,
            targetMonth = month, boostId = boostId, note = note,
        )
        val boost = Boost(boostId, month, destination, source, amount, BoostStatus.ACTIVE, entryId, at)
        return Outcome.Ok(BoostAllocation(boost, entry))
    }

    /** Cancels a boost whose month is not closed and returns the reservation to its source pocket. */
    fun releaseBoost(boost: Boost, closedMonths: Set<YearMonth>): Outcome<BoostRelease, SurplusError> {
        if (boost.status == BoostStatus.RELEASED) return Outcome.Err(SurplusError.AlreadyReleased)
        if (boost.targetMonth in closedMonths) return Outcome.Err(SurplusError.MonthClosed)

        val at = now()
        val entry = LedgerEntry(
            LedgerEntryId(newId()), LedgerEntryType.BOOST_RELEASE, boost.source, boost.amount, at,
            targetMonth = boost.targetMonth, relatedEntryId = boost.reserveEntryId, boostId = boost.id,
        )
        return Outcome.Ok(BoostRelease(boost.copy(status = BoostStatus.RELEASED, releasedAt = at), entry))
    }

    private fun checkAvailable(ledger: List<LedgerEntry>, pocket: SurplusPocket, amount: Money): SurplusError? {
        val available = SurplusLedger.balance(ledger, pocket)
        return if (available < amount) SurplusError.InsufficientSurplus(available) else null
    }
}
