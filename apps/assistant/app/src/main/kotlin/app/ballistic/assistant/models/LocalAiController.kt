package app.ballistic.assistant.models

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.CancellationSignal
import android.net.Uri
import android.provider.OpenableColumns
import app.ballistic.assistant.core.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import app.ballistic.assistant.core.CoreJson
import java.util.concurrent.atomic.AtomicBoolean

/** UI jobs belong to the Application, survive rotation, and never load weights on the UI thread. */
class LocalAiController(
    private val app: Application,
    private val scope: CoroutineScope,
    val engine: LocalModels,
    private val selectionStore: ModelSelectionStore,
    private val mayChange: () -> Boolean,
) {
    private val store = ManagedModelStore(app.noBackupFilesDir.resolve("local-model"))
    private val mutableSelection = MutableStateFlow(runCatching { selectionStore.initialize() }.getOrDefault(ModelSelection()))
    val selection = mutableSelection.asStateFlow()
    private val mutableModel = MutableStateFlow(store.current())
    val model = mutableModel.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var importSignal: CancellationSignal? = null
    @Volatile private var importStream: java.io.InputStream? = null
    val phone = "${Build.MANUFACTURER} ${Build.MODEL}"
    fun memory(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }
    val profile = selectPhoneProfile(
        CoreJson.decodeFromString(ListSerializer(PhoneProfile.serializer()), app.assets.open("phone_profiles.json").bufferedReader().use { it.readText() }),
        Build.MANUFACTURER, Build.MODEL, memory().totalMem,
    )
    val availableStorage: Long get() = app.noBackupFilesDir.usableSpace

    fun choose(backend: ModelBackend) {
        if (!mayChange() || busy.value || engine.state.value.busy) return
        try {
            val next = selection.value.copy(backend = backend)
            selectionStore.save(next)
            mutableSelection.value = next
        } catch (_: Exception) { mutableNotice.value = "Could not save backend selection" }
    }
    fun setContext(tokens: Int) = job {
        val next = selection.value.copy(contextTokens = tokens).validated()
        engine.unload()
        selectionStore.save(next)
        mutableSelection.value = next
        mutableNotice.value = "Context changed. Load the model to use it. Larger contexts need more memory."
    }
    fun applyProfile() = job {
        engine.unload()
        val next = selection.value.copy(contextTokens = profile.contextTokens, threads = profile.threads)
        selectionStore.save(next)
        mutableSelection.value = next
        mutableNotice.value = "Suggested settings applied. Load the model to use them."
    }
    private fun job(block: suspend () -> Unit) {
        if (!mayChange() || busy.value || engine.state.value.busy) return
        mutableBusy.value = true
        mutableNotice.value = null
        cancelled.set(false)
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) {
                mutableNotice.value = if (cancelled.get()) "Stopped" else when(e) {
                    is LocalFailure -> e.message
                    is app.ballistic.assistant.core.PortException -> e.reason
                    else -> "Local operation failed. Check the file, free storage and available memory."
                }
            } finally {
                importSignal = null; importStream = null
                mutableBusy.value = false
            }
        }
    }
    fun import(uri: Uri) = job {
        engine.unload()
        var name = "Imported GGUF"
        var size: Long? = null
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if(n >= 0) name = c.getString(n) ?: name
                if(s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
            }
        }
        val signal = CancellationSignal().also { importSignal = it }
        if (cancelled.get()) signal.cancel()
        val descriptor = app.contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: throw LocalFailure("Cannot open selected document")
        descriptor.use {
            val stream = it.createInputStream().also { stream -> importStream = stream }
            mutableModel.value = store.import(name, stream, size, cancelled::get)
        }
        mutableNotice.value = "Imported a managed copy. Load to validate the model and read its metadata."
    }
    fun load() = job {
        val info = store.current() ?: throw LocalFailure("Import a GGUF model first")
        val mem = memory()
        // This is a conservative admission check, not an exact peak-RAM estimate.
        if (mem.lowMemory || info.bytes > mem.availMem - 512L*1024*1024) throw LocalFailure("Not enough available system memory; close other apps or import smaller weights")
        if (!cancelled.get()) engine.load(store.modelFile, selection.value, cancelled::get)
    }
    fun unload() = job { engine.unload() }
    fun remove() = job { engine.unload(); store.remove(); mutableModel.value = store.current() }
    fun speedCheck() = job {
        engine.complete(listOf(ChatMessage("user", "Count from one to ten in words. Keep the answer brief.")),32,cancelled::get)
        mutableNotice.value = "Speed check finished. This measures a short prompt, not agent quality or a full phone task."
    }
    fun stop() {
        cancelled.set(true)
        engine.cancel()
        importSignal?.cancel()
        // A provider may block in read; closing is best-effort and kept off the UI thread.
        scope.launch(Dispatchers.IO) { runCatching { importStream?.close() } }
    }
}
