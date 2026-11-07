package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import java.nio.file.Files
import kotlin.test.*
import org.junit.Assume.assumeTrue

class LlamaJniTest {
    @Test fun actualJniReportsMissingCorruptAndCancelledModelWithoutSuccess() {
        assumeTrue(System.getProperty("ballistic.llama.test") == "true")
        val driver = LlamaDriver()
        driver.unload()
        driver.resetCancellation()
        val dir = Files.createTempDirectory("native-model").toFile()
        try {
            val missing = assertFailsWith<LocalFailure> { driver.load(dir.resolve("absent.gguf").path,1024,2) }
            assertFalse(missing.message!!.contains(dir.path))
            assertFailsWith<LocalFailure> { driver.generate(listOf(ChatMessage("user","private test prompt")),32) }
            val broken = dir.resolve("broken.gguf").apply { writeBytes(byteArrayOf(71,71,85,70,3,0,0,0)) }
            driver.resetCancellation()
            assertFailsWith<LocalFailure> { driver.load(broken.path,1024,2) }
            driver.cancel()
            val cancelled = assertFailsWith<LocalFailure> { driver.load(broken.path,1024,2) }
            assertEquals("Local operation stopped",cancelled.message)
            driver.unload(); driver.unload()
        } finally { dir.deleteRecursively() }
    }
    @org.junit.Test(timeout = 180_000)
    fun optionalRealModelGeneratesForEveryRoleAndStops() {
        assumeTrue(System.getProperty("ballistic.llama.test") == "true")
        val path = System.getProperty("ballistic.llama.model")
        assumeTrue(path != null)
        val file = java.io.File(path!!)
        val expectedHash = System.getProperty("ballistic.llama.model.sha256")
        assertNotNull(expectedHash, "Set BALLISTIC_LLAMA_TEST_MODEL_SHA256 before using weights")
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while(true) { val n = input.read(bytes); if(n < 0) break; hash.update(bytes,0,n) }
        }
        assertEquals(expectedHash, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        val local = LocalModels(LlamaDriver())
        try {
            local.load(file, ModelSelection(contextTokens = 2048,threads = 2))
            assertEquals(LocalPhase.Ready, local.state.value.phase)
            assertTrue(local.state.value.loadMs!! > 0)
            for(role in app.ballistic.assistant.core.ModelRole.entries) {
                val reply = local.complete(role,listOf(ChatMessage("system","Be brief."),ChatMessage("user","Say hello.")),8)
                assertTrue(reply.text.isNotBlank(), "No text from role $role")
                assertTrue(reply.usage.promptTokens > 0)
                assertTrue(reply.usage.completionTokens in 1..8)
                val metrics = local.state.value.metrics!!
                assertTrue(metrics.durationMs > 0)
                assertNotNull(metrics.firstTokenMs)
                println("JNI fixture role=$role prompt_tokens=${metrics.promptTokens} generated_tokens=${metrics.generatedTokens}; real host inference, not phone benchmark")
            }
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val worker = kotlin.concurrent.thread {
                try {
                    local.complete(app.ballistic.assistant.core.ModelRole.Planner,
                        listOf(ChatMessage("user","Write a long numbered list of 500 different animals.")),1024)
                } catch(t: Throwable) { failure.set(t) }
            }
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                while(local.state.value.phase != LocalPhase.Generating && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(LocalPhase.Generating,local.state.value.phase)
                Thread.sleep(150) // Allow actual prefill/decode to begin before Stop.
                assertTrue(worker.isAlive)
                local.cancel()
                worker.join(10_000)
                assertFalse(worker.isAlive,"Native cancellation did not return within 10 seconds")
                assertIs<app.ballistic.assistant.core.PortException.Host>(failure.get())
                assertEquals(LocalPhase.Ready,local.state.value.phase)
                assertNull(local.state.value.metrics)
            } finally { local.cancel(); worker.join(10_000) }
            // Decode after cancellation must begin with a fresh KV cache and reset stop flag.
            val next = local.complete(app.ballistic.assistant.core.ModelRole.Reader,listOf(ChatMessage("user","Hello")),4)
            assertTrue(next.text.isNotBlank())
        } finally { local.unload() }
        assertEquals(LocalState(),local.state.value)
    }

}
