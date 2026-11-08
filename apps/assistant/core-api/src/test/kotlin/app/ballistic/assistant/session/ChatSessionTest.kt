// Ask as a conversation: each message is one core turn with the stored history; the session shows
// what core returned and stores exactly TurnResult.history. The engine here is scripted: it stands
// in for core's chat.rs, so these tests cover the shell's handling, not the chat model.

package app.ballistic.assistant.session

import app.ballistic.assistant.core.CancelToken
import app.ballistic.assistant.core.ChatTurn
import app.ballistic.assistant.core.CoreEngine
import app.ballistic.assistant.core.CoreOptions
import app.ballistic.assistant.core.CoreRunException
import app.ballistic.assistant.core.ExecutionStatus
import app.ballistic.assistant.core.HandsResult
import app.ballistic.assistant.core.HostPorts
import app.ballistic.assistant.core.RecordJson
import app.ballistic.assistant.core.RunListener
import app.ballistic.assistant.core.RunResult
import app.ballistic.assistant.core.RustCore
import app.ballistic.assistant.core.ScreenState
import app.ballistic.assistant.core.StepEvent
import app.ballistic.assistant.core.TaskStatus
import app.ballistic.assistant.core.TurnKind
import app.ballistic.assistant.core.TurnResult
import app.ballistic.assistant.core.TurnSource
import app.ballistic.assistant.core.ActionLog
import app.ballistic.assistant.core.ConfirmResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ChatSessionTest {
    private class Gate(open: Boolean) : CommandGateState {
        override val unlocked = MutableStateFlow(open)
    }

    /** Stands in for core's converse: [reply] decides the turn and may call the listener. */
    private class ScriptedChat(val reply: (List<ChatTurn>, String, RunListener) -> TurnResult) : CoreEngine {
        val seen = mutableListOf<List<ChatTurn>>()
        override val description = "scripted chat (test)"
        override fun run(request: String, maxSteps: Int, ports: HostPorts, listener: RunListener, cancel: CancelToken): RunResult =
            error("Ask must use converse when a conversation store is present")
        override fun converse(history: List<ChatTurn>, message: String, options: CoreOptions, ports: HostPorts, listener: RunListener, cancel: CancelToken): TurnResult {
            seen += history
            return reply(history, message, listener)
        }
    }

    private val ports = HostPorts(
        screen = { ScreenState("com.android.launcher", ".Home", 1080, 2400, emptyList()) },
        hands = { HandsResult.Ok },
        models = { _, _, _ -> error("unused") },
        confirm = { ConfirmResult.Denied },
    )

    private fun store() = ConversationStore(File.createTempFile("conversation", ".json").apply { delete(); deleteOnExit() })

    private fun TestScope.session(engine: CoreEngine, store: ConversationStore, log: ActionLog? = null) =
        PromptSession(this, Gate(true), ports, { RustCore(engine, it, StandardTestDispatcher(testScheduler)) },
            log = log, conversation = store, route = { "on this phone" })

    private fun owner(text: String) = ChatTurn(TurnSource.Owner, text)
    private fun assistant(text: String) = ChatTurn(TurnSource.Assistant, text)

    @Test
    fun aReplyIsPlainTextWithTheRouteAndNothingRuns() = runTest {
        val store = store()
        val engine = ScriptedChat { _, message, listener ->
            listener.onActivity("Waiting for chat model")
            listener.onActivity("Chat reply received; checking response")
            TurnResult(TurnKind.Reply, "I can't see your phone's CPU from here.", history = listOf(owner(message), assistant("I can't see your phone's CPU from here.")))
        }
        val s = session(engine, store)
        s.submit("what's your CPU?")
        assertEquals(ExchangeKind.Pending, s.state.value.runs.single().kind, "undecided until core answers")
        assertNull(s.state.value.activeTask, "no task card while the chat model decides")
        testScheduler.advanceUntilIdle()
        val run = s.state.value.runs.single()
        assertEquals(ExchangeKind.Reply, run.kind)
        assertFalse(run.isTask)
        assertEquals("I can't see your phone's CPU from here.", run.reply)
        assertEquals("on this phone", run.route)
        assertTrue(run.steps.isEmpty())
        assertEquals(listOf(owner("what's your CPU?"), assistant("I can't see your phone's CPU from here.")), store.load())
    }

    @Test
    fun theStoredHistoryGoesBackOnTheNextTurnCappedAtTwelve() = runTest {
        val store = store()
        val engine = ScriptedChat { _, message, _ -> TurnResult(TurnKind.Reply, "ok", history = listOf(owner(message), assistant("ok"))) }
        val s = session(engine, store)
        for (i in 1..8) {
            s.submit("message $i")
            testScheduler.advanceUntilIdle()
        }
        assertEquals(emptyList(), engine.seen.first())
        assertEquals(listOf(owner("message 1"), assistant("ok")), engine.seen[1])
        assertEquals(12, engine.seen.last().size, "the 8th turn sees the last 12 stored turns")
        assertEquals(owner("message 2"), engine.seen.last().first())
        assertEquals(12, store.load().size)
        assertEquals(8, s.state.value.runs.size)
        assertEquals(listOf("message 6", "message 7", "message 8"), recentForAsk(s.state.value.runs).map { it.request })
    }

    @Test
    fun aTaskTurnBecomesACardAndItsResultLineIsCores() = runTest {
        val store = store()
        val logFile = File.createTempFile("log", ".jsonl").apply { deleteOnExit() }
        // Core's report for the owner's "what's your CPU?" when the planner answered.
        val report = RecordJson.parseToJsonElement("""
            {"done":false,"reason":"I do not have access to the device's CPU information.","steps":1,
             "task_status":"pending","execution_status":"not_executed",
             "verification":{"status":"unknown","evidence_basis":"answer_values_sourced"},
             "answer":{"text":"I do not have access to the device's CPU information.",
                       "display":"I do not have access to the device's CPU information.","values":[]}}""")
        lateinit var s: PromptSession
        val engine = ScriptedChat { _, message, listener ->
            listener.onActivity("Waiting for chat model")
            assertNull(s.state.value.activeTask)
            listener.onActivity("Reading available screen information")
            assertEquals(ExchangeKind.Task, s.state.value.activeTask?.kind, "the loop started: show the card")
            listener.onStep(StepEvent(1, buildJsonObject { put("action", JsonPrimitive("answer")); put("text", JsonPrimitive("I do not have access…")) },
                outcome = "answered", taskStatus = TaskStatus.Pending, executionStatus = ExecutionStatus.NotExecuted))
            TurnResult(TurnKind.Task, "I do not have access to the device's CPU information.", request = "what is the phone's CPU", report = report,
                history = listOf(owner(message), ChatTurn(TurnSource.TaskResult, "Task: what is the phone's CPU → answered, not verified")))
        }
        s = session(engine, store, ActionLog(logFile))
        s.submit("what's your CPU?")
        testScheduler.advanceUntilIdle()
        val run = s.state.value.runs.single()
        assertTrue(run.isTask)
        assertEquals("what's your CPU?", run.request, "line 1 is the owner's words")
        assertEquals("what is the phone's CPU", run.ranAs)
        assertEquals("I do not have access to the device's CPU information.", runStatusLine(run))
        assertEquals("ANSWER", runOutcome(run).label)
        assertFalse("input" in runStatusLine(run).lowercase())
        assertEquals(1, ActionLog(logFile).read().lines.size, "task steps reach Activity")
        assertEquals("what's your CPU?", ActionLog(logFile).read().lines.single().request)
    }

    @Test
    fun theThreadSurvivesARestartAndClearForgetsIt() = runTest {
        val store = store()
        store.append(listOf(owner("hi"), assistant("Hello."), owner("open Clock"),
            ChatTurn(TurnSource.TaskResult, "Task: open Clock → opened, not confirmed")))
        val s = session(ScriptedChat { _, _, _ -> error("unused") }, store)
        val restored = s.state.value.runs
        assertEquals(listOf("hi", "open Clock"), restored.map { it.request })
        assertEquals(ExchangeKind.Reply, restored[0].kind)
        assertEquals("Hello.", restored[0].reply)
        assertTrue(restored[1].isTask && restored[1].finished)
        assertEquals("Opened, not confirmed", runStatusLine(restored[1]))
        assertEquals("NOT CONFIRMED", runOutcome(restored[1]).label)
        assertNull(s.state.value.activeTask)

        assertTrue(s.clearConversation())
        assertTrue(s.state.value.runs.isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aFailedTurnShowsAShortErrorAndStoresNothing() = runTest {
        val store = store()
        val s = session(ScriptedChat { _, _, _ -> throw CoreRunException("model port failed: HTTP 500 {\"detail\":\"secret\"}") }, store)
        s.submit("hello")
        testScheduler.advanceUntilIdle()
        val run = s.state.value.runs.single()
        assertEquals(ExchangeKind.Error, run.kind)
        assertEquals("Something went wrong", runStatusLine(run))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aDecodableButUnknownReportStillShowsCoresLine() = runTest {
        val store = store()
        val s = session(ScriptedChat { _, m, _ ->
            TurnResult(TurnKind.Task, "I opened it, but I haven't confirmed anything beyond that.", request = m,
                report = RecordJson.encodeToJsonElement(RunResult.serializer(), RunResult(false, "opened", 1, TaskStatus.OpenedUi, ExecutionStatus.Executed)),
                history = listOf(owner(m)))
        }, store)
        s.submit("open Clock")
        testScheduler.advanceUntilIdle()
        val run = s.state.value.runs.single()
        assertEquals("I opened it, but I haven't confirmed anything beyond that.", runStatusLine(run))
        assertEquals("NOT CONFIRMED", runOutcome(run).label)
    }

    @Test
    fun theStoreReadsACorruptFileAsEmpty() {
        val file = File.createTempFile("conversation", ".json").apply { deleteOnExit(); writeText("{not json") }
        assertTrue(ConversationStore(file).load().isEmpty())
    }
}
