package lv.kristapsbe.budzets.data.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Files the user picked through the system file picker (Drive, the phone, or another provider). */
interface BackupDocuments {
    /**
     * Writes [bytes] to [uri], then reads the file back. False if the provider hands back
     * something else. Throws [IOException] if the write itself fails.
     */
    suspend fun write(uri: Uri, bytes: ByteArray): Boolean

    /** At most [limit] bytes; a longer file comes back truncated, so callers can call it too large. */
    suspend fun read(uri: Uri, limit: Int): ByteArray
}

/**
 * Through the Storage Access Framework (the Phase 0 choice), so no Google sign-in or Cloud
 * project is needed. With Drive, a successful write means Drive accepted the file; it uploads it
 * afterwards in the background.
 */
class SafBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {

    override suspend fun write(uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // "w" rather than "wt": the file was just created, and not every provider supports "wt".
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream for $uri")
        out.use { it.write(bytes) }
        resolver.openInputStream(uri)?.use { it.readUpTo(bytes.size + 1) }.contentEquals(bytes)
    }

    override suspend fun read(uri: Uri, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("no input stream for $uri")
        input.use { it.readUpTo(limit) }
    }
}

/** `readNBytes` would do, but needs API 33. */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
