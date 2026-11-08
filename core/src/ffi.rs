//! The in-process binding for the app shells (UniFFI, feature `uniffi`). The boundary carries
//! the same JSON strings as the JSON-lines bridge (`bridge.rs`): every value is the serde form
//! of a type in `types.rs`, so serde stays the one contract and the shells decode it into their
//! own mirror classes (Kotlin: `apps/assistant/core-api/.../core/Types.kt`).
//!
//! Generated Kotlin: `apps/assistant/core-api/src/main/kotlin/app/ballistic/assistant/core/ffi/`
//! (`apps/assistant/scripts/build_core.sh`; commands in core/README.md §15).

use serde::{de::DeserializeOwned, Serialize};
use serde_json::{json, Value};

use crate::ports::*;
use crate::types::*;

/// A port failure as the host reports it; core maps it to [`PortError`]. A Kotlin exception
/// that is not one of these arrives as `Host`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum HostError {
    #[error("host disconnected: {reason}")]
    Disconnected { reason: String },
    #[error("host returned malformed data: {reason}")]
    Malformed { reason: String },
    #[error("host error: {reason}")]
    Host { reason: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for HostError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        HostError::Host { reason: e.reason }
    }
}

impl From<HostError> for PortError {
    fn from(e: HostError) -> Self {
        match e {
            HostError::Disconnected { reason } => PortError::Disconnected(reason),
            HostError::Malformed { reason } => PortError::Malformed(reason),
            HostError::Host { reason } => PortError::Host(reason),
        }
    }
}

/// What the shell implements: the four ports, the log sink and the Stop flag, all in the JSON
/// forms of `types.rs`. Called on the thread that called [`run`]; each call blocks core.
///
/// - `observe() -> ScreenState`
/// - `act(HandsOp) -> HandsResult`
/// - `complete(role, [ChatMessage], max_tokens) -> Completion`; `role` is the bare name
///   (`planner`, `reader`, `judge`, `verifier`, `chat`)
/// - `model_limits(role) -> ModelLimits` (`{"context_tokens":4096}`, or `{"context_tokens":null}`
///   when the routed model's context is not known); asked before every model call by the
///   context guard (`context.rs`)
/// - `count_tokens(role, [ChatMessage])`: the exact prompt size from the routed model's
///   tokenizer, or null to let core use its conservative estimate
/// - `confirm(ConfirmRequest) -> ConfirmResult` (`"approved"` or `"denied"`, a JSON string)
/// - `log(step event)`: the object the harness receives as `{"ask":"log","event":...}`
/// - `is_cancelled()`: the owner pressed Stop
#[uniffi::export(callback_interface)]
pub trait HostPorts: Send + Sync {
    fn observe(&self) -> Result<String, HostError>;
    fn act(&self, op_json: String) -> Result<String, HostError>;
    fn complete(&self, role: String, messages_json: String, max_tokens: u32) -> Result<String, HostError>;
    fn model_limits(&self, role: String) -> Result<String, HostError>;
    fn count_tokens(&self, role: String, messages_json: String) -> Result<Option<u32>, HostError>;
    fn confirm(&self, request_json: String) -> Result<String, HostError>;
    fn log(&self, event_json: String);
    fn is_cancelled(&self) -> bool;
}

/// Runs `request` to the end and returns the final line the harness also receives:
/// `{"finished":true,"report":{...}}`, or `{"finished":true,"error":"..."}` if a port failed in
/// a way the loop does not handle itself (the run stopped; effects already reported in step
/// events stand). Uses the app's default [`Options`](crate::run::Options) (judge on).
#[uniffi::export]
pub fn run(request: String, max_steps: u32, host: Box<dyn HostPorts>) -> String {
    run_with_options(request, max_steps, String::new(), host)
}

/// [`run`] with options as JSON (`{"judge":true,"chat_history_turns":12,"max_steps":15,
/// "skills":[...]}`, every field optional, missing fields take the app defaults; `max_steps`
/// here is ignored in favour of the argument; `skills` are the `skill` objects of earlier
/// verified reports). Unknown fields or a malformed object end with an `error` line and no
/// port call.
#[uniffi::export]
pub fn run_with_options(request: String, max_steps: u32, options_json: String, host: Box<dyn HostPorts>) -> String {
    let options = match options(&options_json) {
        Ok(o) => o,
        Err(e) => return json!({"finished": true, "error": e}).to_string(),
    };
    let host = host.as_ref();
    let mut log = |event: &Value| host.log(event.to_string());
    let cancelled = || host.is_cancelled();
    // The four ports are one object on the shell side; `run::run` takes them separately, so
    // lend it four views of the same host. Every shell method takes `&self`.
    let (mut screen, mut hands, mut models, mut confirm) = (Json(host), Json(host), Json(host), Json(host));
    let result = crate::run::run_with_options(&request, max_steps, &options, &mut screen, &mut hands, &mut models, &mut confirm, &mut log, &cancelled);
    match result {
        Ok(report) => json!({"finished": true, "report": report}),
        Err(e) => json!({"finished": true, "error": e.to_string()}),
    }
    .to_string()
}

/// One conversation turn (`chat.rs`). `history_json` is the array of turns the shell keeps
/// (`[{"source":"owner"|"assistant"|"task_result","text":"...","values":[Tagged]}]`), exactly
/// as earlier turns returned them; `options_json` as for [`run_with_options`]. Returns
/// `{"finished":true,"turn":{TurnResult}}` or `{"finished":true,"error":"..."}`. A `task` turn
/// runs the agent loop on `host` and logs its step events as `run` does.
#[uniffi::export]
pub fn converse(history_json: String, message: String, options_json: String, host: Box<dyn HostPorts>) -> String {
    let options = match options(&options_json) {
        Ok(o) => o,
        Err(e) => return json!({"finished": true, "error": e}).to_string(),
    };
    let history: Vec<crate::chat::Turn> = match serde_json::from_str(if history_json.trim().is_empty() { "[]" } else { &history_json }) {
        Ok(h) => h,
        Err(e) => return json!({"finished": true, "error": format!("history: {e}")}).to_string(),
    };
    let host = host.as_ref();
    let mut log = |event: &Value| host.log(event.to_string());
    let cancelled = || host.is_cancelled();
    let (mut screen, mut hands, mut models, mut confirm) = (Json(host), Json(host), Json(host), Json(host));
    match crate::run::converse(&history, &message, &options, &mut screen, &mut hands, &mut models, &mut confirm, &mut log, &cancelled) {
        Ok(turn) => json!({"finished": true, "turn": turn}),
        Err(e) => json!({"finished": true, "error": e.to_string()}),
    }
    .to_string()
}

fn options(text: &str) -> Result<crate::run::Options, String> {
    if text.trim().is_empty() {
        return Ok(crate::run::Options::app());
    }
    serde_json::from_str(text).map_err(|e| format!("options: {e}"))
}

/// The crate version, so a shell can show which core it loaded.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").into()
}

/// One port of a [`HostPorts`], speaking serde JSON.
struct Json<'a>(&'a dyn HostPorts);

fn encode<T: Serialize + ?Sized>(value: &T) -> PortResult<String> {
    serde_json::to_string(value).map_err(|e| PortError::Malformed(e.to_string()))
}

fn decode<T: DeserializeOwned>(text: Result<String, HostError>) -> PortResult<T> {
    let text = text?;
    serde_json::from_str(&text).map_err(|e| PortError::Malformed(e.to_string()))
}

impl Screen for Json<'_> {
    fn observe(&mut self) -> PortResult<ScreenState> {
        decode(self.0.observe())
    }
}

impl Hands for Json<'_> {
    fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> {
        decode(self.0.act(encode(op)?))
    }
}

fn role_name(role: ModelRole) -> String {
    match role {
        ModelRole::Planner => "planner",
        ModelRole::Reader => "reader",
        ModelRole::Judge => "judge",
        ModelRole::Verifier => "verifier",
        ModelRole::Chat => "chat",
    }
    .into()
}

impl Models for Json<'_> {
    fn complete(&mut self, role: ModelRole, messages: &[ChatMessage], max_tokens: u32) -> PortResult<Completion> {
        decode(self.0.complete(role_name(role), encode(messages)?, max_tokens))
    }
    fn limits(&mut self, role: ModelRole) -> PortResult<ModelLimits> {
        decode(self.0.model_limits(role_name(role)))
    }
    fn count_tokens(&mut self, role: ModelRole, messages: &[ChatMessage]) -> PortResult<Option<u32>> {
        Ok(self.0.count_tokens(role_name(role), encode(messages)?)?)
    }
}

impl Confirm for Json<'_> {
    fn ask(&mut self, request: &ConfirmRequest) -> PortResult<ConfirmResult> {
        decode(self.0.confirm(encode(request)?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// A host that answers like the Kotlin shell would, in JSON strings.
    struct Shell {
        calls: Mutex<Vec<String>>,
        events: Mutex<Vec<String>>,
        hands: &'static str,
        limits: &'static str,
    }

    impl HostPorts for Shell {
        fn observe(&self) -> Result<String, HostError> {
            self.calls.lock().unwrap().push("observe".into());
            Ok(r#"{"package":"com.android.systemui","activity":"notification_listener","width":100,"height":100,"nodes":[],
                "installed_apps":[{"label":"Clock","package":"com.android.deskclock","activity":null}],
                "capabilities":{"operations":["open_app","wait"],"app_grants":["com.android.deskclock"]}}"#.into())
        }
        fn act(&self, op_json: String) -> Result<String, HostError> {
            self.calls.lock().unwrap().push(format!("act {op_json}"));
            if self.hands == "throw" {
                return Err(HostError::Disconnected { reason: "gone".into() });
            }
            Ok(r#"{"result":"ok"}"#.into())
        }
        fn complete(&self, role: String, messages_json: String, max_tokens: u32) -> Result<String, HostError> {
            let messages: Vec<ChatMessage> = serde_json::from_str(&messages_json).unwrap();
            assert_eq!(messages[0].role, "system");
            self.calls.lock().unwrap().push(format!("complete {role} {max_tokens}"));
            match role.as_str() {
                "chat" => Ok(r#"{"text":"{\"kind\":\"task\",\"request\":\"open the Clock app\"}"}"#.into()),
                "judge" => Ok(r#"{"text":"{\"verdict\":\"allow\"}"}"#.into()),
                _ => Ok(r#"{"text":"{\"action\":\"open_app\",\"app\":\"Clock\",\"why\":\"asked\"}","usage":{"prompt_tokens":10,"completion_tokens":5}}"#.into()),
            }
        }
        fn model_limits(&self, _role: String) -> Result<String, HostError> {
            Ok(self.limits.into())
        }
        fn count_tokens(&self, _role: String, _messages_json: String) -> Result<Option<u32>, HostError> {
            Ok(None)
        }
        fn confirm(&self, _: String) -> Result<String, HostError> {
            Ok(r#""denied""#.into())
        }
        fn log(&self, event_json: String) {
            self.events.lock().unwrap().push(event_json);
        }
        fn is_cancelled(&self) -> bool {
            false
        }
    }

    fn shell(hands: &'static str) -> Shell {
        Shell { calls: Mutex::new(vec![]), events: Mutex::new(vec![]), hands, limits: r#"{"context_tokens":null}"# }
    }

    #[test]
    fn json_boundary_runs_the_loop() {
        let out: Value = serde_json::from_str(&run("open the Clock app".into(), 1, Box::new(shell("ok")))).unwrap();
        assert_eq!(out["finished"], true);
        assert_eq!(out["report"]["task_status"], "opened_ui");
        assert_eq!(out["report"]["execution_status"], "executed");
    }

    #[test]
    fn calls_and_events_cross_as_serde_json() {
        let host = shell("ok");
        let mut s = Json(&host);
        let (mut h, mut m, mut c) = (Json(&host), Json(&host), Json(&host));
        let events = Mutex::new(Vec::new());
        let report = crate::run::run("open the Clock app", 1, &mut s, &mut h, &mut m, &mut c,
            &mut |e| events.lock().unwrap().push(e.clone()), &|| host.is_cancelled()).unwrap();
        assert_eq!(report["task_status"], "opened_ui");
        let calls = host.calls.lock().unwrap().clone();
        assert_eq!(calls, vec![
            "observe".to_string(),
            "complete planner 800".to_string(),
            r#"act {"op":"open_app","package":"com.android.deskclock","activity":null}"#.to_string(),
        ]);
        assert_eq!(events.lock().unwrap()[0]["outcome"], "opened_ui");
    }

    #[test]
    fn converse_crosses_as_json_and_runs_a_task_turn() {
        let host = shell("ok");
        let history = r#"[{"source":"owner","text":"hello"},{"source":"assistant","text":"Hi."}]"#;
        let out: Value = serde_json::from_str(&converse(history.into(), "open clock".into(), r#"{"max_steps":1,"judge":false}"#.into(), Box::new(host))).unwrap();
        let turn = &out["turn"];
        assert_eq!(turn["kind"], "task");
        assert_eq!(turn["request"], "open the Clock app");
        assert_eq!(turn["report"]["task_status"], "opened_ui");
        assert_eq!(turn["history"][1], json!({"source":"task_result","text":"Task: open the Clock app → opened, not confirmed"}));
    }

    #[test]
    fn malformed_options_or_history_end_with_an_error() {
        for (history, options) in [("[]", r#"{"judge":"yes"}"#), ("[]", r#"{"unknown":1}"#), (r#"[{"source":"screen","text":"x"}]"#, "")] {
            let out: Value = serde_json::from_str(&converse(history.into(), "hi".into(), options.into(), Box::new(shell("ok")))).unwrap();
            assert!(out["error"].is_string(), "{history} {options}");
        }
        let out: Value = serde_json::from_str(&run_with_options("open".into(), 1, "{".into(), Box::new(shell("ok")))).unwrap();
        assert!(out["error"].as_str().unwrap().starts_with("options:"));
    }

    #[test]
    fn model_limits_cross_as_json_and_guard_the_call() {
        // No limit: the call is unchanged (see calls_and_events_cross_as_serde_json).
        let mut host = shell("ok");
        host.limits = r#"{"context_tokens":4096}"#;
        let mut m = Json(&host);
        assert_eq!(m.limits(ModelRole::Planner).unwrap(), ModelLimits { context_tokens: Some(4096) });
        assert_eq!(m.count_tokens(ModelRole::Planner, &[]).unwrap(), None);
        let out: Value = serde_json::from_str(&run("open the Clock app".into(), 1, Box::new(host))).unwrap();
        assert_eq!(out["report"]["task_status"], "opened_ui");
        // A limit too small for the planner's rules is refused before any model call.
        let mut host = shell("ok");
        host.limits = r#"{"context_tokens":1024}"#;
        let out: Value = serde_json::from_str(&run("open the Clock app".into(), 1, Box::new(host))).unwrap();
        assert_eq!(out["report"]["failure_category"], "model_context");
        assert!(out["report"]["reason"].as_str().unwrap().starts_with("prompt does not fit this model's 1,024-token context"));
        let host = shell("ok");
        let bad = Shell { limits: r#"{"context":4096}"#, ..host };
        assert!(matches!(Json(&bad).limits(ModelRole::Planner), Err(PortError::Malformed(_))));
    }

    #[test]
    fn host_errors_keep_their_kind() {
        let host = shell("throw");
        let mut h = Json(&host);
        let err = h.act(&HandsOp::Home).unwrap_err();
        assert!(matches!(err, PortError::Disconnected(m) if m == "gone"));
        let err = decode::<ScreenState>(Ok("not json".into())).unwrap_err();
        assert!(matches!(err, PortError::Malformed(_)));
    }
}
