// Recorded screens from core/tests/screens parse into ScreenState and encode back to the same
// JSON. The files are read in place (path from the build's `ballistic.repo` property).

package app.ballistic.assistant.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FixtureTest {
    private val screens = File(System.getProperty("ballistic.repo") ?: "../../..", "core/tests/screens")

    private fun fixtures(): List<File> =
        screens.walkTopDown().filter { it.isFile && it.extension == "json" }.sorted().toList()

    /** Drops keys from [encoded] that [original] does not have (fields Rust filled by default). */
    private fun restrictTo(original: JsonElement, encoded: JsonElement): JsonElement = when {
        original is JsonObject && encoded is JsonObject ->
            JsonObject(encoded.filterKeys { it in original }.mapValues { (k, v) -> restrictTo(original[k]!!, v) })
        original is JsonArray && encoded is JsonArray ->
            JsonArray(encoded.mapIndexed { i, v -> restrictTo(original[i], v) })
        else -> encoded
    }

    @Test
    fun everyRecordedScreenRoundTrips() {
        val files = fixtures()
        assertTrue(files.size >= 11, "expected the recorded screens under $screens, found ${files.size}")
        for (file in files) {
            val original = Json.parseToJsonElement(file.readText()).jsonObject["screen"]!!
            val screen = CoreJson.decodeFromJsonElement(ScreenState.serializer(), original)
            val encoded = CoreJson.encodeToJsonElement(ScreenState.serializer(), screen)
            assertEquals(original, restrictTo(original, encoded), file.path)
        }
    }

    @Test
    fun smsBeforeSendHasCapabilitiesAndSendButton() {
        val file = File(screens, "benchmark_regressions/sms_before_send.json")
        val screen = CoreJson.decodeFromJsonElement(ScreenState.serializer(), Json.parseToJsonElement(file.readText()).jsonObject["screen"]!!)
        assertEquals("com.simplemobiletools.smsmessenger", screen.`package`)
        assertEquals(7, screen.nodes.size)
        assertTrue(screen.capabilities!!.operations.contains("tap"))
        assertTrue(screen.nodes.any { it.role == Role.Button })
        assertEquals(null, screen.nodes.first().parent)
    }

    @Test
    fun contactsScreenCarriesInstalledApps() {
        val file = File(screens, "ContactsAddContact/01.json")
        val screen = CoreJson.decodeFromJsonElement(ScreenState.serializer(), Json.parseToJsonElement(file.readText()).jsonObject["screen"]!!)
        assertEquals(24, screen.nodes.size)
        assertEquals(33, screen.installedApps.size)
        assertEquals(null, screen.capabilities)
    }
}
