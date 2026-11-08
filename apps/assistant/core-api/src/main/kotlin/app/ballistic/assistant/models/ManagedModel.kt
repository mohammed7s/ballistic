package app.ballistic.assistant.models

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.serialization.Serializable
import app.ballistic.assistant.core.CoreJson

@Serializable data class ManagedModel(
    val name: String,
    val bytes: Long,
    val catalogueId: String? = null,
    val sha256: String? = null,
    /** When this phone checked size and SHA-256 against the catalogue (epoch ms); null before this field existed. */
    val verifiedAt: Long? = null,
)

/** One managed copy. Commit only after a complete copy; failed imports retain the previous file.
 * The document picker source is never moved or deleted. Native load performs full format validation;
 * this store checks only the file signature (GGUF v2/v3 or the LiteRT-LM bundle magic).
 */
class ManagedModelStore(val directory: File, val format: ModelFormat = ModelFormat.Gguf) {
    companion object {
        const val STORAGE_RESERVE = 64L * 1024 * 1024
        private val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)
    }
    val modelFile get() = directory.resolve(format.fileName)
    private val infoFile get() = directory.resolve("model.json")
    /** Call only when no import is active (startup or a new serialized operation). */
    fun discardIncompleteImport() { directory.resolve("import.partial").delete(); directory.resolve("info.partial").delete() }
    fun current(): ManagedModel? {
        if (!modelFile.isFile) return null
        return try {
            CoreJson.decodeFromString(ManagedModel.serializer(), infoFile.readText()).copy(bytes = modelFile.length())
        } catch (_: Exception) { ManagedModel("Imported ${format.label}", modelFile.length()) }
    }
    private fun checkHeader(header: ByteArray) {
        when (format) {
            ModelFormat.Gguf -> if (!header.copyOfRange(0,4).contentEquals(byteArrayOf(71,71,85,70)) || header[4].toInt() !in 2..3 || header.sliceArray(5..7).any { it != 0.toByte() }) {
                throw LocalFailure("Not a supported GGUF file (version 2 or 3 required)")
            }
            ModelFormat.LiteRtLm -> if (!header.contentEquals(LITERTLM_MAGIC)) throw LocalFailure("Not a LiteRT-LM bundle")
        }
    }
    fun import(name: String, input: InputStream, expectedBytes: Long?, cancelled: () -> Boolean, freeBytes: () -> Long = { directory.usableSpace }, expectedSha256: String? = null, catalogueId: String? = null, onProgress: (Long) -> Unit = {}, now: () -> Long = System::currentTimeMillis): ManagedModel {
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
                        if (n < 0) throw LocalFailure("Not a complete ${format.label} file")
                        offset += n
                    }
                    checkHeader(header)
                    out.write(header); digest?.update(header); count += header.size
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("Import stopped")
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n > freeBytes() - reserve) throw LocalFailure("Not enough storage for a managed copy")
                        if (expectedBytes != null && count + n > expectedBytes) throw LocalFailure("Model file exceeds the expected size")
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
            val info = ManagedModel(name.filterNot { it.isISOControl() }.take(160).ifBlank { "Imported ${format.label}" }, count, catalogueId, actualHash,
                verifiedAt = if (expectedSha256 != null) now() else null)
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

/**
 * Every downloaded catalogue file has its own directory, so several can be installed and the
 * owner switches between them. The original single slot (`root/model.gguf`) keeps the owner's own
 * imported GGUF. A starter download made by an earlier build is moved, not re-downloaded.
 */
class ModelLibrary(val root: File, val catalogue: List<DownloadableModel>) {
    val imported = ManagedModelStore(root)
    fun store(entry: DownloadableModel) = ManagedModelStore(root.resolve("catalogue").resolve(entry.id), entry.format)
    fun entry(id: String?): DownloadableModel? = catalogue.firstOrNull { it.id == id }

    /** A verified catalogue copy: identity, digest and byte count all match the shipped entry. */
    fun installed(entry: DownloadableModel): ManagedModel? =
        store(entry).current()?.takeIf { it.catalogueId == entry.id && it.sha256 == entry.sha256 && it.bytes == entry.bytes }

    /** The weights for an active-model id: a verified catalogue copy or the imported GGUF. */
    fun resolve(id: String?): Pair<ManagedModel, File>? {
        if (id == null || id == DownloadableModel.IMPORTED_MODEL_ID) return imported.current()?.let { it to imported.modelFile }
        val entry = entry(id) ?: return null
        return installed(entry)?.let { it to store(entry).modelFile }
    }

    fun usedBytes(): Long = (catalogue.map { store(it).modelFile } + imported.modelFile).filter { it.isFile }.sumOf { it.length() }

    /** Call at startup, before any import or download is active. */
    fun prepare() {
        imported.discardIncompleteImport()
        catalogue.forEach { store(it).discardIncompleteImport() }
        migrateLegacyDownload()
    }

    private fun migrateLegacyDownload() {
        val legacyInfo = root.resolve("model.json")
        val legacy = imported.current() ?: return
        val entry = entry(legacy.catalogueId) ?: return
        if (entry.format != ModelFormat.Gguf || legacy.sha256 != entry.sha256 || legacy.bytes != entry.bytes) return
        val target = store(entry)
        if (target.modelFile.exists()) return
        target.directory.mkdirs()
        // Same filesystem: renames, not copies. A crash in between leaves an unidentified GGUF in
        // the catalogue slot (not shown as verified) and the legacy slot empty; nothing is deleted.
        check(imported.modelFile.renameTo(target.modelFile)) { "Could not move the downloaded model" }
        check(legacyInfo.renameTo(target.directory.resolve("model.json"))) { "Could not move model metadata" }
    }
}
