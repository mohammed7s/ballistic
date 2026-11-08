package app.ballistic.assistant.models

import app.ballistic.assistant.core.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ModelCatalogueTest {
    private val repo = File(System.getProperty("ballistic.repo"))
    private fun shipped() = parseModelCatalogue(repo.resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText())
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val gguf = byteArrayOf(71,71,85,70,3,0,0,0) + ByteArray(300) { it.toByte() }
    private val litert = "LITERTLM".toByteArray() + ByteArray(300) { (it * 7).toByte() }
    private fun entry(id: String, data: ByteArray, runtime: ModelRuntime, accelerator: Accelerator, gated: Boolean = false): DownloadableModel {
        val file = if (runtime == ModelRuntime.LiteRtLm) "m.litertlm" else "m.gguf"
        return DownloadableModel(id, id, "https://huggingface.co/o/r/resolve/${"b".repeat(40)}/$file", data.size.toLong(), hash(data),
            "Apache-2.0", "https://huggingface.co/o/r", runtime, accelerator, file, gated)
    }
    private class Source(private val data: ByteArray) : ModelDownloadSource {
        var opened = 0
        override fun open(url: String, expectedBytes: Long, cancelled: () -> Boolean): InputStream { opened++; return ByteArrayInputStream(data) }
        override fun cancel() {}
    }
    private inline fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("catalogue-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun shippedCatalogueIsPinnedAndDescribesEveryRuntimeAndAccelerator() {
        val catalogue = shipped()
        assertEquals("qwen3-06b-q8-preview", catalogue.first().id)
        assertEquals(639446688, catalogue.first().bytes)
        for (e in catalogue) {
            assertTrue(e.url.contains("/resolve/") && Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(e.url), e.id)
            assertEquals(e.publisherFileName, e.fileName, e.id)
            assertTrue(e.license.isNotBlank() && e.note.isNotBlank(), e.id)
        }
        val g5 = catalogue.single { it.id == "gemma4-e2b-tensor-g5" }
        assertEquals(ModelRuntime.LiteRtLm, g5.runtime)
        assertEquals(Accelerator.TensorG5Npu, g5.accelerator)
        assertEquals(listOf(ComputeBackend.NPU), g5.backends)
        assertEquals(3206299658, g5.bytes)
        assertFalse(g5.gated)
        val generic = catalogue.single { it.id == "gemma4-e2b-litertlm" }
        assertEquals(listOf(ComputeBackend.GPU, ComputeBackend.CPU), generic.backends)
        assertTrue(catalogue.single { it.id == "gemma3-1b-tensor-g5" }.gated)
        catalogue.filter { it.runtime == ModelRuntime.LlamaCpp }.forEach { assertEquals(listOf(ComputeBackend.CPU), it.backends) }
    }

    @Test fun invalidEntriesAreRejected() {
        val ok = entry("ok", gguf, ModelRuntime.LlamaCpp, Accelerator.Cpu)
        ok.validated()
        assertFails { ok.copy(accelerator = Accelerator.Gpu).validated() }            // llama.cpp is CPU-only here
        assertFails { ok.copy(runtime = ModelRuntime.LiteRtLm).validated() }           // .gguf on LiteRT-LM
        assertFails { ok.copy(fileName = "other.gguf").validated() }
        assertFails { ok.copy(url = ok.url.replace("b".repeat(40), "main")).validated() }
        assertFails { ok.copy(url = ok.url + "?download=true").validated() }
        assertFails { ok.copy(id = DownloadableModel.IMPORTED_MODEL_ID).validated() }
        val text = repo.resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText()
        assertFails { parseModelCatalogue("[]") }
        assertFails { parseModelCatalogue(text.replaceFirst("\"note\"", "\"unknownField\": 1, \"note\"")) }
        val duplicate = "[" + text.trim().removePrefix("[").removeSuffix("]").substringBefore("},") + "}," + text.trim().removePrefix("[").removeSuffix("]") + "]"
        assertFails { parseModelCatalogue(duplicate) }
    }

    @Test fun gatedEntryNeverStartsANetworkTransfer() = temp { dir ->
        val gated = entry("gated", litert, ModelRuntime.LiteRtLm, Accelerator.TensorG5Npu, gated = true)
        val source = Source(litert)
        val library = ModelLibrary(dir, listOf(gated))
        assertFailsWith<LocalFailure> { ModelDownloader(source).install(gated, library.store(gated), { false }, {}, { Long.MAX_VALUE }) }
        assertEquals(0, source.opened)
        // The manual path: an owner-copied file is accepted only with the catalogue size and digest.
        val store = library.store(gated)
        assertFails { store.import(gated.name, ByteArrayInputStream(litert.copyOf().apply { this[20] = 1 }), gated.bytes, { false }, { Long.MAX_VALUE }, gated.sha256, gated.id) }
        assertNull(library.installed(gated))
        store.import(gated.name, ByteArrayInputStream(litert), gated.bytes, { false }, { Long.MAX_VALUE }, gated.sha256, gated.id)
        assertNotNull(library.installed(gated))
    }

    @Test fun liteRtBundlesAreDigestCheckedAndFormatsAreNotInterchangeable() = temp { dir ->
        val bundle = entry("bundle", litert, ModelRuntime.LiteRtLm, Accelerator.Gpu)
        val library = ModelLibrary(dir, listOf(bundle))
        val installed = ModelDownloader(Source(litert)).install(bundle, library.store(bundle), { false }, {}, { Long.MAX_VALUE })
        assertEquals(bundle.sha256, installed.sha256)
        assertTrue(library.store(bundle).modelFile.name.endsWith(".litertlm"))
        assertEquals(litert.size.toLong(), library.usedBytes())
        // A GGUF in a LiteRT-LM slot, or a bundle in the GGUF slot, is refused before commit.
        assertFails { ManagedModelStore(dir.resolve("x"), ModelFormat.LiteRtLm).import("g", ByteArrayInputStream(gguf), null, { false }, { Long.MAX_VALUE }) }
        assertFails { ManagedModelStore(dir.resolve("y")).import("l", ByteArrayInputStream(litert), null, { false }, { Long.MAX_VALUE }) }
        assertFails { ModelDownloader(Source(litert)).install(bundle, ManagedModelStore(dir.resolve("z")), { false }, {}, { Long.MAX_VALUE }) }
        // A wrong digest leaves the slot without a verified copy.
        val other = bundle.copy(sha256 = hash(gguf))
        assertFails { ModelDownloader(Source(litert)).install(other, ModelLibrary(dir.resolve("w"), listOf(other)).store(other), { false }, {}, { Long.MAX_VALUE }) }
        assertNull(ModelLibrary(dir.resolve("w"), listOf(other)).installed(other))
    }

    @Test fun legacySingleSlotStarterMovesWithoutRedownloadAndOwnImportsStay() = temp { dir ->
        val starter = entry("starter", gguf, ModelRuntime.LlamaCpp, Accelerator.Cpu)
        ManagedModelStore(dir).import(starter.name, ByteArrayInputStream(gguf), starter.bytes, { false }, { Long.MAX_VALUE }, starter.sha256, starter.id)
        val library = ModelLibrary(dir, listOf(starter))
        library.prepare()
        assertNotNull(library.installed(starter))
        assertNull(library.imported.current())
        assertEquals(library.store(starter).modelFile, library.resolve("starter")!!.second)
        // A file the owner imported (no catalogue identity) stays in its slot.
        val own = temp2 { d2 ->
            ManagedModelStore(d2).import("mine.gguf", ByteArrayInputStream(gguf), null, { false }, { Long.MAX_VALUE })
            ModelLibrary(d2, listOf(starter)).apply { prepare() }.let { it.imported.current()?.name to it.installed(starter) }
        }
        assertEquals("mine.gguf" to null, own)
    }
    private inline fun <T> temp2(block: (File) -> T): T {
        val dir = Files.createTempDirectory("catalogue-test2").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    private class Driver(val name: String) : LocalDriver {
        var loaded: LoadSpec? = null
        var unloads = 0
        var generated = 0
        var failLoad = false
        override fun resetCancellation() {}
        override fun cancel() {}
        override fun load(path: String, context: Int, threads: Int) = error("spec load expected")
        override fun load(spec: LoadSpec): LocalMetadata {
            if (failLoad) throw LocalFailure("Tensor accelerator not available on this phone/OS")
            loaded = spec
            return LocalMetadata(name, null, 0, spec.context, "started on ${spec.backend}")
        }
        override fun unload() { unloads++; loaded = null }
        override fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply {
            generated++
            return LocalReply(name, LocalMetrics(2000, 4, 100.0, 50.0, 120.0, 170.0, reportedPromptTokensPerSecond = 20000.0, reportedGenerationTokensPerSecond = 80.0))
        }
    }

    @Test fun switchingUnloadsTheOtherEngineAndAFailedSwitchLeavesNothingLoaded() {
        val llama = Driver("llama"); val litert = Driver("litert")
        val local = LocalModels(mapOf(ModelRuntime.LlamaCpp to llama, ModelRuntime.LiteRtLm to litert))
        val http = AtomicInteger()
        val router = RoutedModels({ ModelBackend.Local }, Models { _, _, _ -> http.incrementAndGet(); Completion("remote") }, local)
        val msgs = listOf(ChatMessage("user", "hi"))

        local.load(File("a.gguf"), ModelSelection(), LoadTarget("qwen", "Qwen", ModelRuntime.LlamaCpp, ComputeBackend.CPU))
        router.beginRun(); assertEquals("llama", router.complete(ModelRole.Planner, msgs, 8).text); router.endRun()

        local.load(File("b.litertlm"), ModelSelection(), LoadTarget("g5", "Gemma G5", ModelRuntime.LiteRtLm, ComputeBackend.NPU))
        assertNull(llama.loaded)
        assertEquals(ComputeBackend.NPU, litert.loaded!!.backend)
        assertEquals(ComputeBackend.NPU, local.state.value.target!!.backend)
        assertEquals("started on NPU", local.state.value.metadata!!.backendReport)
        router.beginRun(); assertEquals("litert", router.complete(ModelRole.Reader, msgs, 8).text); router.endRun()
        assertEquals(20000.0, local.state.value.metrics!!.promptTokensPerSecond)
        assertEquals(80.0, local.state.value.metrics!!.generationTokensPerSecond)

        // Switch back to Qwen, then a failing NPU start: nothing stays loaded, the previous model is
        // not reloaded, no other backend is tried and nothing reaches the connected model.
        local.load(File("a.gguf"), ModelSelection(), LoadTarget("qwen", "Qwen"))
        litert.failLoad = true
        assertFailsWith<PortException.Host> { local.load(File("b.litertlm"), ModelSelection(), LoadTarget("g5", "Gemma G5", ModelRuntime.LiteRtLm, ComputeBackend.NPU)) }
        assertNull(llama.loaded); assertNull(litert.loaded)
        assertEquals(LocalPhase.Error, local.state.value.phase)
        assertEquals("g5", local.state.value.target!!.modelId)
        assertTrue(local.state.value.error!!.startsWith("Tensor accelerator not available"))
        router.beginRun()
        assertFailsWith<PortException.Host> { router.complete(ModelRole.Planner, msgs, 8) }
        router.endRun()
        assertEquals(0, http.get())
        assertEquals(1, llama.generated)
    }

    @Test fun cpuOnlyRuntimeRefusesOtherBackendsAndChoiceIsOnlyAmongOfferedOnes() {
        val legacy = object : LocalDriver {
            override fun resetCancellation() {}
            override fun cancel() {}
            override fun load(path: String, context: Int, threads: Int) = LocalMetadata("x", null, 0, context)
            override fun unload() {}
            override fun generate(messages: List<ChatMessage>, maxTokens: Int) = error("unused")
        }
        assertFailsWith<LocalFailure> { legacy.load(LoadSpec("p", 4096, 2, ComputeBackend.NPU)) }
        val catalogue = shipped()
        val g5 = catalogue.single { it.id == "gemma4-e2b-tensor-g5" }
        val generic = catalogue.single { it.id == "gemma4-e2b-litertlm" }
        val cpuChoice = ModelSelection(backendChoice = ComputeBackend.CPU)
        assertEquals(ComputeBackend.NPU, g5.backendFor(cpuChoice))           // G5 bundle: NPU or nothing
        assertEquals(ComputeBackend.CPU, generic.backendFor(cpuChoice))      // explicit owner choice
        assertEquals(ComputeBackend.GPU, generic.backendFor(ModelSelection()))
        assertEquals(ComputeBackend.CPU, catalogue.first().backendFor(ModelSelection(backendChoice = ComputeBackend.NPU)))
    }

    @Test fun selectionFromEarlierBuildsStillDecodes() = temp { dir ->
        val file = dir.resolve("selection.json")
        file.writeText("""{"backend":"Local","contextTokens":4096,"threads":2}""")
        val loaded = ModelSelectionStore(file) { false }.load()
        assertEquals(ModelBackend.Local, loaded.backend)
        assertNull(loaded.activeModel)
        assertNull(loaded.backendChoice)
        val llamaJson = """{"description":"d","quantization":"Q8","trainingContext":40960,"context":4096}"""
        assertNull(CoreJson.decodeFromString(LocalMetadata.serializer(), llamaJson).backendReport)
    }

    @Test fun longPrefillCheckIsAgentLengthAndEndsWithAUserMessage() {
        val messages = SpeedCheck.LongPrefill.messages()
        assertEquals("user", messages.last().role)
        val chars = messages.sumOf { it.content.length }
        // 90 lines measured 2,530 Qwen3 tokens; 72 lines are about 6,000 characters (~2K tokens).
        assertTrue(chars in 5_000..8_000, "chars=$chars")
        assertEquals(SpeedCheck.LongPrefill.messages(), messages)
        assertEquals("user", SpeedCheck.Short.messages().single().role)
    }
}
