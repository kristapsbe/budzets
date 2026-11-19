package lv.kristapsbe.budzets.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lv.kristapsbe.budzets.domain.model.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Reading backup files: what is accepted, and why everything else is refused. */
class BackupCodecTest {

    private val createdAt = Instant.parse("2026-10-28T17:40:00.123Z")
    private val t = "2026-03-01T10:00:00Z"

    private val valid = BackupData(
        settings = BackupSettings("EUR"),
        categories = listOf(
            BackupCategory("food", "Food", null, 0, null, t, t),
            BackupCategory("old", "Old", "star", 1, "2026-02", t, t),
        ),
        budgetConfigs = listOf(
            BackupBudgetConfig("b1", null, "2026-03", monthlyTargetMinor = 60_000, createdAt = t, updatedAt = t),
            BackupBudgetConfig("b2", "food", "2026-03", dailyTargetMinor = 1_000, weeklyTargetMinor = 8_000, createdAt = t, updatedAt = t),
        ),
        expenses = listOf(BackupExpense("e1", 1_250, "food", "2026-03-02", "Milk “and” bread\n", t, t)),
        ledger = listOf(
            BackupLedgerEntry("l1", "FINALIZE_CREDIT", "food", 5_000, sourceMonth = "2026-03", createdAt = t),
            BackupLedgerEntry("l2", "BOOST_RESERVE", "food", 1_000, targetMonth = "2026-04", boostId = "x1", createdAt = t),
        ),
        boosts = listOf(BackupBoost("x1", "2026-04", "food", "food", 1_000, "ACTIVE", "l2", t)),
        monthClosures = listOf(
            BackupMonthClosure("2026-03", 1, t, null, 60_000, false, listOf(BackupClosureCategory("food", 31_000, 0, 1_250))),
        ),
    )

    private fun decode(bytes: ByteArray) = BackupCodec.decode(bytes)
    private fun decode(text: String) = decode(text.toByteArray())
    private fun encode(data: BackupData = valid) = BackupCodec.encode(data, createdAt, "1.0")

    private fun failure(result: Outcome<Backup, BackupFailure>): BackupFailure =
        (result as? Outcome.Err)?.error ?: throw AssertionError("expected a failure, got $result")

    private fun ok(result: Outcome<Backup, BackupFailure>): Backup =
        (result as? Outcome.Ok)?.value ?: throw AssertionError("expected success, got $result")

    /** Edits the file's JSON, then fixes the checksum, as a careful hand-edit would. */
    private fun edited(edit: (JsonObject) -> JsonObject, fixChecksum: Boolean = true): ByteArray {
        val root = Json.parseToJsonElement(encode().decodeToString()).jsonObject
        var changed = edit(root)
        if (fixChecksum) changed = JsonObject(changed + ("checksum" to JsonPrimitive(BackupCodec.checksum(changed.getValue("data")))))
        return Json.encodeToString(JsonElement.serializer(), changed).toByteArray()
    }

    private fun JsonObject.withData(edit: (JsonObject) -> JsonObject) = JsonObject(this + ("data" to edit(getValue("data").jsonObject)))

    @Test
    fun `a version 1 file still reads`() {
        val v1 = edited({ root ->
            val categories = root.getValue("data").jsonObject.getValue("categories").jsonArray.map { JsonObject(it.jsonObject - "deletedAt") }
            JsonObject(root.withData { JsonObject(it + ("categories" to JsonArray(categories))) } + ("schemaVersion" to JsonPrimitive(1)))
        })
        val backup = ok(decode(v1))
        assertEquals(1, backup.schemaVersion)
        assertEquals(valid, backup.data)
    }

    @Test
    fun `a version 2 file still reads, without a one-off note`() {
        val noted = valid.copy(settings = BackupSettings("EUR", oneOffNote = "Gifts go to Fun"))
        assertEquals(noted, ok(decode(encode(noted))).data)

        val v2 = edited({ root ->
            JsonObject(root.withData { JsonObject(it + ("settings" to JsonObject(mapOf("currencyCode" to JsonPrimitive("EUR"))))) } + ("schemaVersion" to JsonPrimitive(2)))
        })
        val backup = ok(decode(v2))
        assertEquals(2, backup.schemaVersion)
        assertEquals(null, backup.data.settings.oneOffNote)
    }

    @Test
    fun `deletedAt round-trips, and a deleted category must have left plans`() {
        val deleted = valid.copy(categories = valid.categories + BackupCategory("gone", "Gone", null, 2, "2026-04", t, t, deletedAt = t))
        assertEquals(deleted, ok(decode(encode(deleted))).data)

        val inPlans = valid.copy(categories = valid.categories + BackupCategory("gone", "Gone", null, 2, null, t, t, deletedAt = t))
        assertEquals(BackupProblem.DAMAGED, failure(decode(encode(inPlans))).problem)
    }

    @Test
    fun `a backup reads back exactly`() {
        val backup = ok(decode(encode()))
        assertEquals(valid, backup.data)
        assertEquals(createdAt, backup.createdAt)
        assertEquals("1.0", backup.appVersion)
        assertEquals(BackupCodec.SCHEMA_VERSION, backup.schemaVersion)
        val text = encode().decodeToString()
        assertTrue(text.contains("\"format\": \"budzets-backup\""))
        assertTrue(text.contains("\"schemaVersion\": ${BackupCodec.SCHEMA_VERSION}"))
    }

    @Test
    fun `layout doesn't matter, every value does`() {
        val compact = Json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(encode().decodeToString()))
        ok(decode(compact))

        val changed = encode().decodeToString().replace("\"amountMinor\": 1250", "\"amountMinor\": 1251")
        assertEquals(BackupFailure(BackupProblem.DAMAGED, "checksum mismatch"), failure(decode(changed)))
    }

    @Test
    fun `other files are not backups`() {
        for (text in listOf("", "hello", "[]", "42", """{"name": "something else"}""", """{"format": "other-app"}""")) {
            assertEquals(text, BackupProblem.NOT_A_BACKUP, failure(decode(text)).problem)
        }
        assertEquals(BackupProblem.NOT_A_BACKUP, failure(decode(byteArrayOf(0x50, 0x4b, 0x03, 0x04, -1, -2))).problem)
    }

    @Test
    fun `a backup cut short is damaged`() {
        val bytes = encode()
        assertEquals(BackupProblem.DAMAGED, failure(decode(bytes.copyOf(bytes.size / 2))).problem)
    }

    @Test
    fun `a newer format is refused, an impossible one is damaged`() {
        assertEquals(BackupProblem.TOO_NEW, failure(decode(edited({ JsonObject(it + ("schemaVersion" to JsonPrimitive(BackupCodec.SCHEMA_VERSION + 1))) }))).problem)
        assertEquals(BackupProblem.DAMAGED, failure(decode(edited({ JsonObject(it + ("schemaVersion" to JsonPrimitive(0))) }))).problem)
        assertEquals(BackupProblem.DAMAGED, failure(decode(edited({ JsonObject(it - "schemaVersion") }))).problem)
    }

    @Test
    fun `envelope fields are required`() {
        for (key in listOf("data", "checksum", "createdAt")) {
            assertEquals(key, BackupProblem.DAMAGED, failure(decode(edited({ JsonObject(it - key) }, fixChecksum = key != "checksum" && key != "data"))).problem)
        }
        val badDate = edited({ JsonObject(it + ("createdAt" to JsonPrimitive("yesterday"))) })
        assertEquals("createdAt missing or invalid", failure(decode(badDate)).detail)
    }

    @Test
    fun `missing and unknown fields are damage even with a matching checksum`() {
        val missing = edited({ root -> root.withData { JsonObject(it - "expenses") } })
        assertEquals(BackupProblem.DAMAGED, failure(decode(missing)).problem)
        val unknown = edited({ root -> root.withData { JsonObject(it + ("extra" to JsonPrimitive(1))) } })
        assertEquals(BackupProblem.DAMAGED, failure(decode(unknown)).problem)
    }

    @Test
    fun `too large to be a backup`() {
        assertEquals(BackupProblem.TOO_LARGE, failure(decode(ByteArray(BackupCodec.MAX_BYTES + 1))).problem)
    }

    @Test
    fun `content that doesn't hold together is refused before anything is touched`() {
        val expense = valid.expenses.single()
        val cases = mapOf(
            "unknown currency" to valid.copy(settings = BackupSettings("ZZZ")),
            "expense e1: unknown category" to valid.copy(expenses = listOf(expense.copy(categoryId = "gone"))),
            "duplicate expense e1" to valid.copy(expenses = listOf(expense, expense.copy(note = "again"))),
            "expense e1: date" to valid.copy(expenses = listOf(expense.copy(date = "2026-02-30"))),
            "expense e1: amount out of range" to valid.copy(expenses = listOf(expense.copy(amountMinor = 0))),
            "expense e1: timestamps" to valid.copy(expenses = listOf(expense.copy(createdAt = "noon"))),
            "ledger l1: unknown type" to valid.copy(ledger = valid.ledger.map { if (it.id == "l1") it.copy(type = "GIFT") else it }),
            "ledger l1: amount must be positive" to valid.copy(ledger = valid.ledger.map { if (it.id == "l1") it.copy(amountMinor = -5) else it }),
            "ledger l1: unknown pocket" to valid.copy(ledger = valid.ledger.map { if (it.id == "l1") it.copy(pocketCategoryId = "gone") else it }),
            "boost x1: reservation missing" to valid.copy(ledger = valid.ledger.filter { it.id != "l2" }),
            "boost x1: unknown status" to valid.copy(boosts = valid.boosts.map { it.copy(status = "PAUSED") }),
            "budget b2: unknown category" to valid.copy(budgetConfigs = valid.budgetConfigs.map { if (it.id == "b2") it.copy(categoryId = "gone") else it }),
            "budget b1: target out of range" to valid.copy(budgetConfigs = valid.budgetConfigs.map { if (it.id == "b1") it.copy(monthlyTargetMinor = -1) else it }),
            "duplicate budget version" to valid.copy(budgetConfigs = valid.budgetConfigs + valid.budgetConfigs[0].copy(id = "b3")),
            "category old: archivedFrom" to valid.copy(categories = valid.categories.map { if (it.id == "old") it.copy(archivedFrom = "February") else it }),
            "category food has no name" to valid.copy(categories = valid.categories.map { if (it.id == "food") it.copy(name = " ") else it }),
            "closure 2026-03#1: unknown category" to valid.copy(
                monthClosures = valid.monthClosures.map { m -> m.copy(categories = m.categories.map { it.copy(categoryId = "gone") }) },
            ),
            "duplicate month closure" to valid.copy(monthClosures = valid.monthClosures + valid.monthClosures),
        )
        for ((expected, data) in cases) {
            val failure = failure(decode(encode(data)))
            assertEquals(expected, BackupProblem.DAMAGED, failure.problem)
            assertTrue("$expected / ${failure.detail}", failure.detail.startsWith(expected))
        }
    }

    @Test
    fun `optional values may be absent from older files`() {
        // A file written before a field existed lacks it entirely; it reads as null.
        val withoutMonthly = edited({ root ->
            root.withData { data ->
                val configs = data.getValue("budgetConfigs").jsonArray.map { JsonObject(it.jsonObject - "monthlyTargetMinor") }
                JsonObject(data + ("budgetConfigs" to JsonArray(configs)))
            }
        })
        assertEquals(listOf(null, null), ok(decode(withoutMonthly)).data.budgetConfigs.map { it.monthlyTargetMinor })
    }
}
