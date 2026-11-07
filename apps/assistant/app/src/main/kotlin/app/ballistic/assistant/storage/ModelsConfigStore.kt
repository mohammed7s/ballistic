// models.json in app-private storage: endpoints and model names per role, never the key.

package app.ballistic.assistant.storage

import app.ballistic.assistant.models.ModelsConfig
import java.io.File

class ModelsConfigStore(private val file: File) {
    @Synchronized
    fun load(): ModelsConfig? = if (file.exists()) ModelsConfig.decodeOrNull(file.readText()) else null

    @Synchronized
    fun save(config: ModelsConfig) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(config.encode())
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }
}
