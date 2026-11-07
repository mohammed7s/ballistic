// The HTTP client against a local server speaking the OpenAI chat-completions shape.

package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.ModelRole
import app.ballistic.assistant.core.PortException
import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.CancelToken
import app.ballistic.assistant.core.ConfirmResult
import app.ballistic.assistant.core.CoreRunException
import app.ballistic.assistant.core.Flags
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostCapabilities
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.Node
import app.ballistic.assistant.core.Role
import app.ballistic.assistant.core.RunListener
import app.ballistic.assistant.core.ScreenState
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.UniffiCoreEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeNotNull

class ModelsHttpTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastBody = ""
    private var lastAuth: String? = null
    private var lastPath = ""
    private var replyOverride: String? = null
    private var requestCount = 0

    private fun serve(status: Int, reply: String) {
        server.createContext("/") { exchange ->
            requestCount++
            lastPath = exchange.requestURI.path
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = (replyOverride ?: reply).toByteArray()
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
        serve(401, """{"error":"bad key secret-key PRIVATE_PROVIDER_PAYLOAD"}""")
        val models = ModelsHttp({ ModelsConfig(Endpoint(base, "m")) }, { "secret-key" })
        val e = assertFailsWith<PortException.Host> { models.complete(ModelRole.Planner, emptyList(), 10) }
        assertFalse(e.message!!.contains("secret-key"), e.message)
        assertTrue(e.message!!.contains("401"))
        assertFalse(e.message!!.contains("PRIVATE"))
    }

    @Test
    fun serializedRequestsForEveryRoleDefaultAndOptIn() {
        serve(200, """{"choices":[{"message":{"content":"e7"}}]}""")
        for (enabled in listOf(false, true)) {
            val cfg = checkNotNull(ModelsConfig.decodeOrNull(
                """{"planner":{"base_url":"$base","model":"same-model","disable_native_tools":$enabled}}"""))
            assertEquals(cfg, ModelsConfig.decodeOrNull(cfg.encode()))
            val models = ModelsHttp({ cfg }, { "secret-key" })
            for (role in ModelRole.entries) {
                assertEquals("e7", models.complete(role, listOf(ChatMessage("user", "fixture")), 137).text)
                val expected = """{"model":"same-model","messages":[{"role":"user","content":"fixture"}],"max_tokens":137${if (enabled) ",\"tool_choice\":\"none\"" else ""}}"""
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(lastBody))
                assertEquals("Bearer secret-key", lastAuth)
                assertEquals("/v1/chat/completions", lastPath)
            }
        }
    }

    @Test
    fun optionIsBooleanAndFollowsEndpointFallback() {
        val cfg = checkNotNull(ModelsConfig.decodeOrNull(
            """{"planner":{"base_url":"$base","model":"p","disable_native_tools":true},"reader":{"base_url":"$base","model":"r"}}"""))
        serve(200, """{"choices":[{"message":{"content":"e7"}}]}""")
        val models = ModelsHttp({ cfg }, { null })
        for (role in ModelRole.entries) {
            models.complete(role, emptyList(), 137)
            assertEquals(role in listOf(ModelRole.Planner, ModelRole.Verifier),
                "tool_choice" in Json.parseToJsonElement(lastBody).jsonObject)
        }
        for (value in listOf("1", "null", "\"none\"")) {
            assertEquals(null, ModelsConfig.decodeOrNull(
                """{"planner":{"base_url":"$base","model":"p","disable_native_tools":$value}}"""))
        }
    }

    @Test
    fun nativeAndMixedResponsesAreTerminalAndPayloadSafe() {
        serve(200, "unused")
        val models = ModelsHttp({ ModelsConfig(Endpoint(base, "m")) }, { "secret-key" })
        val signals = listOf(
            "\"tool_calls\":[{\"function\":{\"name\":\"PRIVATE_TOOL\",\"arguments\":\"PRIVATE_ARGS\"}}]" to "tool_calls",
            "\"function_call\":{\"name\":\"PRIVATE_TOOL\"}" to "stop",
            "\"refusal\":\"PRIVATE_REFUSAL\"" to "stop",
            "\"tool_calls\":[\"PRIVATE_TOOL\"]" to "PRIVATE_REASON",
            "\"unknown\":\"PRIVATE_UNKNOWN\"" to "content_filter",
            "\"unknown\":null" to "tool_calls",
            "\"unknown\":null" to "function_call",
        )
        for (role in ModelRole.entries) {
            for ((signal, finish) in signals) {
                for (content in listOf("", "\"content\":\"e7\",")) {
                    replyOverride = """{"choices":[{"message":{$content$signal},"finish_reason":"$finish"}],"unknown":"PRIVATE_ENVELOPE"}"""
                    val before = requestCount
                    val error = assertFailsWith<PortException.Malformed> { models.complete(role, emptyList(), 137) }
                    assertFalse(error.message!!.contains("PRIVATE"), error.message)
                    assertFalse(error.message!!.contains("secret-key"), error.message)
                    assertEquals(before + 1, requestCount)
                }
            }
        }
    }

    @Test
    fun emptyNativeFieldsAndUnknownMetadataDoNotInvalidateText() {
        serve(200, """{"choices":[{"message":{"content":"e7","tool_calls":[],"function_call":null,"refusal":null,"unknown":"PRIVATE"},"finish_reason":"PRIVATE_REASON"}]}""")
        val models = ModelsHttp({ ModelsConfig(Endpoint(base, "m")) }, { null })
        assertEquals("e7", models.complete(ModelRole.Reader, emptyList(), 137).text)
    }

    @Test
    fun optInRejectionDoesNotFallbackOrLeakPayload() {
        serve(400, """{"error":"PRIVATE tool_choice rejected"}""")
        val cfg = checkNotNull(ModelsConfig.decodeOrNull(
            """{"planner":{"base_url":"$base","model":"m","disable_native_tools":true}}"""))
        val error = assertFailsWith<PortException.Host> {
            ModelsHttp({ cfg }, { null }).complete(ModelRole.Reader, emptyList(), 137)
        }
        assertEquals(1, requestCount)
        assertFalse(error.message!!.contains("PRIVATE"))
    }

    @Test
    fun nativeResponseNeverDispatchesOrRedispatchesHandsThroughRealCore() {
        val engine = UniffiCoreEngine.load().getOrNull()
        assumeNotNull(engine)
        val core = checkNotNull(engine)
        serve(200, "unused")
        val screen = ScreenState("org.fixture.dispatch", "Compose", 320, 640, listOf(
            Node(11, role = Role.Button, label = "Send", bounds = Bounds(20, 420, 90, 460),
                flags = Flags(enabled = true, visible = true, clickable = true))),
            capabilities = HostCapabilities(listOf("tap"), listOf("org.fixture.dispatch")))
        val http = ModelsHttp({ ModelsConfig(Endpoint(base, "m")) }, { "secret-key" })
        for (afterAction in listOf(false, true)) {
            for (mixed in listOf(false, true)) {
                var acted = 0
                var confirmed = 0
                var calls = 0
                val ports = HostPorts(
                    screen = { screen },
                    hands = { acted++; HandsResult.Ok },
                    confirm = { confirmed++; ConfirmResult.Approved },
                    models = { role, messages, maxTokens ->
                        calls++
                        val text = when {
                            afterAction && calls == 1 -> """{"action":"tap","target":"e1"}"""
                            afterAction && calls == 2 -> """{"action":"done","evidence":"acknowledged"}"""
                            afterAction -> """{"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}"""
                            else -> """{"action":"tap","target":"e1"}"""
                        }
                        val native = !afterAction || calls == 3
                        val content = if (!native || mixed) "\"content\":${JsonPrimitive(text)}," else ""
                        val signal = if (native) "\"tool_calls\":[{\"function\":{\"name\":\"PRIVATE_TOOL\",\"arguments\":\"PRIVATE_ARGS\"}}]" else "\"role\":\"assistant\""
                        replyOverride = """{"choices":[{"message":{$content$signal},"finish_reason":"${if (native) "tool_calls" else "stop"}"}]}"""
                        http.complete(role, messages, maxTokens)
                    },
                )
                val before = requestCount
                val error = assertFailsWith<CoreRunException> {
                    core.run("Send the prepared message", 2, ports, object : RunListener {
                        override fun onStep(event: StepEvent) {}
                    }, CancelToken())
                }
                assertFalse(error.message!!.contains("PRIVATE"))
                assertEquals(if (afterAction) 1 else 0, acted)
                assertEquals(acted, confirmed)
                assertEquals(if (afterAction) 3 else 1, calls)
                assertEquals(calls, requestCount - before)
            }
        }
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
