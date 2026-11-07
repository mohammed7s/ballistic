package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage
import app.ballistic.assistant.core.CoreJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Byte arrays preserve ordinary UTF-8 (JNI NewStringUTF uses modified UTF-8). */
internal class LlamaNative {
    external fun resetCancellation()
    external fun cancel()
    external fun load(path: ByteArray, context: Int, threads: Int): ByteArray
    external fun unload()
    external fun generate(messages: ByteArray, cap: Int): ByteArray
}
class LlamaDriver : LocalDriver {
    private val library = runCatching { System.loadLibrary("ballistic_llama") }
    private val native = LlamaNative()
    private fun requireLibrary() {
        if (library.isFailure) throw LocalFailure("Embedded llama.cpp library is unavailable in this build")
    }
    private fun response(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val error = CoreJson.parseToJsonElement(text).jsonObject["error"]
        if (error != null) throw LocalFailure(error.jsonPrimitive.content)
        return text
    }
    override fun resetCancellation() { requireLibrary(); native.resetCancellation() }
    override fun cancel() { if(library.isSuccess) native.cancel() }
    override fun unload() { if(library.isSuccess) native.unload() }
    override fun load(path: String, context: Int, threads: Int): LocalMetadata {
        requireLibrary()
        return CoreJson.decodeFromString(LocalMetadata.serializer(), response(native.load(path.toByteArray(Charsets.UTF_8), context, threads)))
    }
    override fun generate(messages: List<ChatMessage>, maxTokens: Int): LocalReply {
        requireLibrary()
        val json = CoreJson.encodeToString(ListSerializer(ChatMessage.serializer()), messages)
        return CoreJson.decodeFromString(LocalReply.serializer(), response(native.generate(json.toByteArray(Charsets.UTF_8), maxTokens)))
    }
}
