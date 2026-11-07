package app.ballistic.assistant.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PolicyStoreTest {
    @Test
    fun savesAndLoads() {
        val dir = Files.createTempDirectory("ballistic-policy").toFile().apply { deleteOnExit() }
        val store = PolicyStore(dir.resolve("policy.json"))
        assertEquals(PolicyFile(), store.load())
        val edited = store.load().with("org.example", AppPolicy(act = Act.Navigate, confirm = ConfirmMode.Always))
        store.save(edited)
        assertEquals(edited, store.load())
        dir.resolve("policy.json").writeText("{truncated")
        assertEquals(PolicyFile(), store.load())
    }
}
