// Data that crosses the four ports: the Kotlin side of core/src/types.rs. Every class here has
// the same JSON form as its Rust serde shape (field names, `action`/`op`/`result`/`kind` tags,
// snake_case enum values, explicit nulls), so a value encoded here decodes in core and back.
// core/src/types.rs is the authority; when it changes, this file and JsonCompatibilityTest change
// with it. Rust u32 fields are Kotlin Int: ids, coordinates and token counts fit, and a value
// above Int.MAX_VALUE fails to decode (malformed) rather than wrapping.

package app.ballistic.assistant.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Identifier of a node within one observation. Not stable across observations. */
typealias NodeId = Int

/** What a node is, as far as the host can tell from class name and flags. */
@Serializable
enum class Role {
    @SerialName("button") Button,
    @SerialName("text") Text,
    @SerialName("edit") Edit,
    @SerialName("image") Image,
    @SerialName("list") List,
    @SerialName("item") Item,
    @SerialName("switch") Switch,
    @SerialName("checkbox") Checkbox,
    @SerialName("slider") Slider,
    @SerialName("tab") Tab,
    @SerialName("container") Container,
    @SerialName("other") Other,
}

@Serializable
data class Bounds(val x1: Int = 0, val y1: Int = 0, val x2: Int = 0, val y2: Int = 0)

/** All false by default, as Rust's `#[serde(default)]` on `Flags`. */
@Serializable
data class Flags(
    val clickable: Boolean = false,
    @SerialName("long_clickable") val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = false,
    val focused: Boolean = false,
    val visible: Boolean = false,
)

/**
 * One accessibility node as the host saw it. [label] is what the control IS (content
 * description, caption, hint); [text] is what it SHOWS and may be someone else's content. The
 * host fills both; core's filter decides which the planner sees.
 */
@Serializable
data class Node(
    val id: NodeId,
    val parent: NodeId? = null,
    val role: Role,
    @SerialName("class_name") val className: String? = null,
    val label: String? = null,
    val text: String? = null,
    @SerialName("resource_id") val resourceId: String? = null,
    val `package`: String? = null,
    val bounds: Bounds,
    val flags: Flags = Flags(),
)

@Serializable
data class AppEntry(
    val label: String,
    val `package`: String,
    /** Component the host starts for this app, if it knows one. */
    val activity: String? = null,
)

/** One observation. Nodes in reading order, parents before children. */
@Serializable
data class ScreenState(
    val `package`: String,
    val activity: String,
    val width: Int,
    val height: Int,
    val nodes: kotlin.collections.List<Node>,
    /** Sent once per session; core keeps the last non-empty list. */
    @SerialName("installed_apps") val installedApps: kotlin.collections.List<AppEntry> = emptyList(),
    /** Host authority, never model text. Null parses old recordings but core denies execution. */
    val capabilities: HostCapabilities? = null,
    /** Explicit host readback only; notifications and launch acknowledgments cannot set it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val foreground: ForegroundObservation? = null,
)

/** Fresh foreground component sampled by this observe call. Positive increasing host sequence.
 * ULong mirrors Rust u64; this is call freshness, not a source capture generation. */
@Serializable
data class ForegroundObservation(
    val `package`: String,
    val activity: String,
    val sequence: ULong,
)

@Serializable
enum class Direction {
    @SerialName("up") Up,
    @SerialName("down") Down,
    @SerialName("left") Left,
    @SerialName("right") Right,
}

/**
 * What the planner may emit (core/README.md §6), in core's internal form: `target` is the numeric
 * element id. The planner's wire form (`"e7"`) and the log form are produced by core, not here.
 */
@Serializable
@JsonClassDiscriminator("action")
sealed interface Action {
    @Serializable @SerialName("open_app") data class OpenApp(val app: String) : Action
    @Serializable @SerialName("tap") data class Tap(val target: Int) : Action
    @Serializable @SerialName("long_press") data class LongPress(val target: Int) : Action
    @Serializable @SerialName("type")
    data class Type(val target: Int, val text: String, val submit: Boolean = false) : Action
    @Serializable @SerialName("scroll")
    data class Scroll(val target: Int? = null, val direction: Direction) : Action
    @Serializable @SerialName("swipe") data class Swipe(val target: Int, val direction: Direction) : Action
    @Serializable @SerialName("back") data object Back : Action
    @Serializable @SerialName("home") data object Home : Action
    @Serializable @SerialName("wait") data object Wait : Action
    @Serializable @SerialName("read")
    data class Read(val targets: kotlin.collections.List<Int>, val question: String, val shape: ReadShape) : Action
    @Serializable @SerialName("answer") data class Answer(val text: String) : Action
    @Serializable @SerialName("done") data class Done(val evidence: String) : Action
    @Serializable @SerialName("give_up") data class GiveUp(val reason: String) : Action
}

/** The fixed shapes a reader answer may take. */
@Serializable
enum class ReadShape {
    @SerialName("element") Element,
    @SerialName("facts") Facts,
    @SerialName("summary") Summary,
}

/**
 * Where a value came from. Set by ports and core's filter, never by a model. Rust encodes this
 * adjacently tagged (`{"kind":"app","detail":"pkg"}`, unit variants without `detail`), which
 * kotlinx has no built-in form for, hence [SourceSerializer].
 */
@Serializable(with = SourceSerializer::class)
sealed interface Source {
    data object OwnerRequest : Source
    data object OwnerData : Source
    data object ScreenLabel : Source
    data class App(val detail: String) : Source
    data class Sms(val detail: String) : Source
    data class Note(val detail: String) : Source
    data class Web(val detail: String) : Source
    /** Written by a model with no other source; [detail] is the role (`planner`, `chat`). */
    data class Model(val detail: String) : Source

    /** Trusted sources may become action arguments without the taint rule firing. */
    val isOwner: Boolean get() = this is OwnerRequest || this is OwnerData || this is ScreenLabel
}

object SourceSerializer : KSerializer<Source> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Source) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Source is JSON-only")
        val (kind, detail) = when (value) {
            Source.OwnerRequest -> "owner_request" to null
            Source.OwnerData -> "owner_data" to null
            Source.ScreenLabel -> "screen_label" to null
            is Source.App -> "app" to value.detail
            is Source.Sms -> "sms" to value.detail
            is Source.Note -> "note" to value.detail
            is Source.Web -> "web" to value.detail
            is Source.Model -> "model" to value.detail
        }
        json.encodeJsonElement(buildJsonObject {
            put("kind", JsonPrimitive(kind))
            if (detail != null) put("detail", JsonPrimitive(detail))
        })
    }

    override fun deserialize(decoder: Decoder): Source {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Source is JSON-only")
        val obj = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Source must be an object")
        val unknown = obj.keys - setOf("kind", "detail")
        if (unknown.isNotEmpty()) throw SerializationException("unknown Source fields $unknown")
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: throw SerializationException("Source without kind")
        fun detail(): String = obj["detail"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Source $kind needs detail")
        return when (kind) {
            "owner_request" -> Source.OwnerRequest
            "owner_data" -> Source.OwnerData
            "screen_label" -> Source.ScreenLabel
            "app" -> Source.App(detail())
            "sms" -> Source.Sms(detail())
            "note" -> Source.Note(detail())
            "web" -> Source.Web(detail())
            "model" -> Source.Model(detail())
            else -> throw SerializationException("unknown Source kind $kind")
        }
    }
}

/** A string plus where it came from. */
@Serializable
data class Tagged(val value: String, val source: Source)

/**
 * What Hands executes: coordinates and strings only, no element ids. Rust denies unknown fields
 * here; [CoreJson] does the same for every class.
 */
@Serializable
@JsonClassDiscriminator("op")
sealed interface HandsOp {
    @Serializable @SerialName("open_app")
    data class OpenApp(val `package`: String, val activity: String? = null) : HandsOp
    @Serializable @SerialName("tap") data class Tap(val x: Int, val y: Int) : HandsOp
    @Serializable @SerialName("long_press") data class LongPress(val x: Int, val y: Int) : HandsOp
    /** Tap the field first, then type. `submit` presses Enter afterwards. */
    @Serializable @SerialName("type")
    data class Type(val x: Int, val y: Int, val text: String, val submit: Boolean) : HandsOp
    /** Drag inside [area] so content moves to reveal what lies in [direction]. */
    @Serializable @SerialName("scroll") data class Scroll(val area: Bounds, val direction: Direction) : HandsOp
    /** Drag across [area] in [direction] itself (sliders, carousels). */
    @Serializable @SerialName("swipe") data class Swipe(val area: Bounds, val direction: Direction) : HandsOp
    @Serializable @SerialName("back") data object Back : HandsOp
    @Serializable @SerialName("home") data object Home : HandsOp
    @Serializable @SerialName("wait") data class Wait(val millis: Int) : HandsOp
}

/** Operation id as core's capabilities.rs names it; what [HostCapabilities.operations] lists. */
val HandsOp.operationId: String
    get() = when (this) {
        is HandsOp.OpenApp -> "open_app"
        is HandsOp.Tap -> "tap"
        is HandsOp.LongPress -> "long_press"
        is HandsOp.Type -> "type"
        is HandsOp.Scroll -> "scroll"
        is HandsOp.Swipe -> "swipe"
        HandsOp.Back -> "back"
        HandsOp.Home -> "home"
        is HandsOp.Wait -> "wait"
    }

@Serializable
@JsonClassDiscriminator("result")
sealed interface HandsResult {
    @Serializable @SerialName("ok") data object Ok : HandsResult
    /** Something went wrong after input may have been dispatched; core treats the effect as unknown. */
    @Serializable @SerialName("error") data class Error(val message: String) : HandsResult
    /** Host guarantees no input was dispatched. */
    @Serializable @SerialName("not_executed") data class NotExecuted(val message: String) : HandsResult
}

/** Which model answers. Each may be a different backend. */
@Serializable
enum class ModelRole {
    @SerialName("planner") Planner,
    @SerialName("reader") Reader,
    @SerialName("judge") Judge,
    @SerialName("verifier") Verifier,
    /** One conversation turn (core/src/chat.rs). Route it like [Planner]. */
    @SerialName("chat") Chat,
}

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
data class Completion(val text: String, val usage: Usage = Usage())

/**
 * What the model a role is routed to can hold, prompt plus output (core/src/types.rs
 * `ModelLimits`, used by core's context guard). Null: not known, so core enforces nothing.
 */
@Serializable
data class ModelLimits(@SerialName("context_tokens") val contextTokens: Int? = null)

/** What the owner is shown before a guarded action. Every value names its source. */
@Serializable
data class ConfirmRequest(
    /** Exact resolved parameters covered by this one-use approval. */
    val operation: HandsOp,
    val app: String,
    val verb: String,
    val target: String,
    val values: kotlin.collections.List<Tagged> = emptyList(),
    val hard: Boolean,
    val reason: String,
)

@Serializable
enum class ConfirmResult {
    @SerialName("approved") Approved,
    @SerialName("denied") Denied,
}

/** A host can restrict the shipped action set, never extend it with executable app text. */
@Serializable
data class HostCapabilities(
    val operations: kotlin.collections.List<String>,
    @SerialName("app_grants") val appGrants: kotlin.collections.List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val observations: kotlin.collections.List<String> = emptyList(),
)

@Serializable
enum class ExecutionStatus {
    @SerialName("not_executed") NotExecuted,
    @SerialName("executed") Executed,
    @SerialName("unknown") Unknown,
}

@Serializable
enum class VerificationStatus {
    @SerialName("verified") Verified,
    @SerialName("failed") Failed,
    @SerialName("unknown") Unknown,
}

@Serializable
data class Verification(
    val status: VerificationStatus = VerificationStatus.Unknown,
    /** Set by core. A model never upgrades itself to authoritative readback. */
    @SerialName("evidence_basis") val evidenceBasis: String = "none",
    val nodes: kotlin.collections.List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val foreground: ForegroundObservation? = null,
)
