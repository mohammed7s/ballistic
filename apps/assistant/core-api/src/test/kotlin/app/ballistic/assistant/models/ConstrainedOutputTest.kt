// Optional constrained output: the schema reaches only an engine that supports it, only for the
// roles the host names, and never for a speed check.

package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.CoreJson
import app.ballistic.assistant.core.ModelRole
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstrainedOutputTest {
    private val messages = listOf(ChatMessage("user", "Open Clock"))

    private class Driver(override val supportsJsonSchema: Boolean) : LocalDriver {
        val schemas = mutableListOf<String?>()
        override fun resetCancellation() {}
        override fun cancel() {}
        override fun unload() {}
        override fun load(path: String, context: Int, threads: Int) = LocalMetadata("fixture", null, 4096, context)
        override fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply = generate(messages, maxTokens, null)
        override fun generate(messages: List<ChatMessage>, maxTokens: Int, jsonSchema: String?): LocalReply {
            schemas += jsonSchema
            return LocalReply("{\"action\":\"home\"}", LocalMetrics(5, 3, 1.0, 1.0, 1.0, 2.0))
        }
    }

    private fun loaded(driver: Driver, schema: (ModelRole) -> String?): Pair<LocalModels, File> {
        val file = Files.createTempFile("model", ".gguf").toFile()
        val models = LocalModels(mapOf(ModelRuntime.LlamaCpp to driver), outputSchema = schema)
        models.load(file, ModelSelection(), LoadTarget("m", "m"))
        return models to file
    }

    @Test
    fun plannerGetsSchemaOtherRolesAndSpeedChecksDoNot() {
        val driver = Driver(supportsJsonSchema = true)
        val (models, file) = loaded(driver) { if (it == ModelRole.Planner) "{\"type\":\"object\"}" else null }
        try {
            models.complete(ModelRole.Planner, messages, 64)
            models.complete(ModelRole.Chat, messages, 64)
            models.complete(ModelRole.Reader, messages, 64)
            models.complete(messages, 16) { false }
            assertEquals(listOf("{\"type\":\"object\"}", null, null, null), driver.schemas)
            assertTrue(models.constrains(ModelRole.Planner))
            assertFalse(models.constrains(ModelRole.Chat))
        } finally { file.delete() }
    }

    @Test
    fun engineWithoutSupportRunsUnconstrained() {
        val driver = Driver(supportsJsonSchema = false)
        val (models, file) = loaded(driver) { "{\"type\":\"object\"}" }
        try {
            models.complete(ModelRole.Planner, messages, 64)
            assertEquals(listOf<String?>(null), driver.schemas)
            assertFalse(models.constrains(ModelRole.Planner))
        } finally { file.delete() }
    }

    @Test
    fun defaultIsOffAndOldSelectionFilesStillLoad() {
        assertFalse(ModelSelection().constrainPlanner)
        val old = CoreJson.decodeFromString(ModelSelection.serializer(), "{\"backend\":\"Local\",\"contextTokens\":4096,\"threads\":2}")
        assertFalse(old.constrainPlanner)
        assertNull(old.activeModel)
    }

    /** The schema the app ships is core's own file (single copy); it must stay a oneOf of action objects. */
    @Test
    fun coreActionSchemaIsTheShippedShape() {
        val repo = File(System.getProperty("ballistic.repo") ?: "../..")
        val schema = repo.resolve("core/schema/actions.json")
        if (!schema.exists()) return
        val root = CoreJson.parseToJsonElement(schema.readText())
        assertTrue(root.toString().contains("\"oneOf\""))
    }
}
