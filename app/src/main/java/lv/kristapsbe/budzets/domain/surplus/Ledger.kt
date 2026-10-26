package lv.kristapsbe.budzets.domain.surplus

import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.LedgerEntryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import java.time.Instant
import java.time.YearMonth

enum class LedgerEntryType(val sign: Int, val isClosure: Boolean = false) {
    /** Unspent budget when a month is first closed. */
    FINALIZE_CREDIT(+1, isClosure = true),

    /** A category's overspend when a month is first closed. It may push the balance negative. */
    FINALIZE_DEBIT(-1, isClosure = true),

    /** Delta posted when a month is re-closed after changes, or reversed on reopen. */
    ADJUSTMENT_CREDIT(+1, isClosure = true),
    ADJUSTMENT_DEBIT(-1, isClosure = true),

    TRANSFER_OUT(-1),
    TRANSFER_IN(+1),

    /** Surplus reserved to fund a month-specific category boost. */
    BOOST_RESERVE(-1),

    /** A released boost returns its reservation. */
    BOOST_RELEASE(+1),
}

/**
 * Append-only surplus ledger entry. [amount] is always positive; [type] decides the direction.
 * Closure entries carry [sourceMonth]; boost entries carry [targetMonth] and [boostId].
 */
data class LedgerEntry(
    val id: LedgerEntryId,
    val type: LedgerEntryType,
    val pocket: SurplusPocket,
    val amount: Money,
    val createdAt: Instant,
    val sourceMonth: YearMonth? = null,
    val targetMonth: YearMonth? = null,
    val relatedEntryId: LedgerEntryId? = null,
    val boostId: BoostId? = null,
    val note: String? = null,
) {
    init {
        require(amount.isPositive) { "ledger amounts must be positive" }
    }

    val signedAmount: Money get() = if (type.sign > 0) amount else -amount
}

enum class BoostStatus { ACTIVE, RELEASED }

/** Extra spending capacity for one category in one month, funded from [source]. */
data class Boost(
    val id: BoostId,
    val targetMonth: YearMonth,
    val destination: CategoryId,
    val source: SurplusPocket,
    val amount: Money,
    val status: BoostStatus,
    val reserveEntryId: LedgerEntryId,
    val createdAt: Instant,
    val releasedAt: Instant? = null,
)

/** Balances are always derived from the entries and never stored separately. */
object SurplusLedger {

    fun balances(entries: Iterable<LedgerEntry>): Map<SurplusPocket, Money> =
        entries.groupBy { it.pocket }
            .mapValues { (_, list) -> list.fold(Money.ZERO) { acc, e -> acc + e.signedAmount } }

    fun balance(entries: Iterable<LedgerEntry>, pocket: SurplusPocket): Money =
        entries.filter { it.pocket == pocket }.fold(Money.ZERO) { acc, e -> acc + e.signedAmount }

    /** Net effect, per pocket, of every closure entry for [month]. Zero pockets are omitted. */
    fun closureNet(entries: Iterable<LedgerEntry>, month: YearMonth): Map<SurplusPocket, Money> =
        balances(entries.filter { it.type.isClosure && it.sourceMonth == month }).filterValues { !it.isZero }
}
