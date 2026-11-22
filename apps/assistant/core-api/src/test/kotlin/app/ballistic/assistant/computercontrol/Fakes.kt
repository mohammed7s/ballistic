package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Bounds

/** Records every Computer Control call as a line; [fail] throws on the named call. */
class FakeInput(var fail: Map<String, RuntimeException> = emptyMap()) : CcInput {
    val calls = mutableListOf<String>()
    private fun record(name: String, line: String) {
        fail[name]?.let { throw it }
        calls += line
    }
    override fun tap(x: Int, y: Int) = record("tap", "tap $x,$y")
    override fun longPress(x: Int, y: Int) = record("longPress", "longPress $x,$y")
    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = record("swipe", "swipe $fromX,$fromY->$toX,$toY")
    override fun insertText(text: String, replaceExisting: Boolean, commit: Boolean) =
        record("insertText", "insertText '$text' replace=$replaceExisting commit=$commit")
    override fun back() = record("back", "back")
    override fun launch(`package`: String, activity: String?) = record("launch", "launch ${`package`} ${activity ?: "-"}")
}

/** A session whose state and windows the test sets. */
class FakeSession(
    override var state: CcState,
    var windowList: List<AxWindow> = emptyList(),
    override val width: Int = 1080,
    override val height: Int = 2400,
) : CcSessionView {
    override val input = FakeInput()
    var windowReads = 0
    /** Returned by successive windows() calls before [windowList]; lets a test start with an empty tree. */
    val firstReads = ArrayDeque<List<AxWindow>>()
    override fun windows(): List<AxWindow> {
        windowReads++
        return firstReads.removeFirstOrNull() ?: windowList
    }
}

fun appWindow(pkg: String, vararg children: AxNode, active: Boolean = true) = AxWindow(
    kind = AxWindowKind.Application, active = active, focused = active,
    root = AxNode(className = "android.widget.FrameLayout", packageName = pkg, bounds = Bounds(0, 0, 1080, 2400),
        visibleToUser = true, enabled = true, children = children.toList()),
)
