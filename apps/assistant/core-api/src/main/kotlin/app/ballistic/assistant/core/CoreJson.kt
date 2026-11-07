// The one JSON configuration used for everything that crosses to or from core.

package app.ballistic.assistant.core

import kotlinx.serialization.json.Json

/**
 * Matches serde's output: defaults are written (Rust always writes `submit`, `flags`, …), nulls
 * are written as `null` (Rust `Option::None`), and unknown keys are rejected. Rust rejects them
 * only for `HandsOp` and `HostCapabilities`; the shell is stricter everywhere because core and
 * shell ship in one APK, so an unknown key means a version mismatch, not a newer peer.
 */
val CoreJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

/**
 * For records written for people, not for decisions: run events, results and log lines. Core
 * may add fields to these before the shell learns them; nothing reads them to decide anything.
 */
val RecordJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
