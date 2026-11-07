package app.ballistic.assistant.models

import app.ballistic.assistant.core.*
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class LocalModelsTest {
    private val messages = listOf(ChatMessage("system", "System"), ChatMessage("user", "Hello"))
    private class Driver : LocalDriver {
        var loads = 0
        var unloads = 0
        var cap = 0
        @Volatile var cancelled = false
        var failLoad = false
        var failGenerate = false
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun resetCancellation() { cancelled = false }
        override fun cancel() { cancelled = true; release?.countDown() }
        override fun unload() { unloads++ }
        override fun load(path: String, context: Int, threads: Int): LocalMetadata {
            loads++
            if(failLoad) throw LocalFailure("Invalid GGUF")
            return LocalMetadata("fixture", "Q4",4096,context)
        }
        override fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply {
            cap = maxTokens
            entered?.countDown()
            check(release?.await(3,TimeUnit.SECONDS) != false)
            if(cancelled) throw LocalFailure("Local operation stopped")
            if(failGenerate) throw IllegalStateException("SECRET PROMPT")
            return LocalReply("output", LocalMetrics(10,3,20.0,40.0,25.0,65.0))
        }
    }
    @Test fun migrationPreservesEndpointBytesAndDefaultsFreshInstallLocal() {
        val dir = Files.createTempDirectory("selection").toFile()
        try {
            val http = dir.resolve("models.json")
            val selection = ModelSelectionStore(dir.resolve("selection.json")) { http.exists() }
            assertEquals(ModelBackend.Local,selection.load().backend)
            val cfg = ModelsConfig(Endpoint("https://example.org", "planner",true), reader = Endpoint("https://reader.example", "reader"), timeoutSeconds = 123)
            http.writeText(cfg.encode())
            assertEquals(ModelBackend.Http,selection.load().backend)
            selection.save(ModelSelection(ModelBackend.Local,4096,4))
            assertEquals(cfg.encode(),http.readText())
            assertEquals(cfg,ModelsConfig.decodeOrNull(http.readText()))
            assertEquals(ModelBackend.Local,selection.load().backend)
            dir.resolve("selection.json").writeText("broken")
            assertEquals(ModelBackend.Local,selection.load().backend)
        } finally { dir.deleteRecursively() }
    }
    @Test fun initializedLocalChoiceDoesNotChangeWhenHttpSettingsAreAddedLater() {
        val dir = Files.createTempDirectory("stable-selection").toFile()
        try {
            var httpExists = false
            val selection = ModelSelectionStore(dir.resolve("selection.json")) { httpExists }
            assertEquals(ModelBackend.Local,selection.initialize().backend)
            assertEquals(4096,selection.load().contextTokens)
            selection.save(selection.load().copy(contextTokens = 2048))
            httpExists = true
            assertEquals(ModelBackend.Local,selection.load().backend)
            assertEquals(2048,selection.load().contextTokens)
            selection.save(selection.load().copy(backend = ModelBackend.Http))
            assertEquals(ModelBackend.Http,selection.load().backend)
        } finally { dir.deleteRecursively() }
    }
    @Test fun unknownMetricsStayUnknownAndAllRolesShareWeights() {
        val driver = Driver()
        val model = LocalModels(driver)
        assertNull(model.state.value.loadMs)
        assertNull(model.state.value.metrics)
        model.load(File("fixture"),ModelSelection())
        assertNotNull(model.state.value.loadMs)
        assertNull(model.state.value.metrics)
        for(role in ModelRole.entries) model.complete(role,messages,4096)
        assertEquals(1,driver.loads)
        assertEquals(1024,driver.cap)
        assertEquals(500.0,model.state.value.metrics!!.promptTokensPerSecond)
        assertEquals(50.0,model.state.value.metrics!!.generationTokensPerSecond)
        assertNull(LocalMetrics(0,1,0.0,2.0,null,2.0).generationTokensPerSecond)
        model.unload()
        assertEquals(LocalState(),model.state.value)
    }
    @Test fun stopInterruptsActiveOwnerAndCannotRaceUnload() {
        val driver = Driver().apply { entered=CountDownLatch(1); release=CountDownLatch(1) }
        val model = LocalModels(driver)
        model.load(File("fixture"),ModelSelection())
        var failure: Throwable? = null
        val worker = thread { try { model.complete(ModelRole.Planner,messages,32) } catch(t: Throwable) { failure=t } }
        assertTrue(driver.entered!!.await(3,TimeUnit.SECONDS))
        assertEquals(LocalPhase.Generating,model.state.value.phase)
        val unloadedBefore = driver.unloads
        assertFailsWith<PortException.Host> { model.unload() }
        assertEquals(unloadedBefore,driver.unloads)
        model.cancel()
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertIs<PortException.Host>(failure)
        assertEquals(LocalPhase.Ready,model.state.value.phase)
        assertNull(model.state.value.metrics)
        model.unload()
        assertEquals(LocalPhase.Unloaded,model.state.value.phase)
    }
    @Test fun failuresNeverFallBackAndErrorsDoNotContainPrompt() {
        val driver = Driver()
        val local = LocalModels(driver)
        val calls = AtomicInteger()
        val http = Models { _,_,_ -> calls.incrementAndGet(); Completion("remote") }
        var selected = ModelBackend.Local
        val router = RoutedModels({selected},http,local)
        router.beginRun()
        assertFailsWith<PortException.Host> { router.complete(ModelRole.Reader,messages,32) }
        assertEquals(0,calls.get())
        local.load(File("fixture"),ModelSelection())
        selected = ModelBackend.Http // frozen per run
        assertEquals("output",router.complete(ModelRole.Planner,messages,32).text)
        assertEquals(0,calls.get())
        driver.failGenerate = true
        val error = assertFailsWith<PortException.Host> { router.complete(ModelRole.Planner,messages,32) }
        assertFalse(error.message!!.contains("SECRET"))
        router.cancelRun()
        assertFailsWith<PortException.Host> { router.complete(ModelRole.Planner,messages,32) }
        router.endRun(); router.beginRun()
        assertEquals("remote",router.complete(ModelRole.Planner,messages,32).text)
        assertEquals(1,calls.get())
        router.endRun()
    }
    @Test fun failedLoadReleasesWeightsAndShowsError() {
        val driver = Driver().apply { failLoad=true }
        val model = LocalModels(driver)
        assertFailsWith<PortException.Host> { model.load(File("fixture"),ModelSelection()) }
        assertEquals(2,driver.unloads)
        assertEquals(LocalPhase.Error,model.state.value.phase)
        assertNull(model.state.value.metadata)
        assertNull(model.state.value.loadMs)
    }
    @Test fun stopBeforeCallbackCannotBeResetByNextGeneration() {
        val driver = Driver()
        val local = LocalModels(driver)
        local.load(File("fixture"),ModelSelection())
        val router = RoutedModels({ModelBackend.Local}, Models { _,_,_ -> error("network") },local)
        router.beginRun(); router.cancelRun()
        assertFailsWith<PortException.Host> { router.complete(ModelRole.Planner,messages,32) }
        assertTrue(driver.cancelled)
        assertEquals(0,driver.cap)
    }
    @Test fun profileFallbackUsesMemoryAndExactDeviceMapping() {
        val low = PhoneProfile("low",contextTokens=1024,threads=2,suggestion="trial")
        val high = PhoneProfile("high",minMemoryGiB=8,contextTokens=2048,threads=4,suggestion="trial")
        val pixel = PhoneProfile("pixel","Google","Pixel 10 Pro",contextTokens=4096,threads=4,suggestion="trial")
        val profiles = listOf(pixel,high,low)
        assertEquals(pixel,selectPhoneProfile(profiles,"google","Pixel 10 Pro",16L shl 30))
        assertEquals(high,selectPhoneProfile(profiles,"unknown","other",12L shl 30))
        assertEquals(low,selectPhoneProfile(profiles,"unknown","other",4L shl 30))
    }
    @Test fun importFailureOrCancellationKeepsOldModelAndClosesSource() {
        val dir = Files.createTempDirectory("model-import").toFile()
        try {
            val store = ManagedModelStore(dir)
            val bytes = byteArrayOf(71,71,85,70,3,0,0,0)+ByteArray(256)
            var closed = false
            val source = object: ByteArrayInputStream(bytes) { override fun close() { closed=true; super.close() } }
            store.import("model.gguf",source,bytes.size.toLong(),{false},{Long.MAX_VALUE})
            assertTrue(closed)
            assertEquals(bytes.size.toLong(),store.current()!!.bytes)
            assertFails { store.import("bad",ByteArrayInputStream(byteArrayOf(0)),null,{false},{Long.MAX_VALUE}) }
            assertContentEquals(bytes,store.modelFile.readBytes())
            assertFails { store.import("cancelled",ByteArrayInputStream(bytes),null,{true},{Long.MAX_VALUE}) }
            assertContentEquals(bytes,store.modelFile.readBytes())
            assertFalse(dir.resolve("import.partial").exists())
            assertFails { store.import("large",ByteArrayInputStream(bytes),bytes.size.toLong(),{false},{1L}) }
            assertContentEquals(bytes,store.modelFile.readBytes())
            store.remove(); assertNull(store.current())
        } finally { dir.deleteRecursively() }
    }
}
