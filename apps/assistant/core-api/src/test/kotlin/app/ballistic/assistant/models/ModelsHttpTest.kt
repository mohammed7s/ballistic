// The HTTP client against a local server speaking the OpenAI chat-completions shape.

package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.PortException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ModelsHttpTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastBody = ""
    private var lastAuth: String? = null
    private var lastPath = ""

    private fun serve(status: Int, reply: String) {
        server.createContext("/") { exchange ->
            lastPath = exchange.requestURI.path
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val base get() = "http://127.0.0.1:${server.address.port}/v1/"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun sendsChatCompletionAndReadsUsage() {
        serve(200, """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\"action\":\"home\"}"}}],"usage":{"prompt_tokens":812,"completion_tokens":9,"total_tokens":821}}""")
        val models = ModelsHttp({ ModelsConfig(Endpoint(base, "gemini-flash-lite-latest")) }, { "secret-key" })
        val completion = models.complete(ModelRole.Judge, listOf(ChatMessage("user", "hi")), 400)
        assertEquals("""{"action":"home"}""", completion.text)
        assertEquals(812, completion.usage.promptTokens)
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals("Bearer secret-key", lastAuth)
        val sent = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("gemini-flash-lite-latest", sent["model"]!!.jsonPrimitive.content)
        assertEquals(400, sent["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun errorsNeverCarryTheKey() {
        serve(401, """{"error":"bad key secret-key"}""")
        val models = ModelsHttp({ ModelsConfig(Endpoint(base, "m")) }, { "secret-key" })
        val e = assertFailsWith<PortException.Host> { models.complete(ModelRole.Planner, emptyList(), 10) }
        assertFalse(e.message!!.contains("secret-key"), e.message)
        assertTrue(e.message!!.contains("401"))
    }

    @Test
    fun noConfigIsAHostError() {
        assertFailsWith<PortException.Host> { ModelsHttp({ null }, { null }).complete(ModelRole.Planner, emptyList(), 10) }
    }

    @Test
    fun rolesFallBackAsCoreConfigDescribes() {
        val p = Endpoint("https://generativelanguage.googleapis.com/v1beta/openai/", "planner")
        val r = Endpoint("http://127.0.0.1:11434/v1", "qwen3:4b")
        val cfg = ModelsConfig(planner = p, reader = r)
        assertEquals(r, cfg.endpoint(ModelRole.Judge))
        assertEquals(p, cfg.endpoint(ModelRole.Verifier))
        assertTrue(p.leavesDevice())
        assertFalse(r.leavesDevice())
    }
}
