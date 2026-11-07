package app.ballistic.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PolicyTest {
    private fun request(verb: String = "tap e4", target: String = "Save", hard: Boolean = false, values: List<Tagged> = emptyList(), reason: String = "toggle") =
        ConfirmRequest(HandsOp.Tap(1, 2), "com.android.deskclock", verb, target, values, hard, reason)

    @Test
    fun hardFromCoreIsNeverLowered() {
        for (mode in listOf(ConfirmMode.DefaultList, ConfirmMode.Always)) {
            assertEquals(ConfirmClass.Hard, ConfirmFloor.required(request(hard = true), AppPolicy(confirm = mode)))
        }
    }

    @Test
    fun codeConstantRaisesSoftToHard() {
        // Core asked for soft, but the operation names a word on the constant list.
        assertEquals(ConfirmClass.Hard, ConfirmFloor.required(request(target = "Send message"), AppPolicy()))
        assertEquals(ConfirmClass.Hard, ConfirmFloor.required(request(reason = "type and submit"), AppPolicy()))
        assertEquals(ConfirmClass.Soft, ConfirmFloor.required(request(), AppPolicy()))
    }

    @Test
    fun ownerSettingsTightenOnly() {
        assertEquals(ConfirmClass.Hard, ConfirmFloor.required(request(), AppPolicy(confirm = ConfirmMode.Always)))
        assertEquals(ConfirmClass.Refuse, ConfirmFloor.required(request(hard = true), AppPolicy(confirm = ConfirmMode.NeverAutomate)))
        assertEquals(ConfirmClass.Refuse, ConfirmFloor.required(request(), AppPolicy(act = Act.None)))
    }

    @Test
    fun taintedValueRaisesToHard() {
        val tainted = listOf(Tagged("+15550100", Source.Sms("thread 4")))
        assertEquals(ConfirmClass.Hard, ConfirmFloor.required(request(values = tainted), AppPolicy()))
    }

    @Test
    fun fileRoundTripsWithItsDocumentedShape() {
        val text = """{"version":1,"apps":{"org.example":{"observe":"notifications","act":"navigate","route":"device_only","confirm":"always"}}}"""
        val file = PolicyFile.decodeOrDefault(text)
        assertEquals(AppPolicy(Observe.Notifications, Act.Navigate, Route.DeviceOnly, ConfirmMode.Always), file.forApp("org.example"))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(text), kotlinx.serialization.json.Json.parseToJsonElement(file.encode()))
    }

    @Test
    fun unreadableOrLooseningFileFallsBackToDefaults() {
        // An invented key that would loosen the floor is rejected, and so is the whole file.
        val loosening = """{"version":1,"hard_words":[],"apps":{"org.example":{"confirm":"always"}}}"""
        assertEquals(PolicyFile(), PolicyFile.decodeOrDefault(loosening))
        assertEquals(PolicyFile(), PolicyFile.decodeOrDefault("""{"version":1,"apps":{"org.example":{"confirm":"never"}}}"""))
        assertEquals(PolicyFile(), PolicyFile.decodeOrDefault("""{"version":2,"apps":{}}"""))
        assertEquals(PolicyFile(), PolicyFile.decodeOrDefault("not json"))
        assertEquals(PolicyFile(), PolicyFile.decodeOrDefault(null))
    }

    @Test
    fun signalIsDeviceOnlyByDefault() {
        assertEquals(Route.DeviceOnly, PolicyFile().forApp("org.thoughtcrime.securesms").route)
        assertEquals(AppPolicy(), PolicyFile().forApp("com.android.deskclock"))
    }
}
