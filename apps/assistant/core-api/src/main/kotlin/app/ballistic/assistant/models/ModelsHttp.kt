// Models port over an OpenAI-compatible HTTP endpoint (`POST {base_url}/chat/completions`): one
// client for Gemini's OpenAI endpoint, llama.cpp server, Ollama and vLLM (core/README.md §4, §12).
// Plain java.net so it runs on Android and in JVM tests.
//
// Can: route each role to its own endpoint and model, report token usage, say whether a role's
// requests leave the device. Cannot: run a model in-process (LiteRT-LM / llama.cpp backends
// come later behind the same port), stream, or retry; a failed call is a PortException and core
// decides what follows.
//
// The API key comes from [apiKey] (the app reads it from its encrypted store). It is never
// stored in the config file, never part of an exception message and never logged.

package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.Completion
import app.ballistic.assistant.core.CoreJson
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.Models
import app.ballistic.assistant.core.PortException
import app.ballistic.assistant.core.RecordJson
import app.ballistic.assistant.core.Usage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class Endpoint(
    /** e.g. `https://generativelanguage.googleapis.com/v1beta/openai/` or `http://127.0.0.1:8080/v1`. */
    @SerialName("base_url") val baseUrl: String,
    val model: String,
)

/**
 * The app's `models.json` (app-private). Roles left out fall back as core/README.md §12's example
 * does: reader to planner, judge to reader, verifier to planner.
 */
@Serializable
data class ModelsConfig(
    val planner: Endpoint,
    val reader: Endpoint? = null,
    val judge: Endpoint? = null,
    val verifier: Endpoint? = null,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 60,
) {
    fun endpoint(role: ModelRole): Endpoint = when (role) {
        ModelRole.Planner -> planner
        ModelRole.Reader -> reader ?: planner
        ModelRole.Judge -> judge ?: reader ?: planner
        ModelRole.Verifier -> verifier ?: planner
    }

    fun encode(): String = CoreJson.encodeToString(serializer(), this)

    companion object {
        fun decodeOrNull(text: String?): ModelsConfig? = try {
            text?.let { CoreJson.decodeFromString(serializer(), it) }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** True unless the endpoint is this device (loopback). Shown in the UI for every role. */
fun Endpoint.leavesDevice(): Boolean {
    val host = try {
        URI(baseUrl).host?.lowercase()
    } catch (e: IllegalArgumentException) {
        null
    } ?: return true
    return host != "localhost" && host != "127.0.0.1" && host != "[::1]" && host != "::1"
}

@Serializable
private data class ChatRequest(val model: String, val messages: List<ChatMessage>, @SerialName("max_tokens") val maxTokens: Int)

@Serializable
private data class ChatResponse(val choices: List<Choice> = emptyList(), val usage: Usage? = null) {
    @Serializable
    data class Choice(val message: Message? = null)

    @Serializable
    data class Message(val content: String? = null)
}

class ModelsHttp(
    private val config: () -> ModelsConfig?,
    private val apiKey: () -> String?,
) : Models {
    override fun complete(role: ModelRole, messages: List<ChatMessage>, maxTokens: Int): Completion {
        val cfg = config() ?: throw PortException.Host("no model endpoint configured (Settings > Models)")
        val endpoint = cfg.endpoint(role)
        val key = apiKey()
        val url = try {
            URI(endpoint.baseUrl.trimEnd('/') + "/chat/completions").toURL()
        } catch (e: Exception) {
            throw PortException.Host("invalid base_url for ${role.name.lowercase()}")
        }
        val body = CoreJson.encodeToString(ChatRequest.serializer(), ChatRequest(endpoint.model, messages, maxTokens))
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw PortException.Disconnected("cannot open ${url.host}")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = cfg.timeoutSeconds * 1000
            connection.readTimeout = cfg.timeoutSeconds * 1000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (!key.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                throw PortException.Host("${url.host} returned HTTP $status: ${scrub(text, key).take(200)}")
            }
            val response = try {
                RecordJson.decodeFromString(ChatResponse.serializer(), text)
            } catch (e: SerializationException) {
                throw PortException.Malformed("${url.host} returned a body that is not a chat completion")
            }
            val content = response.choices.firstOrNull()?.message?.content
                ?: throw PortException.Malformed("${url.host} returned no message content")
            return Completion(content, response.usage ?: Usage())
        } catch (e: IOException) {
            throw PortException.Disconnected("${url.host}: ${e::class.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    /** Removes the key from text that came back from the server, in case it echoes headers. */
    private fun scrub(text: String, key: String?): String =
        if (key.isNullOrEmpty()) text else text.replace(key, "[key]")
}
