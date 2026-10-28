package lv.kristapsbe.budzets.data.backup

import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.surplus.BoostStatus
import lv.kristapsbe.budzets.domain.surplus.LedgerEntryType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything the database and the domain rely on, checked before a restore touches anything:
 * unique ids, references that resolve, values that parse, and amounts the domain accepts. A
 * backup that passes can be inserted as-is.
 */
internal object BackupValidator {

    fun problems(data: BackupData): List<String> = buildList {
        fun check(ok: Boolean, problem: () -> String) {
            if (!ok) add(problem())
        }

        check(runCatching { CurrencySpec.of(data.settings.currencyCode) }.isSuccess) { "unknown currency ${data.settings.currencyCode}" }

        val categoryIds = data.categories.map { it.id }.toSet()
        val ledgerIds = data.ledger.map { it.id }.toSet()
        val boostIds = data.boosts.map { it.id }.toSet()
        unique("category", data.categories.map { it.id })?.let(::add)
        unique("budget", data.budgetConfigs.map { it.id })?.let(::add)
        unique("expense", data.expenses.map { it.id })?.let(::add)
        unique("ledger entry", data.ledger.map { it.id })?.let(::add)
        unique("boost", data.boosts.map { it.id })?.let(::add)
        unique("month closure", data.monthClosures.map { "${it.month}#${it.revision}" })?.let(::add)
        unique("budget version", data.budgetConfigs.map { "${it.categoryId}@${it.effectiveFrom}" })?.let(::add)

        for (c in data.categories) {
            check(c.id.isNotBlank()) { "category with a blank id" }
            check(c.name.isNotBlank()) { "category ${c.id} has no name" }
            check(c.archivedFrom == null || isMonth(c.archivedFrom)) { "category ${c.id}: archivedFrom" }
            check(isInstant(c.createdAt) && isInstant(c.updatedAt)) { "category ${c.id}: timestamps" }
        }
        for (b in data.budgetConfigs) {
            check(b.categoryId == null || b.categoryId in categoryIds) { "budget ${b.id}: unknown category" }
            check(isMonth(b.effectiveFrom)) { "budget ${b.id}: effectiveFrom" }
            check(listOf(b.dailyTargetMinor, b.weeklyTargetMinor, b.monthlyTargetMinor).all { it == null || it in 0..AmountParser.MAX_MINOR }) {
                "budget ${b.id}: target out of range"
            }
            check(isInstant(b.createdAt) && isInstant(b.updatedAt)) { "budget ${b.id}: timestamps" }
        }
        for (e in data.expenses) {
            check(e.id.isNotBlank()) { "expense with a blank id" }
            check(e.amountMinor in 1..AmountParser.MAX_MINOR) { "expense ${e.id}: amount out of range" }
            check(e.categoryId in categoryIds) { "expense ${e.id}: unknown category" }
            check(isDate(e.date)) { "expense ${e.id}: date" }
            check(isInstant(e.createdAt) && isInstant(e.updatedAt)) { "expense ${e.id}: timestamps" }
        }
        for (l in data.ledger) {
            check(l.id.isNotBlank()) { "ledger entry with a blank id" }
            check(LedgerEntryType.entries.any { it.name == l.type }) { "ledger ${l.id}: unknown type ${l.type}" }
            check(l.amountMinor > 0) { "ledger ${l.id}: amount must be positive" }
            check(l.pocketCategoryId == null || l.pocketCategoryId in categoryIds) { "ledger ${l.id}: unknown pocket" }
            check(l.sourceMonth == null || isMonth(l.sourceMonth)) { "ledger ${l.id}: sourceMonth" }
            check(l.targetMonth == null || isMonth(l.targetMonth)) { "ledger ${l.id}: targetMonth" }
            check(l.relatedEntryId == null || l.relatedEntryId in ledgerIds) { "ledger ${l.id}: unknown related entry" }
            check(l.boostId == null || l.boostId in boostIds) { "ledger ${l.id}: unknown boost" }
            check(isInstant(l.createdAt)) { "ledger ${l.id}: createdAt" }
        }
        for (b in data.boosts) {
            check(b.id.isNotBlank()) { "boost with a blank id" }
            check(BoostStatus.entries.any { it.name == b.status }) { "boost ${b.id}: unknown status ${b.status}" }
            check(b.amountMinor > 0) { "boost ${b.id}: amount must be positive" }
            check(b.destinationCategoryId in categoryIds) { "boost ${b.id}: unknown destination" }
            check(b.sourceCategoryId == null || b.sourceCategoryId in categoryIds) { "boost ${b.id}: unknown source" }
            check(b.reserveEntryId in ledgerIds) { "boost ${b.id}: reservation missing from the ledger" }
            check(isMonth(b.targetMonth)) { "boost ${b.id}: targetMonth" }
            check(isInstant(b.createdAt) && (b.releasedAt == null || isInstant(b.releasedAt))) { "boost ${b.id}: timestamps" }
        }
        for (m in data.monthClosures) {
            val name = "closure ${m.month}#${m.revision}"
            check(isMonth(m.month)) { "$name: month" }
            check(m.revision >= 1) { "$name: revision" }
            check(m.overallBaseMinor == null || m.overallBaseMinor >= 0) { "$name: overall base" }
            check(isInstant(m.closedAt) && (m.reopenedAt == null || isInstant(m.reopenedAt))) { "$name: timestamps" }
            unique("$name category", m.categories.map { it.categoryId })?.let(::add)
            for (c in m.categories) {
                check(c.categoryId in categoryIds) { "$name: unknown category" }
                check((c.baseMinor ?: 0) >= 0 && c.boostMinor >= 0 && c.spentMinor >= 0) { "$name: negative amount" }
            }
        }
    }

    private fun unique(what: String, keys: List<String>): String? =
        keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let { "duplicate $what $it" }

    private fun isMonth(text: String) = runCatching { YearMonth.parse(text) }.isSuccess
    private fun isDate(text: String) = runCatching { LocalDate.parse(text) }.isSuccess
    private fun isInstant(text: String) = runCatching { Instant.parse(text) }.isSuccess
}
