package app.ballistic.assistant.models

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.serialization.Serializable
import app.ballistic.assistant.core.CoreJson

@Serializable data class ManagedModel(val name: String, val bytes: Long)

/** One managed copy. Commit only after a complete copy; failed imports retain the previous file.
 * The document picker source is never moved or deleted. Native load performs full GGUF validation.
 */
class ManagedModelStore(val directory: File) {
    val modelFile get() = directory.resolve("model.gguf")
    private val infoFile get() = directory.resolve("model.json")
    fun current(): ManagedModel? {
        if (!modelFile.isFile) return null
        return try {
            CoreJson.decodeFromString(ManagedModel.serializer(), infoFile.readText()).copy(bytes = modelFile.length())
        } catch (_: Exception) { ManagedModel("Imported GGUF", modelFile.length()) }
    }
    fun import(name: String, input: InputStream, expectedBytes: Long?, cancelled: () -> Boolean, freeBytes: () -> Long = { directory.usableSpace }): ManagedModel {
        directory.mkdirs()
        val tmp = directory.resolve("import.partial")
        val reserve = 64L * 1024 * 1024
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
                    out.write(header); count += header.size
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("Import stopped")
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n > freeBytes() - reserve) throw LocalFailure("Not enough storage for a managed copy")
                        out.write(buffer, 0, n); count += n
                    }
                    out.fd.sync()
                }
            }
            if (cancelled()) throw InterruptedIOException("Import stopped")
            if (expectedBytes != null && count != expectedBytes) throw LocalFailure("Document size changed or import was incomplete")
            val info = ManagedModel(name.filterNot { it.isISOControl() }.take(160).ifBlank { "Imported GGUF" }, count)
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
