// Kotlin values against the JSON core produces. Every literal below was printed by serde from
// core/src/types.rs (a throwaway program serializing each variant, 7 Nov 2026); the bridge
// literal is from core/src/bridge.rs. If core's shapes change, regenerate these.

package app.ballistic.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class JsonCompatibilityTest {
    private fun <T> same(serializer: KSerializer<T>, value: T, rust: String) {
        assertEquals(Json.parseToJsonElement(rust), CoreJson.encodeToJsonElement(serializer, value), "encode $value")
        assertEquals(value, CoreJson.decodeFromString(serializer, rust), "decode $rust")
    }

    @Test
    fun actions() {
        val s = Action.serializer()
        same(s, Action.OpenApp("Clock"), """{"action":"open_app","app":"Clock"}""")
        same(s, Action.Tap(7), """{"action":"tap","target":7}""")
        same(s, Action.LongPress(3), """{"action":"long_press","target":3}""")
        same(s, Action.Type(4, "I'm late", true), """{"action":"type","target":4,"text":"I'm late","submit":true}""")
        same(s, Action.Scroll(null, Direction.Down), """{"action":"scroll","target":null,"direction":"down"}""")
        same(s, Action.Scroll(2, Direction.Up), """{"action":"scroll","target":2,"direction":"up"}""")
        same(s, Action.Swipe(5, Direction.Left), """{"action":"swipe","target":5,"direction":"left"}""")
        same(s, Action.Back, """{"action":"back"}""")
        same(s, Action.Home, """{"action":"home"}""")
        same(s, Action.Wait, """{"action":"wait"}""")
        same(s, Action.Read(listOf(1, 2), "who wrote?", ReadShape.Facts), """{"action":"read","targets":[1,2],"question":"who wrote?","shape":"facts"}""")
        same(s, Action.Answer("nothing new"), """{"action":"answer","text":"nothing new"}""")
        same(s, Action.Done("alarm listed"), """{"action":"done","evidence":"alarm listed"}""")
        same(s, Action.GiveUp("no such app"), """{"action":"give_up","reason":"no such app"}""")
    }

    @Test
    fun typeSubmitDefaultsToFalseWhenAbsent() {
        // Rust: #[serde(default)] submit.
        assertEquals(Action.Type(1, "x", false), CoreJson.decodeFromString(Action.serializer(), """{"action":"type","target":1,"text":"x"}"""))
    }

    @Test
    fun handsOps() {
        val s = HandsOp.serializer()
        val b = Bounds(0, 128, 1080, 2400)
        same(s, HandsOp.OpenApp("com.android.deskclock"), """{"op":"open_app","package":"com.android.deskclock","activity":null}""")
        same(s, HandsOp.OpenApp("com.a", ".Main"), """{"op":"open_app","package":"com.a","activity":".Main"}""")
        same(s, HandsOp.Tap(540, 1200), """{"op":"tap","x":540,"y":1200}""")
        same(s, HandsOp.LongPress(1, 2), """{"op":"long_press","x":1,"y":2}""")
        same(s, HandsOp.Type(10, 20, "hi", false), """{"op":"type","x":10,"y":20,"text":"hi","submit":false}""")
        same(s, HandsOp.Scroll(b, Direction.Down), """{"op":"scroll","area":{"x1":0,"y1":128,"x2":1080,"y2":2400},"direction":"down"}""")
        same(s, HandsOp.Swipe(b, Direction.Right), """{"op":"swipe","area":{"x1":0,"y1":128,"x2":1080,"y2":2400},"direction":"right"}""")
        same(s, HandsOp.Back, """{"op":"back"}""")
        same(s, HandsOp.Home, """{"op":"home"}""")
        same(s, HandsOp.Wait(500), """{"op":"wait","millis":500}""")
    }

    @Test
    fun handsOpRejectsUnknownFieldsAsCoreDoes() {
        // Rust: #[serde(deny_unknown_fields)] on HandsOp.
        assertFailsWith<SerializationException> {
            CoreJson.decodeFromString(HandsOp.serializer(), """{"op":"tap","x":1,"y":2,"force":true}""")
        }
        assertFailsWith<SerializationException> {
            CoreJson.decodeFromString(HandsOp.serializer(), """{"op":"reply","text":"hi"}""")
        }
    }

    @Test
    fun handsResults() {
        val s = HandsResult.serializer()
        same(s, HandsResult.Ok, """{"result":"ok"}""")
        same(s, HandsResult.Error("needs Computer Control"), """{"result":"error","message":"needs Computer Control"}""")
        same(s, HandsResult.NotExecuted("x"), """{"result":"not_executed","message":"x"}""")
    }

    @Test
    fun sources() {
        val s = Source.serializer()
        same(s, Source.OwnerRequest, """{"kind":"owner_request"}""")
        same(s, Source.App("org.thoughtcrime.securesms"), """{"kind":"app","detail":"org.thoughtcrime.securesms"}""")
        assertFailsWith<SerializationException> { CoreJson.decodeFromString(s, """{"kind":"app"}""") }
        assertFailsWith<SerializationException> { CoreJson.decodeFromString(s, """{"kind":"model"}""") }
    }

    @Test
    fun confirmRequest() {
        same(
            ConfirmRequest.serializer(),
            ConfirmRequest(
                operation = HandsOp.Tap(980, 2250),
                app = "com.simplemobiletools.smsmessenger",
                verb = "tap e9",
                target = "Send",
                values = listOf(Tagged("I'm late", Source.OwnerRequest), Tagged("+15550100", Source.Sms("thread 4"))),
                hard = true,
                reason = "activate a control labelled \"send\"",
            ),
            """{"operation":{"op":"tap","x":980,"y":2250},"app":"com.simplemobiletools.smsmessenger","verb":"tap e9","target":"Send","values":[{"value":"I'm late","source":{"kind":"owner_request"}},{"value":"+15550100","source":{"kind":"sms","detail":"thread 4"}}],"hard":true,"reason":"activate a control labelled \"send\""}""",
        )
        same(ConfirmResult.serializer(), ConfirmResult.Approved, "\"approved\"")
    }

    @Test
    fun modelsAndCapabilities() {
        same(ModelRole.serializer(), ModelRole.Verifier, "\"verifier\"")
        same(Completion.serializer(), Completion("{}", Usage(812, 40)), """{"text":"{}","usage":{"prompt_tokens":812,"completion_tokens":40}}""")
        same(ChatMessage.serializer(), ChatMessage("user", "hi"), """{"role":"user","content":"hi"}""")
        same(HostCapabilities.serializer(), HostCapabilities(listOf("open_app", "wait"), listOf("com.android.deskclock")), """{"operations":["open_app","wait"],"app_grants":["com.android.deskclock"]}""")
        same(Verification.serializer(), Verification(), """{"status":"unknown","evidence_basis":"none","nodes":[]}""")
        same(ExecutionStatus.serializer(), ExecutionStatus.NotExecuted, "\"not_executed\"")
    }

    @Test
    fun screenState() {
        same(
            ScreenState.serializer(),
            ScreenState(
                `package` = "com.android.systemui", activity = "notifications", width = 1080, height = 2400,
                nodes = listOf(
                    Node(
                        id = 1, role = Role.Text, label = "Signal", text = "Ali: running late?",
                        `package` = "org.thoughtcrime.securesms", bounds = Bounds(), flags = Flags(enabled = true, visible = true),
                    ),
                ),
                installedApps = listOf(AppEntry("Clock", "com.android.deskclock")),
            ),
            """{"package":"com.android.systemui","activity":"notifications","width":1080,"height":2400,"nodes":[{"id":1,"parent":null,"role":"text","class_name":null,"label":"Signal","text":"Ali: running late?","resource_id":null,"package":"org.thoughtcrime.securesms","bounds":{"x1":0,"y1":0,"x2":0,"y2":0},"flags":{"clickable":false,"long_clickable":false,"editable":false,"scrollable":false,"checkable":false,"checked":false,"selected":false,"enabled":true,"focused":false,"visible":true}}],"installed_apps":[{"label":"Clock","package":"com.android.deskclock","activity":null}],"capabilities":null}""",
        )
    }

    @Test
    fun bridgeObserveReplyParses() {
        // core/src/bridge.rs test `observe_round_trip`: optional fields absent.
        val reply = Json.parseToJsonElement("""{"id":1,"ok":true,"data":{"package":"com.a","activity":".Main","width":1080,"height":2400,"nodes":[]}}""")
        val screen = CoreJson.decodeFromJsonElement(ScreenState.serializer(), reply.jsonObject["data"]!!)
        assertEquals("com.a", screen.`package`)
        assertEquals(0, screen.nodes.size)
        assertEquals(null, screen.capabilities)
    }

    @Test
    fun operationIdsMatchCoreCapabilities() {
        // core/src/capabilities.rs operation_id.
        val ids = listOf(
            HandsOp.OpenApp("p"), HandsOp.Tap(0, 0), HandsOp.LongPress(0, 0), HandsOp.Type(0, 0, "", false),
            HandsOp.Scroll(Bounds(), Direction.Down), HandsOp.Swipe(Bounds(), Direction.Down), HandsOp.Back, HandsOp.Home, HandsOp.Wait(1),
        ).map { it.operationId }
        assertEquals(listOf("open_app", "tap", "long_press", "type", "scroll", "swipe", "back", "home", "wait"), ids)
    }
}
