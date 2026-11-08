package app.ballistic.assistant.models

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import app.ballistic.assistant.core.CoreJson

/** Which in-process engine reads the weights. Both sit behind the same Models port. */
@Serializable enum class ModelRuntime(val label: String) {
    @SerialName("llama_cpp") LlamaCpp("llama.cpp"),
    @SerialName("litert_lm") LiteRtLm("LiteRT-LM"),
}

/** The hardware a catalogue file was built for. A Tensor G5 bundle is compiled for that NPU only. */
@Serializable enum class Accelerator(val label: String) {
    @SerialName("cpu") Cpu("CPU"),
    @SerialName("gpu") Gpu("GPU"),
    @SerialName("tensor_g5_npu") TensorG5Npu("Tensor G5 NPU"),
}

/** The backend an engine is asked to start on. Never changed automatically after a failure. */
@Serializable enum class ComputeBackend { CPU, GPU, NPU }

enum class ModelFormat(val fileName: String, val label: String) {
    Gguf("model.gguf", "GGUF"),
    LiteRtLm("model.litertlm", "LiteRT-LM bundle"),
}

/** Shipped, pinned catalogue. It is never populated from a model response or remote manifest. */
@Serializable data class DownloadableModel(
    val id: String,
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val license: String,
    val sourcePage: String,
    val runtime: ModelRuntime = ModelRuntime.LlamaCpp,
    val accelerator: Accelerator = Accelerator.Cpu,
    val fileName: String = "",
    /** The publisher requires a signed-in account that accepted its terms; no in-app download. */
    val gated: Boolean = false,
    val note: String = "",
) {
    val format: ModelFormat get() = if (runtime == ModelRuntime.LiteRtLm) ModelFormat.LiteRtLm else ModelFormat.Gguf
    val publisherFileName: String get() = fileName.ifEmpty { URI(url).path.substringAfterLast('/') }

    /** Owner-selectable backends, default first. There is no automatic fallback between them. */
    val backends: List<ComputeBackend> get() = when {
        runtime == ModelRuntime.LlamaCpp -> listOf(ComputeBackend.CPU)
        accelerator == Accelerator.TensorG5Npu -> listOf(ComputeBackend.NPU)
        accelerator == Accelerator.Gpu -> listOf(ComputeBackend.GPU, ComputeBackend.CPU)
        else -> listOf(ComputeBackend.CPU, ComputeBackend.GPU)
    }

    fun validated() = apply {
        require(id.matches(Regex("[a-z0-9-]{1,80}")) && id != IMPORTED_MODEL_ID)
        require(name.isNotBlank() && name.length <= 80)
        require(bytes > 8 && bytes <= 8L * 1024 * 1024 * 1024)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "huggingface.co" && uri.userInfo == null && uri.query == null)
        require(Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(uri.path))
        require(fileName.isEmpty() || uri.path.endsWith("/$fileName"))
        val extension = if (runtime == ModelRuntime.LiteRtLm) ".litertlm" else ".gguf"
        require(publisherFileName.endsWith(extension)) { "Catalogue file does not match its runtime" }
        // The embedded llama.cpp build is CPU-only; GPU/NPU targets need LiteRT-LM.
        require(runtime == ModelRuntime.LiteRtLm || accelerator == Accelerator.Cpu)
        require(sourcePage.startsWith("https://huggingface.co/"))
        require(note.length <= 400)
    }

    companion object {
        /** Reserved identity for the owner's own imported GGUF (not a catalogue entry). */
        const val IMPORTED_MODEL_ID = "imported"
    }
}

/** Parses and validates the shipped catalogue; any invalid entry rejects the whole file. */
fun parseModelCatalogue(text: String): List<DownloadableModel> {
    val entries = CoreJson.decodeFromString(ListSerializer(DownloadableModel.serializer()), text)
    require(entries.isNotEmpty()) { "Empty model catalogue" }
    entries.forEach { it.validated() }
    require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate catalogue id" }
    require(entries.map { it.sha256 }.toSet().size == entries.size) { "Duplicate catalogue file" }
    return entries
}

data class DownloadProgress(val received: Long, val total: Long) {
    val fraction: Float get() = (received.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
}

interface ModelDownloadSource {
    /** Returns a stream owned by the caller; close must release the connection. */
    fun open(url: String, expectedBytes: Long, cancelled: () -> Boolean): InputStream
    fun cancel()
}

/** No keys, request content, remote configuration, retries or insecure redirects. */
class HttpsModelDownloadSource : ModelDownloadSource {
    @Volatile private var active: HttpURLConnection? = null
    override fun cancel() { active?.disconnect() }

    override fun open(url: String, expectedBytes: Long, cancelled: () -> Boolean): InputStream {
        var target = URI(url).toURL()
        repeat(6) { hop ->
            if (cancelled()) throw LocalFailure("Download stopped")
            requireDownloadUrl(target)
            val connection = target.openConnection() as HttpURLConnection
            active = connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                if (cancelled()) throw LocalFailure("Download stopped")
                val status = connection.responseCode
                if (status in listOf(301,302,303,307,308)) {
                    val location = connection.getHeaderField("Location") ?: throw LocalFailure("Model host returned an invalid redirect")
                    if (hop == 5) throw LocalFailure("Model host redirected too many times")
                    target = target.toURI().resolve(location).toURL()
                    connection.disconnect()
                    return@repeat
                }
                if (status != 200) throw LocalFailure("Model download failed (HTTP $status). Try again later.")
                val length = connection.contentLengthLong
                if (length >= 0 && length != expectedBytes) throw LocalFailure("Model download size does not match the catalogue")
                val stream = connection.inputStream
                return object : java.io.FilterInputStream(stream) {
                    override fun close() {
                        try { super.close() } finally {
                            connection.disconnect()
                            if (active === connection) active = null
                        }
                    }
                }
            } catch (e: Exception) {
                connection.disconnect()
                if (active === connection) active = null
                if (e is LocalFailure) throw e
                throw LocalFailure(if(cancelled()) "Download stopped" else when (e) {
                    is java.net.UnknownHostException -> "Cannot find the model host. Connect to Wi-Fi or mobile data, or check your DNS settings, then retry. USB debugging does not provide internet access."
                    is java.net.SocketTimeoutException -> "The model host timed out. Check your internet connection and retry."
                    is javax.net.ssl.SSLException -> "Could not establish a secure connection to the model host. Check the phone's date and network connection."
                    else -> "Cannot reach the model host. Check your internet connection and Ballistic's network permission, then retry."
                })
            }
        }
        throw LocalFailure("Model download could not start")
    }

    companion object {
        internal fun requireDownloadUrl(url: URL) {
            val host = url.host.lowercase()
            if (url.protocol != "https" || url.userInfo != null || (url.port != -1 && url.port != 443) ||
                !(host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co"))) {
                throw LocalFailure("Model host redirected outside the supported secure download service")
            }
        }
    }
}

class ModelDownloader(private val source: ModelDownloadSource) {
    fun cancel() = source.cancel()
    fun install(model: DownloadableModel, store: ManagedModelStore, cancelled: () -> Boolean,
                progress: (DownloadProgress) -> Unit, freeBytes: () -> Long = { store.directory.usableSpace }): ManagedModel {
        model.validated()
        if (model.gated) throw LocalFailure("This publisher requires a Hugging Face account that accepted its terms. Download the file on a computer, then use Import verified file.")
        if (model.format != store.format) throw LocalFailure("Model storage does not match this catalogue entry")
        store.directory.mkdirs()
        store.discardIncompleteImport()
        // The old model remains in place while the replacement is downloaded and verified.
        if (freeBytes() - ManagedModelStore.STORAGE_RESERVE < model.bytes) throw LocalFailure("Not enough storage for this download; free space or remove the current copy")
        if(cancelled()) throw LocalFailure("Download stopped")
        progress(DownloadProgress(0,model.bytes))
        return source.open(model.url,model.bytes,cancelled).use { stream ->
            store.import(model.name,stream,model.bytes,cancelled,freeBytes,
                expectedSha256 = model.sha256, catalogueId = model.id,
                onProgress = { progress(DownloadProgress(it,model.bytes)) })
        }
    }
}
