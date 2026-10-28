package lv.kristapsbe.budzets.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Outcome
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

/** A backup file that parsed and passed every check. Only [BackupCodec.decode] makes one from a file. */
class Backup internal constructor(
    val schemaVersion: Int,
    val appVersion: String?,
    val createdAt: Instant,
    val data: BackupData,
) {
    val currency: CurrencySpec get() = CurrencySpec.of(data.settings.currencyCode)
}

enum class BackupProblem {
    /** Not JSON, or JSON that isn't a Budzets backup. */
    NOT_A_BACKUP,

    /** Written by a newer app version in a format this one doesn't know. */
    TOO_NEW,

    /** Checksum mismatch, missing or unknown fields, or content that doesn't hold together. */
    DAMAGED,

    TOO_LARGE,
}

/** [detail] is for logs and tests; the user is shown [problem]. */
data class BackupFailure(val problem: BackupProblem, val detail: String)

/**
 * Reads and writes the backup file format described in `BackupFormat.kt`. Decoding never
 * touches app data: it only says whether a file can be restored.
 */
object BackupCodec {
    const val FORMAT = "budzets-backup"
    const val SCHEMA_VERSION = 1

    /** Years of daily expenses come to a few MB; anything far larger isn't ours. */
    const val MAX_BYTES = 64 * 1024 * 1024

    private val compact = Json { encodeDefaults = true }
    private val pretty = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(data: BackupData, createdAt: Instant, appVersion: String): ByteArray {
        val dataJson = compact.encodeToJsonElement(BackupData.serializer(), data)
        val envelope = buildJsonObject {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("appVersion", appVersion)
            put("createdAt", createdAt.toString())
            put("checksum", checksum(dataJson))
            put("data", dataJson)
        }
        return pretty.encodeToString(JsonElement.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Outcome<Backup, BackupFailure> {
        if (bytes.size > MAX_BYTES) return fail(BackupProblem.TOO_LARGE, "${bytes.size} bytes")
        val text = bytes.toString(Charsets.UTF_8)
        val root = try {
            compact.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            // A backup cut short (an interrupted download, say) is damaged rather than foreign.
            if (text.contains("\"$FORMAT\"")) return fail(BackupProblem.DAMAGED, "not valid JSON")
            null
        } ?: return fail(BackupProblem.NOT_A_BACKUP, "not a JSON object")
        if (root.string("format") != FORMAT) return fail(BackupProblem.NOT_A_BACKUP, "no format marker")

        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return fail(BackupProblem.DAMAGED, "schemaVersion missing")
        if (version > SCHEMA_VERSION) return fail(BackupProblem.TOO_NEW, "schemaVersion $version")
        if (version < 1) return fail(BackupProblem.DAMAGED, "schemaVersion $version")

        val dataJson = root["data"] as? JsonObject ?: return fail(BackupProblem.DAMAGED, "data missing")
        if (root.string("checksum") != checksum(dataJson)) return fail(BackupProblem.DAMAGED, "checksum mismatch")
        val createdAt = root.string("createdAt")?.let(::parseInstant)
            ?: return fail(BackupProblem.DAMAGED, "createdAt missing or invalid")

        // Version 1 is the only format so far. A later version keeps reading older ones by
        // upgrading their `data` here before it is decoded.
        val data = try {
            compact.decodeFromJsonElement(BackupData.serializer(), dataJson)
        } catch (e: SerializationException) {
            return fail(BackupProblem.DAMAGED, e.message ?: "unreadable data")
        } catch (e: IllegalArgumentException) {
            return fail(BackupProblem.DAMAGED, e.message ?: "unreadable data")
        }
        BackupValidator.problems(data).firstOrNull()?.let { return fail(BackupProblem.DAMAGED, it) }
        return Outcome.Ok(Backup(version, root.string("appVersion"), createdAt, data))
    }

    /** Over the compact form, so whitespace and indentation don't matter but every value does. */
    internal fun checksum(data: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(compact.encodeToString(JsonElement.serializer(), data).toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun parseInstant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }

    private fun fail(problem: BackupProblem, detail: String) = Outcome.Err(BackupFailure(problem, detail))
}
