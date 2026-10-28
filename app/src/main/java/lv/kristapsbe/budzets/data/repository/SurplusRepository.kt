package lv.kristapsbe.budzets.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import lv.kristapsbe.budzets.data.local.BudzetsDatabase
import lv.kristapsbe.budzets.data.local.toCategoryRows
import lv.kristapsbe.budzets.data.local.toDb
import lv.kristapsbe.budzets.data.local.toDomain
import lv.kristapsbe.budzets.data.local.toEntity
import lv.kristapsbe.budzets.data.local.toInstant
import lv.kristapsbe.budzets.data.local.toYearMonth
import lv.kristapsbe.budzets.domain.calculation.MonthSummary
import lv.kristapsbe.budzets.domain.model.BoostId
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import lv.kristapsbe.budzets.domain.surplus.Boost
import lv.kristapsbe.budzets.domain.surplus.CloseReview
import lv.kristapsbe.budzets.domain.surplus.ClosureError
import lv.kristapsbe.budzets.domain.surplus.LedgerEntry
import lv.kristapsbe.budzets.domain.surplus.MonthClosure
import lv.kristapsbe.budzets.domain.surplus.MonthFinalizer
import lv.kristapsbe.budzets.domain.surplus.SurplusError
import lv.kristapsbe.budzets.domain.surplus.SurplusLedger
import lv.kristapsbe.budzets.domain.surplus.SurplusOperations
import java.time.Clock
import java.time.Instant
import java.time.YearMonth

/** The latest closure record of a month. [isClosed] is false once it has been reopened. */
data class ClosureStatus(val revision: Int, val closedAt: Instant, val reopenedAt: Instant?) {
    val isClosed: Boolean get() = reopenedAt == null
}

/** Everything the month-close review shows; re-emitted whenever any input changes. */
data class MonthCloseState(val review: CloseReview, val latest: ClosureStatus?)

/**
 * Persists surplus operations. Each one reads the current ledger, asks the domain layer to plan
 * the change, and writes the result in the same transaction. A half-applied transfer or a
 * duplicated month closure therefore can't be committed.
 */
class SurplusRepository(
    private val db: BudzetsDatabase,
    private val budgets: BudgetRepository,
    private val clock: Clock,
    newId: () -> String,
) {
    private val dao = db.surplusDao()
    private val operations = SurplusOperations(newId) { clock.instant() }
    private val finalizer = MonthFinalizer(newId) { clock.instant() }

    val ledger: Flow<List<LedgerEntry>> = dao.observeLedger().map { rows -> rows.map { it.toDomain() } }
    val balances: Flow<Map<SurplusPocket, Money>> = ledger.map(SurplusLedger::balances)
    val boosts: Flow<List<Boost>> = dao.observeBoosts().map { rows -> rows.map { it.toDomain() } }
    val closedMonths: Flow<Set<YearMonth>> = dao.observeClosedMonths().map { rows -> rows.map { it.toYearMonth() }.toSet() }

    suspend fun transfer(
        from: SurplusPocket,
        to: SurplusPocket,
        amount: Money,
        note: String? = null,
    ): Outcome<List<LedgerEntry>, SurplusError> = db.withTransaction {
        val result = operations.transfer(loadLedger(), from, to, amount, note)
        if (result is Outcome.Ok) dao.insertEntries(result.value.map { it.toEntity() })
        result
    }

    suspend fun allocateBoost(
        source: SurplusPocket,
        destination: CategoryId,
        month: YearMonth,
        amount: Money,
        note: String? = null,
    ): Outcome<Boost, SurplusError> = db.withTransaction {
        val result = operations.allocateBoost(
            loadLedger(), source, destination, month, amount, YearMonth.now(clock), loadClosedMonths(), note,
        )
        when (result) {
            is Outcome.Ok -> {
                dao.insertEntries(listOf(result.value.entry.toEntity()))
                dao.insertBoost(result.value.boost.toEntity())
                Outcome.Ok(result.value.boost)
            }
            is Outcome.Err -> result
        }
    }

    suspend fun releaseBoost(id: BoostId): Outcome<Boost, SurplusError> = db.withTransaction {
        val boost = requireNotNull(dao.getBoost(id.value)) { "Unknown boost" }.toDomain()
        when (val result = operations.releaseBoost(boost, loadClosedMonths())) {
            is Outcome.Ok -> {
                dao.insertEntries(listOf(result.value.entry.toEntity()))
                dao.updateBoost(result.value.boost.toEntity())
                Outcome.Ok(result.value.boost)
            }
            is Outcome.Err -> result
        }
    }

    suspend fun latestClosure(month: YearMonth): MonthClosure? = db.withTransaction {
        dao.getLatestClosure(month.toDb())?.let { it.toDomain(dao.getClosureCategories(it.month, it.revision)) }
    }

    /** The month's total surplus effect per pocket if it were closed now (for the review screen). */
    suspend fun previewClose(month: YearMonth): Map<SurplusPocket, Money> = db.withTransaction {
        finalizer.preview(budgets.loadMonthSummary(month))
    }

    suspend fun hasChangesSinceClosing(month: YearMonth): Boolean = db.withTransaction {
        latestClosure(month)?.isClosed == true &&
            finalizer.hasChangesSinceClosing(loadLedger(), budgets.loadMonthSummary(month))
    }

    fun observeCloseState(month: YearMonth): Flow<MonthCloseState> =
        combine(ledger, budgets.observeMonthSummary(month), dao.observeClosures()) { entries, summary, closures ->
            val latest = closures.filter { it.month == month.toDb() }.maxByOrNull { it.revision }
            MonthCloseState(
                review = finalizer.review(entries, summary),
                latest = latest?.let { ClosureStatus(it.revision, it.closedAt.toInstant(), it.reopenedAt?.toInstant()) },
            )
        }

    /**
     * For callers already observing [ledger] and the month's summary: whether a closed month's
     * figures now differ from what closing it posted. Check that the month is closed first.
     */
    fun hasChangesSinceClosing(ledger: List<LedgerEntry>, summary: MonthSummary): Boolean =
        finalizer.hasChangesSinceClosing(ledger, summary)

    /** First close, re-close after changes, or close after a reopen. */
    suspend fun closeMonth(month: YearMonth): Outcome<MonthClosure, ClosureError> = db.withTransaction {
        val result = finalizer.close(loadLedger(), latestClosure(month), budgets.loadMonthSummary(month), YearMonth.now(clock))
        when (result) {
            is Outcome.Ok -> {
                val closure = result.value.closure
                dao.insertEntries(result.value.entries.map { it.toEntity() })
                dao.insertClosure(closure.toEntity())
                dao.insertClosureCategories(closure.toCategoryRows())
                Outcome.Ok(closure)
            }
            is Outcome.Err -> result
        }
    }

    suspend fun reopenMonth(month: YearMonth): Outcome<MonthClosure, ClosureError> = db.withTransaction {
        val latest = latestClosure(month) ?: return@withTransaction Outcome.Err(ClosureError.NotClosed)
        when (val result = finalizer.reopen(loadLedger(), latest)) {
            is Outcome.Ok -> {
                dao.insertEntries(result.value.entries.map { it.toEntity() })
                dao.updateClosure(result.value.closure.toEntity())
                Outcome.Ok(result.value.closure)
            }
            is Outcome.Err -> result
        }
    }

    private suspend fun loadLedger(): List<LedgerEntry> = dao.getLedger().map { it.toDomain() }

    private suspend fun loadClosedMonths(): Set<YearMonth> = dao.getClosedMonths().map { it.toYearMonth() }.toSet()
}
