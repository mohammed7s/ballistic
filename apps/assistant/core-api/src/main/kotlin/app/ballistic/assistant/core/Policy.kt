// The owner's per-app policy file (core/README.md §9, SPEC.md §4.3/§7.4) and the floor it can
// never go below. The file is written by the owner in Settings, behind the command gate, and
// lives in app-private storage. A file can make the assistant more careful, never less: the
// hard-confirmation list is a code constant, and an unreadable file falls back to the defaults.
//
// Enforcement belongs to core (core/policy, not written yet). The shell uses this for Settings
// and as a second, tighten-only check in its Confirm adapter ([ConfirmFloor]).

package app.ballistic.assistant.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Observe {
    @SerialName("none") None,
    @SerialName("notifications") Notifications,
    @SerialName("tree") Tree,
    @SerialName("tree_screenshot") TreeScreenshot,
}

@Serializable
enum class Act {
    @SerialName("none") None,
    @SerialName("navigate") Navigate,
    @SerialName("full") Full,
}

@Serializable
enum class Route {
    @SerialName("default") Default,
    @SerialName("device_only") DeviceOnly,
}

@Serializable
enum class ConfirmMode {
    /** The code constant plus core's taint rule and Rule of Two. */
    @SerialName("default_list") DefaultList,
    /** Every guarded operation in this app takes a hard (biometric) confirmation. */
    @SerialName("always") Always,
    /** The assistant may not act in this app at all. */
    @SerialName("never_automate") NeverAutomate,
}

@Serializable
data class AppPolicy(
    val observe: Observe = Observe.Tree,
    val act: Act = Act.Full,
    val route: Route = Route.Default,
    val confirm: ConfirmMode = ConfirmMode.DefaultList,
)

/**
 * The file: `{"version":1,"apps":{"<package>":{"observe":"tree","act":"full","route":"default",
 * "confirm":"default_list"}}}`. Apps not listed get [defaultFor].
 */
@Serializable
data class PolicyFile(
    val version: Int = 1,
    val apps: Map<String, AppPolicy> = emptyMap(),
) {
    fun forApp(pkg: String): AppPolicy = apps[pkg] ?: defaultFor(pkg)

    fun with(pkg: String, policy: AppPolicy): PolicyFile = copy(apps = apps + (pkg to policy))

    fun encode(): String = CoreJson.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        /** Device-only by default (core/README.md §9). Banking apps are added as they are identified. */
        val DEVICE_ONLY_DEFAULT: Set<String> = setOf("org.thoughtcrime.securesms")

        fun defaultFor(pkg: String): AppPolicy =
            if (pkg in DEVICE_ONLY_DEFAULT) AppPolicy(route = Route.DeviceOnly) else AppPolicy()

        /**
         * Strict parse (unknown keys and values rejected). Any failure returns the defaults, never
         * a partly read file: a corrupted or hand-edited file cannot drop a setting silently.
         */
        fun decodeOrDefault(text: String?): PolicyFile {
            if (text.isNullOrBlank()) return PolicyFile()
            return try {
                CoreJson.decodeFromString(serializer(), text).takeIf { it.version == VERSION } ?: PolicyFile()
            } catch (e: IllegalArgumentException) {
                PolicyFile()
            }
        }
    }
}

enum class ConfirmClass { Soft, Hard, Refuse }

/**
 * The tighten-only rule. Given what core asked for and the owner's policy for the app, returns
 * the confirmation the window must apply. It can raise Soft to Hard, or refuse; it never lowers.
 */
object ConfirmFloor {
    /**
     * Always hard, whatever the policy file says: SPEC.md §7.1 (send, pay, delete, post, install,
     * grant) plus core's CONFIRM_WORDS (core/src/actions.rs: remove, purchase) and its
     * "type and submit" reason.
     */
    val HARD_WORDS: Set<String> = setOf("send", "pay", "delete", "remove", "post", "install", "grant", "purchase", "submit")

    fun required(request: ConfirmRequest, policy: AppPolicy): ConfirmClass {
        if (policy.confirm == ConfirmMode.NeverAutomate || policy.act == Act.None) return ConfirmClass.Refuse
        if (request.hard) return ConfirmClass.Hard
        if (policy.confirm == ConfirmMode.Always) return ConfirmClass.Hard
        // A tainted value (not the owner's) in a soft request is still raised: the taint rule's
        // floor is a hard confirmation (SPEC.md §7.2–7.3).
        if (request.values.any { !it.source.isOwner }) return ConfirmClass.Hard
        val words = listOf(request.verb, request.target, request.reason)
            .flatMap { it.lowercase().split(Regex("[^a-z]+")) }
            .toSet()
        return if (words.any { it in HARD_WORDS }) ConfirmClass.Hard else ConfirmClass.Soft
    }
}
