package app.ballistic.assistant.models

import app.ballistic.assistant.core.*
import java.io.File
import kotlin.test.*

/** The context limit the Models port advertises to core's guard, and LiteRT-LM prompt accounting. */
class ContextLimitTest {
    private val repo = File(System.getProperty("ballistic.repo"))
    private val messages = listOf(ChatMessage("system", "S"), ChatMessage("user", "U"))

    /** Reports the context it was started with, like llama.cpp and LiteRT-LM CPU/GPU; 0 like the NPU path. */
    private class Driver(private val reported: Int? = null) : LocalDriver {
        override fun resetCancellation() {}
        override fun cancel() {}
        override fun unload() {}
        override fun load(path: String, context: Int, threads: Int) = LocalMetadata("fixture", null, 0, reported ?: context)
        override fun load(spec: LoadSpec) = load(spec.path, spec.context, spec.threads)
        override fun generate(messages: List<ChatMessage>, maxTokens: Int) =
            LocalReply("ok", LocalMetrics(2441, 36, 820.0, 1700.0, 820.0, 2500.0))
    }

    @Test fun shippedTensorBundlesCarryTheirCompiledContextAndOthersNone() {
        val catalogue = parseModelCatalogue(repo.resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText())
        assertEquals(4096, catalogue.single { it.id == "gemma4-e2b-tensor-g5" }.contextTokens)
        assertEquals(1280, catalogue.single { it.id == "gemma3-1b-tensor-g5" }.contextTokens)
        catalogue.filter { it.accelerator != Accelerator.TensorG5Npu }.forEach { assertNull(it.contextTokens, it.id) }
    }

    @Test fun aCompiledBundleAdvertisesItsContextAndOthersTheConfiguredOne() {
        val npu = LocalModels(mapOf(ModelRuntime.LiteRtLm to Driver(reported = 0)))
        assertNull(npu.limits(ModelRole.Planner).contextTokens, "nothing loaded: no limit")
        npu.load(File("g5"), ModelSelection(contextTokens = 2048),
            LoadTarget("gemma4-e2b-tensor-g5", "Gemma 4 E2B · Tensor G5", ModelRuntime.LiteRtLm, ComputeBackend.NPU, contextTokens = 4096))
        for (role in ModelRole.entries) assertEquals(ModelLimits(4096), npu.limits(role))
        assertEquals(4096, npu.state.value.metadata?.context)

        val gguf = LocalModels(Driver())
        gguf.load(File("m.gguf"), ModelSelection(contextTokens = 3072))
        assertEquals(ModelLimits(3072), gguf.limits(ModelRole.Reader))

        // A driver that reports no context and no catalogue value: nothing is advertised.
        val unknown = LocalModels(mapOf(ModelRuntime.LiteRtLm to Driver(reported = 0)))
        unknown.load(File("x"), ModelSelection(), LoadTarget("imported", "x", ModelRuntime.LiteRtLm, ComputeBackend.NPU))
        assertNull(unknown.limits(ModelRole.Planner).contextTokens)
        unknown.unload()
        assertNull(unknown.limits(ModelRole.Planner).contextTokens)
    }

    @Test fun routingAdvertisesTheFrozenBackendsLimitAndHttpReportsNone() {
        val local = LocalModels(Driver()).apply { load(File("m.gguf"), ModelSelection(contextTokens = 4096)) }
        val http = Models { _, _, _ -> Completion("remote") }
        var selected = ModelBackend.Local
        val router = RoutedModels({ selected }, http, local)
        assertFailsWith<PortException.Host> { router.limits(ModelRole.Planner) }
        router.beginRun()
        assertEquals(ModelLimits(4096), router.limits(ModelRole.Planner))
        selected = ModelBackend.Http
        assertEquals(ModelLimits(4096), router.limits(ModelRole.Planner), "frozen for the run")
        router.endRun(); router.beginRun()
        assertEquals(ModelLimits(), router.limits(ModelRole.Planner))
        assertNull(router.countTokens(ModelRole.Planner, messages))
        router.endRun()
        assertEquals(Usage(2441, 36), local.complete(messages, 800) { false }.usage)
    }

    @Test fun modelLimitsUseCoresJsonForm() {
        assertEquals("""{"context_tokens":4096}""", CoreJson.encodeToString(ModelLimits.serializer(), ModelLimits(4096)))
        assertEquals("""{"context_tokens":null}""", CoreJson.encodeToString(ModelLimits.serializer(), ModelLimits()))
    }

    @Test fun liteRtPromptTokensPreferAWholePrefillElseTheKvCount() {
        // Tensor G5 NPU, 8 Nov 2026: last prefill 2,441, KV 2,477 after 36 decoded tokens.
        assertEquals(2441, LiteRtPromptTokens.count(2441, 2477, 36))
        assertEquals(2449, LiteRtPromptTokens.count(2449, 2495, 46))
        assertEquals(2525, LiteRtPromptTokens.count(2525, 2574, 49))
        assertTrue(LiteRtPromptTokens.prefillCoversPrompt(2441, 2477, 36))
        // Emulator CPU: the last prefill was the final chunk only; the KV count gives the prompt.
        assertEquals(2008, LiteRtPromptTokens.count(2, 2010, 2))
        assertFalse(LiteRtPromptTokens.prefillCoversPrompt(2, 2010, 2))
        // No usable KV count (missing, or below the decoded tokens): not measured.
        assertEquals(0, LiteRtPromptTokens.count(2441, null, 36))
        assertEquals(0, LiteRtPromptTokens.count(5, 3, 10))
        assertFalse(LiteRtPromptTokens.prefillCoversPrompt(0, 2477, 36))
        assertEquals(2441, LiteRtPromptTokens.count(0, 2477, 36))
    }
}
