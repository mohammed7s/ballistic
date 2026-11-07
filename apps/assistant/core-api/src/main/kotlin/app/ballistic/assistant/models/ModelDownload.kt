package app.ballistic.assistant.models

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.serialization.Serializable

/** Shipped, pinned catalogue. It is never populated from a model response or remote manifest. */
@Serializable data class DownloadableModel(
    val id: String,
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val license: String,
    val sourcePage: String,
) {
    fun validated() = apply {
        require(id.matches(Regex("[a-z0-9-]{1,80}")))
        require(bytes > 8 && bytes <= 8L * 1024 * 1024 * 1024)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "huggingface.co" && uri.userInfo == null)
        require(Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(uri.path))
    }
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
                throw LocalFailure(if(cancelled()) "Download stopped" else "Cannot reach the model host. Check your connection and retry.")
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
