// Ask's composer: Send must never be a silent no-op. Each state either sends, loads then sends,
// or is disabled with a reason in words.

package app.ballistic.assistant.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerTest {
    private val ready = ComposerInputs(
        phase = Phase.Idle, unlocked = true, localSelected = true, realEngine = true, modelInstalled = true,
        modelLoaded = true, engineBusy = false, localJobBusy = false, textBlank = false,
    )

    @Test
    fun loadedModelWithTextSends() {
        val v = composerView(ready)
        assertEquals(ComposerAction.Send, v.action)
        assertTrue(v.buttonEnabled && v.fieldEnabled)
        assertNull(v.reason)
    }

    @Test
    fun afterAFinishedTurnSendStillWorks() {
        assertEquals(ComposerAction.Send, composerView(ready.copy(phase = Phase.Finished)).action)
    }

    /** The owner's report: model installed, app restarted, so nothing is loaded. Send used to be disabled with no visible reason. */
    @Test
    fun installedButUnloadedModelLoadsThenSends() {
        val v = composerView(ready.copy(modelLoaded = false))
        assertEquals(ComposerAction.LoadThenSend, v.action)
        assertTrue(v.buttonEnabled && v.fieldEnabled)
        assertEquals("Model not loaded. Send loads it first.", v.reason)
    }

    @Test
    fun failedLoadSaysWhyAndAllowsRetry() {
        val v = composerView(ready.copy(modelLoaded = false, loadError = "Not enough available system memory"))
        assertEquals(ComposerAction.LoadThenSend, v.action)
        assertEquals("Model didn't load: Not enough available system memory", v.reason)
    }

    @Test
    fun queuedSendDisablesUntilLoaded() {
        val v = composerView(ready.copy(modelLoaded = false, localJobBusy = true, engineBusy = true, sendQueued = true))
        assertFalse(v.buttonEnabled)
        assertFalse(v.fieldEnabled)
        assertNotNull(v.reason)
    }

    @Test
    fun busyEngineIsExplained() {
        val v = composerView(ready.copy(engineBusy = true))
        assertFalse(v.buttonEnabled)
        assertEquals("Local AI is busy. Wait for it to finish.", v.reason)
    }

    @Test
    fun runningTaskDisablesField() {
        for (phase in listOf(Phase.Running, Phase.AwaitingApproval)) {
            val v = composerView(ready.copy(phase = phase))
            assertFalse(v.fieldEnabled)
            assertFalse(v.buttonEnabled)
        }
    }

    @Test
    fun lockedOffersEnableWhenEmptyAndSendWithText() {
        val empty = composerView(ready.copy(unlocked = false, textBlank = true))
        assertEquals(ComposerAction.Unlock, empty.action)
        assertTrue(empty.buttonEnabled)
        // With text, Send parks the message and the session asks for the unlock.
        assertEquals(ComposerAction.Send, composerView(ready.copy(unlocked = false)).action)
        assertEquals(ComposerAction.LoadThenSend, composerView(ready.copy(unlocked = false, modelLoaded = false)).action)
    }

    @Test
    fun noModelPointsToSetup() {
        val v = composerView(ready.copy(modelInstalled = false, modelLoaded = false))
        assertEquals(ComposerAction.SetUpAi, v.action)
        assertTrue(v.buttonEnabled)
    }

    @Test
    fun connectedServerIgnoresLocalModelState() {
        assertEquals(ComposerAction.Send, composerView(ready.copy(localSelected = false, modelInstalled = false, modelLoaded = false)).action)
    }

    @Test
    fun emptyTextNeverEnablesSendButExplainsUnloaded() {
        assertFalse(composerView(ready.copy(textBlank = true)).buttonEnabled)
        val unloaded = composerView(ready.copy(textBlank = true, modelLoaded = false))
        assertFalse(unloaded.buttonEnabled)
        assertNotNull(unloaded.reason)
    }

    /** Any enabled button has an action, and any disabled one (with text to send) has a reason. */
    @Test
    fun neverASilentNoOp() {
        val phases = Phase.entries
        val bools = listOf(false, true)
        for (phase in phases) for (unlocked in bools) for (local in bools) for (real in bools) for (installed in bools)
            for (loaded in bools) for (busy in bools) for (job in bools) for (queued in bools) {
                val v = composerView(ComposerInputs(phase, unlocked, local, real, installed, loaded && installed, busy, job, textBlank = false, sendQueued = queued))
                assertEquals(v.buttonEnabled, v.action != ComposerAction.None)
                val running = phase == Phase.Running || phase == Phase.AwaitingApproval
                if (!v.buttonEnabled && !running) assertNotNull(v.reason, "no reason for $phase $unlocked $local $real $installed $loaded $busy $job $queued")
            }
    }
}
