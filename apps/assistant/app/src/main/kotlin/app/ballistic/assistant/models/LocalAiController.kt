package app.ballistic.assistant.models

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.CancellationSignal
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import app.ballistic.assistant.core.CoreJson
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** UI jobs belong to the Application, survive rotation, and never load weights on the UI thread. */
class LocalAiController(
    private val app: Application,
    private val scope: CoroutineScope,
    val engine: LocalModels,
    private val selectionStore: ModelSelectionStore,
    private val mayChange: () -> Boolean,
) {
    val catalogue: List<DownloadableModel> = parseModelCatalogue(app.assets.open("model_catalogue.json").bufferedReader().use { it.readText() })
    val starter = catalogue.first()
    private val library = ModelLibrary(app.noBackupFilesDir.resolve("local-model"), catalogue).also { runCatching { it.prepare() } }
    @Volatile private var activeDownloader: ModelDownloader? = null
    private val mutableDownload = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress = mutableDownload.asStateFlow()
    private val mutableDownloading = MutableStateFlow<String?>(null)
    /** Catalogue id of the transfer in progress, if any. */
    val downloading = mutableDownloading.asStateFlow()
    private val mutableSelection = MutableStateFlow(initialSelection())
    val selection = mutableSelection.asStateFlow()
    private val mutableInstalled = MutableStateFlow(scanInstalled())
    /** Verified catalogue copies by id, plus [DownloadableModel.IMPORTED_MODEL_ID] for an own import. */
    val installed = mutableInstalled.asStateFlow()
    private val mutableModel = MutableStateFlow(activeInstalled())
    /** The active model's managed copy, if installed. */
    val model = mutableModel.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableResults = MutableStateFlow<List<SpeedResult>>(emptyList())
    /** Speed checks finished in this process, newest first, for side-by-side comparison. */
    val speedResults = mutableResults.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var importSignal: CancellationSignal? = null
    @Volatile private var importStream: java.io.InputStream? = null
    val phone = "${Build.MANUFACTURER} ${Build.MODEL}"
    val tensorInfo: String get() = TensorSupport.describe(app)
    fun memory(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }
    val profile = selectPhoneProfile(
        CoreJson.decodeFromString(ListSerializer(PhoneProfile.serializer()), app.assets.open("phone_profiles.json").bufferedReader().use { it.readText() }),
        Build.MANUFACTURER, Build.MODEL, memory().totalMem,
    )
    val availableStorage: Long get() = app.noBackupFilesDir.usableSpace
    val usedStorage: Long get() = library.usedBytes()

    val activeId: String get() = selection.value.activeModel ?: DownloadableModel.IMPORTED_MODEL_ID
    fun entry(id: String?) = library.entry(id)

    /** Older builds had one slot and no active-model field: keep whatever they were using. */
    private fun initialSelection(): ModelSelection {
        val stored = runCatching { selectionStore.initialize() }.getOrDefault(ModelSelection())
        if (stored.activeModel != null) return stored
        val active = when {
            library.imported.current() != null -> DownloadableModel.IMPORTED_MODEL_ID
            else -> catalogue.firstOrNull { library.installed(it) != null }?.id ?: starter.id
        }
        return stored.copy(activeModel = active).also { runCatching { selectionStore.save(it) } }
    }
    private fun scanInstalled(): Map<String, ManagedModel> = buildMap {
        catalogue.forEach { entry -> library.installed(entry)?.let { put(entry.id, it) } }
        library.imported.current()?.let { put(DownloadableModel.IMPORTED_MODEL_ID, it) }
    }
    private fun activeInstalled(): ManagedModel? = library.resolve(selection.value.activeModel)?.first
    private fun refresh() {
        mutableInstalled.value = scanInstalled()
        mutableModel.value = activeInstalled()
    }
    private fun save(next: ModelSelection) {
        selectionStore.save(next)
        mutableSelection.value = next
        mutableModel.value = activeInstalled()
    }

    fun choose(backend: ModelBackend) {
        if (!mayChange() || busy.value || engine.state.value.busy) return
        try { save(selection.value.copy(backend = backend)) }
        catch (_: Exception) { mutableNotice.value = "Could not save backend selection" }
    }
    /** Explicit owner choice for an entry offering several backends. Unloads; never automatic. */
    fun chooseComputeBackend(backend: ComputeBackend) = job {
        engine.unload()
        save(selection.value.copy(backendChoice = backend))
        mutableNotice.value = "${backend.name} selected. Use or load the model to start it on this backend."
    }
    fun setContext(tokens: Int) = job {
        val next = selection.value.copy(contextTokens = tokens).validated()
        engine.unload()
        save(next)
        mutableNotice.value = "Context changed. Load the model to use it. Larger contexts need more memory."
    }
    fun applyProfile() = job {
        engine.unload()
        save(selection.value.copy(contextTokens = profile.contextTokens, threads = profile.threads))
        mutableNotice.value = "Suggested settings applied. Load the model to use them."
    }
    private fun job(block: suspend () -> Unit) {
        if (!mayChange() || busy.value || engine.state.value.busy) return
        mutableBusy.value = true
        mutableNotice.value = null
        cancelled.set(false)
        mutableDownload.value = null
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) {
                mutableNotice.value = if (cancelled.get()) "Stopped" else when(e) {
                    is LocalFailure -> e.message
                    is app.ballistic.assistant.core.PortException -> e.reason
                    else -> "Local operation failed. Check the file, free storage and available memory."
                }
            } finally {
                importSignal = null; importStream = null; activeDownloader = null
                mutableDownloading.value = null
                refresh()
                mutableBusy.value = false
            }
        }
    }

    /** Copies a document into the imported-GGUF slot (any GGUF) or a catalogue slot (digest-checked). */
    fun import(uri: Uri, targetId: String = DownloadableModel.IMPORTED_MODEL_ID) = job {
        val entry = library.entry(targetId)
        if (engine.state.value.target?.modelId == targetId || targetId == activeId) engine.unload()
        var name = entry?.name ?: "Imported GGUF"
        var size: Long? = null
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if(n >= 0 && entry == null) name = c.getString(n) ?: name
                if(s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
            }
        }
        if (entry != null && size != null && size != entry.bytes) throw LocalFailure("This file is not ${entry.publisherFileName} (${entry.bytes} bytes expected).")
        val signal = CancellationSignal().also { importSignal = it }
        if (cancelled.get()) signal.cancel()
        val descriptor = app.contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: throw LocalFailure("Cannot open selected document")
        descriptor.use {
            val stream = it.createInputStream().also { stream -> importStream = stream }
            if (entry == null) library.imported.import(name, stream, size, cancelled::get)
            else library.store(entry).import(entry.name, stream, entry.bytes, cancelled::get,
                expectedSha256 = entry.sha256, catalogueId = entry.id)
        }
        mutableNotice.value = if (entry == null) "Imported a managed copy. Use it to validate the model and read its metadata."
            else "Verified ${entry.name}: size and SHA-256 match the catalogue. Choose Use to load it."
    }

    /** Owner-confirmed transfer of one catalogue entry, then switch to it and load it. */
    fun download(id: String) = job {
        val entry = library.entry(id) ?: throw LocalFailure("Unknown catalogue model")
        if (engine.state.value.target?.modelId == id) engine.unload()
        mutableDownloading.value = id
        val downloader = ModelDownloader(HttpsModelDownloadSource()).also { activeDownloader = it }
        downloader.install(entry, library.store(entry), cancelled::get, { mutableDownload.value = it })
        refresh()
        // The owner chose on-device setup. A load failure must not leave a remote backend active.
        save(selection.value.copy(backend = ModelBackend.Local, activeModel = id))
        mutableNotice.value = "Download verified. Loading ${entry.name}…"
        if (cancelled.get()) throw LocalFailure("Download installed; loading stopped")
        loadActive()
        mutableNotice.value = "Ready on device: ${entry.name} on ${engine.state.value.target?.backend?.name}. Phone-task quality has not been validated."
    }
    fun downloadStarter() = download(starter.id)

    /** Switch: unload whatever is loaded, select this model on device, load it. No fallback. */
    fun use(id: String) = job {
        engine.unload()
        if (library.resolve(id) == null) throw LocalFailure("Download or import this model first")
        save(selection.value.copy(backend = ModelBackend.Local, activeModel = id))
        loadActive()
        mutableNotice.value = "Using ${engine.state.value.target?.modelName} on ${engine.state.value.target?.backend?.name}."
    }
    fun load() = job { loadActive() }
    private fun loadActive() {
        val id = activeId
        val entry = library.entry(id)
        val (info, file) = library.resolve(id)
            ?: throw LocalFailure(if (entry?.gated == true) "Import the verified file for this model first" else "Download or import this model first")
        val mem = memory()
        // This is a conservative admission check, not an exact peak-RAM estimate.
        if (mem.lowMemory || info.bytes > mem.availMem - 512L*1024*1024) throw LocalFailure("Not enough available system memory; close other apps or choose a smaller model")
        if (cancelled.get()) throw LocalFailure("Loading stopped")
        val target = LoadTarget(id, entry?.name ?: info.name, entry?.runtime ?: ModelRuntime.LlamaCpp, entry.backendFor(selection.value))
        engine.load(file, selection.value, target, cancelled::get)
    }
    fun unload() = job { engine.unload() }
    fun delete(id: String) = job {
        if (engine.state.value.target?.modelId == id) engine.unload()
        val entry = library.entry(id)
        if (entry == null) library.imported.remove() else library.store(entry).remove()
        mutableNotice.value = "Deleted the managed copy."
    }
    fun remove() = delete(activeId)

    fun speedCheck(check: SpeedCheck = SpeedCheck.Short) = job {
        val target = engine.state.value.target
        engine.complete(check.messages(), check.maxTokens, cancelled::get)
        val metrics = engine.state.value.metrics
        if (target != null && metrics != null) {
            val result = SpeedResult(target.modelId, target.modelName, target.runtime, target.backend,
                engine.state.value.metadata?.backendReport, check, metrics)
            mutableResults.value = (listOf(result) + mutableResults.value).take(20)
            android.util.Log.i("Ballistic", String.format(Locale.ROOT,
                "speed check model=%s runtime=%s backend=%s check=%s prompt_tokens=%d generated_tokens=%d ttft_ms=%.0f prefill_tps=%.1f decode_tps=%.1f duration_ms=%.0f",
                target.modelId, target.runtime.name, target.backend.name, check.name, metrics.promptTokens, metrics.generatedTokens,
                metrics.firstTokenMs ?: -1.0, metrics.promptTokensPerSecond ?: -1.0, metrics.generationTokensPerSecond ?: -1.0, metrics.durationMs))
        }
        mutableNotice.value = "${check.label} check finished. This measures model execution only, not agent quality or a phone task."
    }
    fun stop() {
        cancelled.set(true)
        engine.cancel()
        val downloadToStop = activeDownloader
        scope.launch(Dispatchers.IO) { downloadToStop?.cancel() }
        importSignal?.cancel()
        // A provider may block in read; closing is best-effort and kept off the UI thread.
        scope.launch(Dispatchers.IO) { runCatching { importStream?.close() } }
    }
}
