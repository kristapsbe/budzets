package lv.kristapsbe.budzets.data.backup

import kotlinx.serialization.Serializable

/*
 * Backup file format, version 1. A JSON object:
 *
 *   format         "budzets-backup", so other JSON files are recognised as "not a backup"
 *   schemaVersion  this format's version (not the database's); a reader refuses newer ones
 *   appVersion     informational
 *   createdAt      ISO-8601 instant
 *   checksum       "sha256:<hex>" of the `data` object as compact JSON (see BackupCodec)
 *   data           everything below
 *
 * Conventions as in the database: money in minor units, months "YYYY-MM", dates "YYYY-MM-DD",
 * and a null category on surplus rows meaning Unallocated. Instants are ISO-8601 here rather than
 * epoch milliseconds, so the file is readable. Rows are in insertion order, which keeps ties in
 * "newest first" lists the same after a restore. No credentials or device identifiers.
 */

@Serializable
data class BackupData(
    val settings: BackupSettings,
    val categories: List<BackupCategory>,
    val budgetConfigs: List<BackupBudgetConfig>,
    val expenses: List<BackupExpense>,
    val ledger: List<BackupLedgerEntry>,
    val boosts: List<BackupBoost>,
    val monthClosures: List<BackupMonthClosure>,
)

@Serializable
data class BackupSettings(val currencyCode: String)

@Serializable
data class BackupCategory(
    val id: String,
    val name: String,
    val iconKey: String? = null,
    val sortOrder: Int,
    val archivedFrom: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** A null [categoryId] is the overall budget. */
@Serializable
data class BackupBudgetConfig(
    val id: String,
    val categoryId: String? = null,
    val effectiveFrom: String,
    val dailyTargetMinor: Long? = null,
    val weeklyTargetMinor: Long? = null,
    val monthlyTargetMinor: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupExpense(
    val id: String,
    val amountMinor: Long,
    val categoryId: String,
    val date: String,
    val note: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupLedgerEntry(
    val id: String,
    val type: String,
    val pocketCategoryId: String? = null,
    val amountMinor: Long,
    val sourceMonth: String? = null,
    val targetMonth: String? = null,
    val relatedEntryId: String? = null,
    val boostId: String? = null,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class BackupBoost(
    val id: String,
    val targetMonth: String,
    val destinationCategoryId: String,
    val sourceCategoryId: String? = null,
    val amountMinor: Long,
    val status: String,
    val reserveEntryId: String,
    val createdAt: String,
    val releasedAt: String? = null,
)

/** One close of a month, with its per-category snapshot. */
@Serializable
data class BackupMonthClosure(
    val month: String,
    val revision: Int,
    val closedAt: String,
    val reopenedAt: String? = null,
    val overallBaseMinor: Long? = null,
    val overallDerived: Boolean,
    val categories: List<BackupClosureCategory>,
)

@Serializable
data class BackupClosureCategory(
    val categoryId: String,
    val baseMinor: Long? = null,
    val boostMinor: Long,
    val spentMinor: Long,
)
