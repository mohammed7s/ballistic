package app.ballistic.assistant.models

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.builtins.ListSerializer
import app.ballistic.assistant.core.CoreJson
import org.junit.Assume.assumeTrue

class ModelDownloadTest {
    private val bytes = byteArrayOf(71,71,85,70,3,0,0,0) + ByteArray(512) { it.toByte() }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun spec(data: ByteArray = bytes) = DownloadableModel("fixture", "Fixture", "https://huggingface.co/owner/repo/resolve/${"a".repeat(40)}/model.gguf",data.size.toLong(),hash(data),"Apache-2.0","https://huggingface.co/owner/repo")
    private class Source(private val data: ByteArray) : ModelDownloadSource {
        var opened = 0
        var closed = false
        var stopped = false
        override fun open(url: String, expectedBytes: Long, cancelled: () -> Boolean): InputStream {
            opened++
            return object : ByteArrayInputStream(data) { override fun close() { closed=true; super.close() } }
        }
        override fun cancel() { stopped=true }
    }
    private inline fun fixture(block: (ManagedModelStore) -> Unit) {
        val directory = Files.createTempDirectory("download-test").toFile()
        try { block(ManagedModelStore(directory)) } finally { directory.deleteRecursively() }
    }
    private fun installOld(store: ManagedModelStore) = store.import("Existing",ByteArrayInputStream(bytes),bytes.size.toLong(),{false},{Long.MAX_VALUE})

    @Test fun verifiedBytesInstallWithIdentityAndProgress() = fixture { store ->
        val source = Source(bytes)
        val progress = mutableListOf<DownloadProgress>()
        val model = ModelDownloader(source).install(spec(),store,{false},{progress+=it},{Long.MAX_VALUE})
        assertEquals("fixture", model.catalogueId)
        assertEquals(hash(bytes),store.current()!!.sha256)
        assertContentEquals(bytes,store.modelFile.readBytes())
        assertTrue(source.closed)
        assertEquals(0,progress.first().received)
        assertEquals(bytes.size.toLong(),progress.last().received)
        assertFalse(store.directory.resolve("import.partial").exists())
    }
    @Test fun hashMismatchLeavesExistingModelAndMetadataUnchanged() = fixture { store ->
        val old = installOld(store)
        val wrong = bytes.copyOf().apply { this[100] = 99 }
        val source = Source(wrong)
        assertFailsWith<LocalFailure> { ModelDownloader(source).install(spec(),store,{false},{},{Long.MAX_VALUE}) }
        assertEquals(old,store.current())
        assertContentEquals(bytes,store.modelFile.readBytes())
        assertTrue(source.closed)
        assertFalse(store.directory.resolve("import.partial").exists())
    }
    @Test fun truncatedAndOversizedResponsesNeverReplaceModel() = fixture { store ->
        val old = installOld(store)
        for(data in listOf(bytes.copyOf(40),bytes+byteArrayOf(1))) {
            assertFails { ModelDownloader(Source(data)).install(spec(),store,{false},{},{Long.MAX_VALUE}) }
            assertEquals(old,store.current())
            assertContentEquals(bytes,store.modelFile.readBytes())
        }
    }
    @Test fun cancellationAfterTransferBeforeCommitPreservesOldModel() = fixture { store ->
        val old = installOld(store)
        var stopped = false
        val source = Source(bytes)
        val downloader = ModelDownloader(source)
        assertFails { downloader.install(spec(),store,{stopped},{ if(it.received == it.total) stopped=true },{Long.MAX_VALUE}) }
        assertEquals(old,store.current())
        assertTrue(source.closed)
        downloader.cancel(); assertTrue(source.stopped)
    }
    @Test fun storageAndPreCancellationFailBeforeNetwork() = fixture { store ->
        val source = Source(bytes)
        val downloader = ModelDownloader(source)
        assertFails { downloader.install(spec(),store,{false},{},{1}) }
        assertFails { downloader.install(spec(),store,{true},{},{Long.MAX_VALUE}) }
        assertEquals(0,source.opened)
    }
    @Test fun interruptedPartialIsDiscardedAndCanRestart() = fixture { store ->
        store.directory.resolve("import.partial").writeText("partial from killed process")
        store.directory.resolve("info.partial").writeText("partial metadata")
        ModelDownloader(Source(bytes)).install(spec(),store,{false},{},{Long.MAX_VALUE})
        assertContentEquals(bytes,store.modelFile.readBytes())
        assertFalse(store.directory.resolve("info.partial").exists())
    }
    @Test fun onlyPinnedCatalogueAndSecureDownloadHostsAreAccepted() {
        spec().validated()
        assertFails { spec().copy(url="https://huggingface.co/o/r/resolve/main/file.gguf").validated() }
        assertFails { spec().copy(sha256="bad").validated() }
        for(url in listOf("http://huggingface.co/x","https://huggingface.co.evil.test/x","https://user:pass@huggingface.co/x","https://huggingface.co:444/x")) {
            assertFailsWith<LocalFailure> { HttpsModelDownloadSource.requireDownloadUrl(URL(url)) }
        }
        HttpsModelDownloadSource.requireDownloadUrl(URL("https://cas-bridge.xethub.hf.co/x"))
    }
    @Test fun shippedCatalogueIsValidAndBackwardsCompatibleImportsRemainReadable() = fixture { store ->
        val root = java.io.File(System.getProperty("ballistic.repo"))
        val catalogue = CoreJson.decodeFromString(ListSerializer(DownloadableModel.serializer()),root.resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText())
        assertEquals(1,catalogue.size)
        catalogue.forEach { it.validated() }
        store.modelFile.writeBytes(bytes)
        store.directory.resolve("model.json").writeText("""{"name":"Old import","bytes":520}""")
        assertEquals("Old import",store.current()!!.name)
        assertNull(store.current()!!.catalogueId)
    }
    @org.junit.Test(timeout=600_000) fun optionalLiveCatalogueDownload() {
        val path = System.getenv("BALLISTIC_TEST_DOWNLOAD_DIR")
        assumeTrue(path != null)
        val root = java.io.File(System.getProperty("ballistic.repo"))
        val spec = CoreJson.decodeFromString(ListSerializer(DownloadableModel.serializer()),root.resolve("apps/assistant/app/src/main/assets/model_catalogue.json").readText()).single()
        val store = ManagedModelStore(java.io.File(path!!))
        val result = ModelDownloader(HttpsModelDownloadSource()).install(spec,store,{false},{})
        assertEquals(spec.sha256,result.sha256)
        assertEquals(spec.bytes,store.modelFile.length())
        if (System.getProperty("ballistic.llama.test") == "true") {
            val local = LocalModels(LlamaDriver())
            try {
                local.load(store.modelFile,ModelSelection(contextTokens=4096,threads=2))
                val reply = local.complete(app.ballistic.assistant.core.ModelRole.Planner,
                    listOf(app.ballistic.assistant.core.ChatMessage("user","Say hello briefly.")),32)
                assertTrue(reply.text.isNotBlank())
                assertTrue(reply.usage.completionTokens > 0)
                println("Catalogue model generated ${reply.usage.completionTokens} tokens through host JNI; not phone/task validation")
            } finally { local.unload() }
        }
        println("Verified live catalogue download: ${result.bytes} bytes; sha256=${result.sha256}")
    }
}
