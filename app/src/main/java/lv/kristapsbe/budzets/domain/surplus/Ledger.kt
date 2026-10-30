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

    /** Money from outside the budget, added to a month. It starts in the unallocated pocket. */
    ONE_OFF_CREDIT(+1),

    /** A removed one-off boost takes its money back out. */
    ONE_OFF_REVERSAL(-1),
}

/**
 * Append-only surplus ledger entry. [amount] is always positive; [type] decides the direction.
 * Closure entries carry [sourceMonth]; boost entries carry [targetMonth] and [boostId]; one-off
 * entries carry [targetMonth].
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

/**
 * Where a pocket's balance came from, so "available", "moved" and "reserved for a boost" are
 * never confused (spec §7.5). All values are signed; [available] is their sum.
 */
data class PocketBreakdown(
    /** Net of month closes: unspent budget minus overspend, including adjustments and reopens. */
    val fromClosing: Money = Money.ZERO,
    /** Net of transfers in and out. */
    val moved: Money = Money.ZERO,
    /** Net reserved for boosts (reservations minus releases); zero or negative. */
    val boosts: Money = Money.ZERO,
    /** Net of one-off boosts still in place. */
    val oneOff: Money = Money.ZERO,
) {
    val available: Money get() = fromClosing + moved + boosts + oneOff
}

/** Balances are always derived from the entries and never stored separately. */
object SurplusLedger {

    fun breakdown(entries: Iterable<LedgerEntry>): Map<SurplusPocket, PocketBreakdown> =
        entries.groupBy { it.pocket }.mapValues { (_, list) ->
            fun net(filter: (LedgerEntryType) -> Boolean) = list.filter { filter(it.type) }.fold(Money.ZERO) { acc, e -> acc + e.signedAmount }
            PocketBreakdown(
                fromClosing = net { it.isClosure },
                moved = net { it == LedgerEntryType.TRANSFER_IN || it == LedgerEntryType.TRANSFER_OUT },
                boosts = net { it == LedgerEntryType.BOOST_RESERVE || it == LedgerEntryType.BOOST_RELEASE },
                oneOff = net { it == LedgerEntryType.ONE_OFF_CREDIT || it == LedgerEntryType.ONE_OFF_REVERSAL },
            )
        }

    fun balances(entries: Iterable<LedgerEntry>): Map<SurplusPocket, Money> =
        entries.groupBy { it.pocket }
            .mapValues { (_, list) -> list.fold(Money.ZERO) { acc, e -> acc + e.signedAmount } }

    fun balance(entries: Iterable<LedgerEntry>, pocket: SurplusPocket): Money =
        entries.filter { it.pocket == pocket }.fold(Money.ZERO) { acc, e -> acc + e.signedAmount }

    /** Net effect, per pocket, of every closure entry for [month]. Zero pockets are omitted. */
    fun closureNet(entries: Iterable<LedgerEntry>, month: YearMonth): Map<SurplusPocket, Money> =
        balances(entries.filter { it.type.isClosure && it.sourceMonth == month }).filterValues { !it.isZero }

    /** Where [pocket]'s money counts: a deleted category's pocket has merged into Unallocated. */
    fun effectivePocket(pocket: SurplusPocket, deleted: Set<CategoryId>): SurplusPocket =
        if (pocket is SurplusPocket.Category && pocket.id in deleted) SurplusPocket.Unallocated else pocket

    /**
     * [entries] as balances see them, per [effectivePocket]. Entries stay on their own pocket in
     * the ledger, so what a month's closes recorded doesn't change when a category is deleted.
     * Closing and reviewing therefore use the raw entries; only balances use this view.
     */
    fun merged(entries: List<LedgerEntry>, deleted: Set<CategoryId>): List<LedgerEntry> =
        if (deleted.isEmpty()) entries else entries.map { it.copy(pocket = effectivePocket(it.pocket, deleted)) }

    /** One-off boosts that haven't been removed, oldest first. */
    fun activeOneOffs(entries: Iterable<LedgerEntry>): List<LedgerEntry> {
        val list = entries.toList()
        val removed = list.filter { it.type == LedgerEntryType.ONE_OFF_REVERSAL }.mapNotNull { it.relatedEntryId }.toSet()
        return list.filter { it.type == LedgerEntryType.ONE_OFF_CREDIT && it.id !in removed }
    }
}
