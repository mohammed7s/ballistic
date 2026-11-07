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
///   (`planner`, `reader`, `judge`, `verifier`)
/// - `confirm(ConfirmRequest) -> ConfirmResult` (`"approved"` or `"denied"`, a JSON string)
/// - `log(step event)`: the object the harness receives as `{"ask":"log","event":...}`
/// - `is_cancelled()`: the owner pressed Stop
#[uniffi::export(callback_interface)]
pub trait HostPorts: Send + Sync {
    fn observe(&self) -> Result<String, HostError>;
    fn act(&self, op_json: String) -> Result<String, HostError>;
    fn complete(&self, role: String, messages_json: String, max_tokens: u32) -> Result<String, HostError>;
    fn confirm(&self, request_json: String) -> Result<String, HostError>;
    fn log(&self, event_json: String);
    fn is_cancelled(&self) -> bool;
}

/// Runs `request` to the end and returns the final line the harness also receives:
/// `{"finished":true,"report":{...}}`, or `{"finished":true,"error":"..."}` if a port failed in
/// a way the loop does not handle itself (the run stopped; effects already reported in step
/// events stand).
#[uniffi::export]
pub fn run(request: String, max_steps: u32, host: Box<dyn HostPorts>) -> String {
    let host = host.as_ref();
    let mut log = |event: &Value| host.log(event.to_string());
    let cancelled = || host.is_cancelled();
    // The four ports are one object on the shell side; `run::run` takes them separately, so
    // lend it four views of the same host. Every shell method takes `&self`.
    let (mut screen, mut hands, mut models, mut confirm) = (Json(host), Json(host), Json(host), Json(host));
    let result = crate::run::run(&request, max_steps, &mut screen, &mut hands, &mut models, &mut confirm, &mut log, &cancelled);
    match result {
        Ok(report) => json!({"finished": true, "report": report}),
        Err(e) => json!({"finished": true, "error": e.to_string()}),
    }
    .to_string()
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

impl Models for Json<'_> {
    fn complete(&mut self, role: ModelRole, messages: &[ChatMessage], max_tokens: u32) -> PortResult<Completion> {
        let role = match role {
            ModelRole::Planner => "planner",
            ModelRole::Reader => "reader",
            ModelRole::Judge => "judge",
            ModelRole::Verifier => "verifier",
        };
        decode(self.0.complete(role.into(), encode(messages)?, max_tokens))
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
            Ok(r#"{"text":"{\"action\":\"open_app\",\"app\":\"Clock\",\"why\":\"asked\"}","usage":{"prompt_tokens":10,"completion_tokens":5}}"#.into())
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
        Shell { calls: Mutex::new(vec![]), events: Mutex::new(vec![]), hands }
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
    fn host_errors_keep_their_kind() {
        let host = shell("throw");
        let mut h = Json(&host);
        let err = h.act(&HandsOp::Home).unwrap_err();
        assert!(matches!(err, PortError::Disconnected(m) if m == "gone"));
        let err = decode::<ScreenState>(Ok("not json".into())).unwrap_err();
        assert!(matches!(err, PortError::Malformed(_)));
    }
}
