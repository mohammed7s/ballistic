package app.ballistic.assistant.models

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.serialization.Serializable
import app.ballistic.assistant.core.CoreJson

@Serializable data class ManagedModel(val name: String, val bytes: Long, val catalogueId: String? = null, val sha256: String? = null)

/** One managed copy. Commit only after a complete copy; failed imports retain the previous file.
 * The document picker source is never moved or deleted. Native load performs full GGUF validation.
 */
class ManagedModelStore(val directory: File) {
    companion object { const val STORAGE_RESERVE = 64L * 1024 * 1024 }
    val modelFile get() = directory.resolve("model.gguf")
    private val infoFile get() = directory.resolve("model.json")
    /** Call only when no import is active (startup or a new serialized operation). */
    fun discardIncompleteImport() { directory.resolve("import.partial").delete(); directory.resolve("info.partial").delete() }
    fun current(): ManagedModel? {
        if (!modelFile.isFile) return null
        return try {
            CoreJson.decodeFromString(ManagedModel.serializer(), infoFile.readText()).copy(bytes = modelFile.length())
        } catch (_: Exception) { ManagedModel("Imported GGUF", modelFile.length()) }
    }
    fun import(name: String, input: InputStream, expectedBytes: Long?, cancelled: () -> Boolean, freeBytes: () -> Long = { directory.usableSpace }, expectedSha256: String? = null, catalogueId: String? = null, onProgress: (Long) -> Unit = {}): ManagedModel {
        directory.mkdirs()
        val tmp = directory.resolve("import.partial")
        val reserve = STORAGE_RESERVE
        val digest = expectedSha256?.let { java.security.MessageDigest.getInstance("SHA-256") }
        var lastProgress = 0L
        try {
            if (expectedBytes != null && expectedBytes > freeBytes() - reserve) throw LocalFailure("Not enough storage for a managed copy")
            var count = 0L
            input.use { source ->
                tmp.outputStream().use { out ->
                    val header = ByteArray(8)
                    var offset = 0
                    while (offset < header.size) {
                        val n = source.read(header, offset, header.size - offset)
                        if (n < 0) throw LocalFailure("Not a complete GGUF file")
                        offset += n
                    }
                    if (!header.copyOfRange(0,4).contentEquals(byteArrayOf(71,71,85,70)) || header[4].toInt() !in 2..3 || header.sliceArray(5..7).any { it != 0.toByte() }) {
                        throw LocalFailure("Not a supported GGUF file (version 2 or 3 required)")
                    }
                    out.write(header); digest?.update(header); count += header.size
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("Import stopped")
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n > freeBytes() - reserve) throw LocalFailure("Not enough storage for a managed copy")
                        if (expectedBytes != null && count + n > expectedBytes) throw LocalFailure("Model download exceeds the expected size")
                        out.write(buffer, 0, n); digest?.update(buffer, 0, n); count += n
                        val now = System.nanoTime()
                        if (now - lastProgress >= 200_000_000) { onProgress(count); lastProgress = now }
                    }
                    out.fd.sync()
                }
            }
            if (cancelled()) throw InterruptedIOException("Import stopped")
            if (expectedBytes != null && count != expectedBytes) throw LocalFailure("Document size changed or import was incomplete")
            val actualHash = digest?.digest()?.joinToString("") { "%02x".format(it.toInt() and 255) }
            if (expectedSha256 != null && actualHash != expectedSha256) throw LocalFailure("Model integrity check failed. The previous model was kept; retry the download.")
            onProgress(count)
            if (cancelled()) throw InterruptedIOException("Import stopped")
            val info = ManagedModel(name.filterNot { it.isISOControl() }.take(160).ifBlank { "Imported GGUF" }, count, catalogueId, actualHash)
            // Android's rename in the same directory replaces atomically; no partial model is exposed.
            val infoTmp = directory.resolve("info.partial")
            infoTmp.writeText(CoreJson.encodeToString(ManagedModel.serializer(), info))
            // In a crash between these renames identity is explicitly unknown, never stale.
            check(!infoFile.exists() || infoFile.delete()) { "Could not replace model metadata" }
            check(tmp.renameTo(modelFile)) { "Could not install managed model" }
            check(infoTmp.renameTo(infoFile)) { "Could not save model metadata" }
            return info
        } finally { tmp.delete(); directory.resolve("info.partial").delete(); input.close() }
    }
    fun remove() {
        check(!modelFile.exists() || modelFile.delete()) { "Could not remove managed model" }
        infoFile.delete()
    }
}
