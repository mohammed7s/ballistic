// The accessibility tree of the Computer Control virtual display, as plain data, and its
// translation into core's Node list. Pure Kotlin so the mapping is tested on the JVM; the app
// copies each android.view.accessibility.AccessibilityNodeInfo into an [AxNode] field by field
// (app/.../adapters/ComputerControlPlatform.kt) and does nothing else with it.
//
// The translation is the harness's (harness/core_bridge.py, forest_to_nodes / _windows_to_nodes
// / _role), line for line, so core's filter sees the same shapes on the phone as on the
// emulator it was tuned on:
//   label = content description, else hint text, else tooltip text  (what a control IS)
//   text  = the node's text                                          (what it SHOWS)
// The two are never merged: text may be another app's or another person's content, and only
// core's filter decides what the planner sees (core/README.md §5).
// Ids are 1-based, in pre-order (reading order), unique across windows; parents come first.

package app.ballistic.assistant.computercontrol

import app.ballistic.assistant.core.Bounds
import app.ballistic.assistant.core.Flags
import app.ballistic.assistant.core.Node
import app.ballistic.assistant.core.Role

/** AccessibilityWindowInfo.getType(), reduced to what the adapter distinguishes. */
enum class AxWindowKind { Application, InputMethod, System, Other }

/** One AccessibilityNodeInfo as copied by the platform adapter. Strings as the app set them. */
data class AxNode(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val tooltipText: String? = null,
    /** getViewIdResourceName(), e.g. `com.android.contacts:id/save`. */
    val viewId: String? = null,
    val packageName: String? = null,
    /** getBoundsInScreen() on the session's display. */
    val bounds: Bounds = Bounds(),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = false,
    val focused: Boolean = false,
    val visibleToUser: Boolean = false,
    val children: List<AxNode> = emptyList(),
)

/** One AccessibilityWindowInfo on the session's display. */
data class AxWindow(
    val kind: AxWindowKind,
    val active: Boolean = false,
    val focused: Boolean = false,
    val root: AxNode?,
)

object AxTree {
    /** harness/core_bridge.py `_ROLE_BY_CLASS`, same order, same patterns. */
    private val ROLE_BY_CLASS: List<Pair<Regex, Role>> = listOf(
        Regex("""\.(Button|ImageButton|FloatingActionButton|MaterialButton|Chip)$""") to Role.Button,
        Regex("""\.(EditText|AutoCompleteTextView|TextInputEditText|SearchView)$""") to Role.Edit,
        Regex("""\.(Switch|SwitchCompat|ToggleButton|SwitchMaterial)$""") to Role.Switch,
        Regex("""\.(CheckBox|MaterialCheckBox|RadioButton|CheckedTextView)$""") to Role.Checkbox,
        Regex("""\.(SeekBar|Slider|RangeSlider|ProgressBar)$""") to Role.Slider,
        Regex("""\.(ImageView|AppCompatImageView)$""") to Role.Image,
        Regex("""\.(RecyclerView|ListView|ScrollView|HorizontalScrollView|NestedScrollView|ViewPager2?|GridView)$""") to Role.List,
        Regex("""\.(TextView|MaterialTextView|AppCompatTextView)$""") to Role.Text,
        Regex("""Tab(Layout|View)?$""") to Role.Tab,
        Regex("""\.(FrameLayout|LinearLayout|RelativeLayout|ConstraintLayout|ViewGroup|CoordinatorLayout|CardView|MaterialCardView|ComposeView|View)$""") to Role.Container,
    )

    /** harness `_role`: class pattern first; a clickable container is a button, or an item in a scrollable parent. */
    fun role(className: String?, clickable: Boolean, parentIsList: Boolean): Role {
        val cn = className ?: ""
        for ((rx, role) in ROLE_BY_CLASS) {
            if (rx.containsMatchIn(cn)) {
                if (role == Role.Container && clickable && parentIsList) return Role.Item
                if (role == Role.Container && clickable) return Role.Button
                return role
            }
        }
        return Role.Other
    }

    /** harness `_windows_to_nodes`: every window in the order given, each in pre-order. */
    fun toNodes(windows: List<AxWindow>): List<Node> {
        val out = mutableListOf<Node>()
        for (window in windows) {
            val root = window.root ?: continue
            // Pre-order with an explicit stack: deep trees must not overflow the call stack.
            val stack = ArrayDeque<Triple<AxNode, Int?, AxNode?>>()
            stack.addLast(Triple(root, null, null))
            while (stack.isNotEmpty()) {
                val (n, parentId, parent) = stack.removeLast()
                val id = out.size + 1
                out += Node(
                    id = id,
                    parent = parentId,
                    role = role(n.className, n.clickable, parent?.scrollable == true),
                    className = n.className.orNull(),
                    label = n.contentDescription.orNull() ?: n.hintText.orNull() ?: n.tooltipText.orNull(),
                    text = n.text.orNull(),
                    resourceId = n.viewId.orNull(),
                    `package` = n.packageName.orNull(),
                    bounds = n.bounds,
                    flags = Flags(
                        clickable = n.clickable, longClickable = n.longClickable, editable = n.editable,
                        scrollable = n.scrollable, checkable = n.checkable, checked = n.checked,
                        selected = n.selected, enabled = n.enabled, focused = n.focused,
                        visible = n.visibleToUser,
                    ),
                )
                for (child in n.children.asReversed()) stack.addLast(Triple(child, id, n))
            }
        }
        return out
    }

    /**
     * The package the owner would call "in front" on the session's display: the active or
     * focused application window's root package, else the first application window's. Null when
     * no application window has a package. A host fact, not an activity name (the API exposes
     * none), so ScreenState.activity stays empty and no foreground_component_v1 is advertised.
     */
    fun frontPackage(windows: List<AxWindow>): String? {
        val apps = windows.filter { it.kind == AxWindowKind.Application && it.root?.packageName.orNull() != null }
        val front = apps.firstOrNull { it.active || it.focused } ?: apps.firstOrNull()
        return front?.root?.packageName.orNull()
    }

    private fun String?.orNull(): String? = if (this.isNullOrEmpty()) null else this
}
