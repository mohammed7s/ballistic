// The policy file on disk. Reads fall back to the defaults (PolicyFile.decodeOrDefault); writes
// go to a temporary file renamed over the old one, so a crash mid-write leaves the old file.
// The app allows writes only while the command gate is unlocked.

package app.ballistic.assistant.core

import java.io.File

class PolicyStore(private val file: File) {
    @Synchronized
    fun load(): PolicyFile = PolicyFile.decodeOrDefault(if (file.exists()) file.readText() else null)

    @Synchronized
    fun save(policy: PolicyFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(policy.encode())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not replace ${file.name}")
        }
    }
}
