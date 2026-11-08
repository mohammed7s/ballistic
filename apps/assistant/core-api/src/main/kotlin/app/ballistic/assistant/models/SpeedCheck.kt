package app.ballistic.assistant.models

import app.ballistic.assistant.core.ChatMessage

/**
 * Fixed, synthetic prompts for the Local AI speed checks. No owner data, screen content or
 * phone action is involved. The long check approximates an agent-length prefill: a planner call
 * carries roughly two thousand tokens of instructions and observations. The runtime reports the
 * exact prompt-token count it processed; this file only targets the size.
 */
enum class SpeedCheck(val label: String, val maxTokens: Int) {
    Short("Short prompt", 32),
    LongPrefill("~2K-token prefill", 16);

    fun messages(): List<ChatMessage> = when (this) {
        Short -> listOf(ChatMessage("user", "Count from one to ten in words. Keep the answer brief."))
        LongPrefill -> listOf(
            ChatMessage("system", "You are reading a list of interface elements on a phone screen. Answer with one number only."),
            ChatMessage("user", longPrefillBody()),
        )
    }

    companion object {
        /** 90 lines measured 2,530 Qwen3 prompt tokens with llama.cpp; 72 targets about 2,000. */
        const val LONG_PREFILL_LINES = 72

        /** Roughly 28 tokens per line for the Qwen3 tokenizer; deterministic for comparisons. */
        fun longPrefillBody(): String = buildString {
            append("Screen elements, one per line, in reading order.\n")
            for (i in 1..LONG_PREFILL_LINES) {
                append("Element ").append(i).append(": button labelled \"Item ").append(i)
                    .append("\" in row ").append((i - 1) / 4 + 1).append(", column ").append((i - 1) % 4 + 1)
                    .append(", enabled, not selected.\n")
            }
            append("Which element number is labelled \"Item 42\"? Reply with the number only.")
        }
    }
}

/** One completed speed check, kept in memory for side-by-side comparison in this process. */
data class SpeedResult(
    val modelId: String,
    val modelName: String,
    val runtime: ModelRuntime,
    val backend: ComputeBackend,
    val backendReport: String?,
    val check: SpeedCheck,
    val metrics: LocalMetrics,
)
