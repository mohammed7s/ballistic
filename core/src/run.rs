//! Core as a library: the agent loop over any four ports, a log sink and a cancel check.
//! The harness reaches the same loop through the JSON-lines [`Bridge`](crate::bridge::Bridge)
//! (`agent::run`); the Android and iOS shells reach it through UniFFI (`ffi.rs`). Both get the
//! same step events and the same report.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::agent;
use crate::bridge::Bridge;
use crate::ports::*;
use crate::types::*;

/// What a host may set per call (core/README.md §12). The app and the harness differ only in
/// their defaults: the deny-only judge (§8) is on for the app and off for the harness, so
/// benchmark runs stay comparable with earlier ones. Missing fields take the app defaults.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Options {
    /// Step budget of a task, including one started from a conversation turn.
    pub max_steps: u32,
    /// One `judge` model call before every state-changing action. It can only block.
    pub judge: bool,
    /// Earlier conversation turns kept in the chat prompt (`chat.rs`), at most 50.
    pub chat_history_turns: u32,
    /// Skills the host stored from earlier verified runs (`skills.rs`). A run whose request
    /// matches one exactly replays it; every check still applies.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub skills: Vec<crate::skills::Skill>,
    /// Corrections a step gives a rejected planner reply before the run ends (default 2, at most
    /// `agent::MAX_PLANNER_CORRECTIONS`). Each is one more planner call with the error and one
    /// correct example of the action attempted. Written only when not the default, so the JSON
    /// hosts mirror (apps/assistant JsonCompatibilityTest) is unchanged.
    #[serde(skip_serializing_if = "default_corrections")]
    pub planner_corrections: u32,
}

pub const DEFAULT_PLANNER_CORRECTIONS: u32 = 2;
fn default_corrections(n: &u32) -> bool { *n == DEFAULT_PLANNER_CORRECTIONS }

impl Options {
    pub fn app() -> Self {
        Options { max_steps: 15, judge: true, chat_history_turns: crate::chat::DEFAULT_HISTORY_TURNS, skills: vec![], planner_corrections: DEFAULT_PLANNER_CORRECTIONS }
    }
    pub fn harness() -> Self {
        Options { judge: false, ..Options::app() }
    }
}

impl Default for Options {
    fn default() -> Self {
        Options::app()
    }
}

/// Everything the loop needs from a host: the four ports, somewhere to put step events, and
/// whether the owner pressed Stop. Core checks [`Host::cancelled`] before every observation,
/// before every model call and immediately before every dispatch.
pub trait Host: Screen + Hands + Models + Confirm + Tools {
    fn log(&mut self, event: &Value) -> PortResult<()>;
    fn cancelled(&self) -> bool {
        false
    }
}

impl<R: std::io::BufRead, W: std::io::Write> Host for Bridge<R, W> {
    fn log(&mut self, event: &Value) -> PortResult<()> {
        Bridge::log(self, event)
    }
}

/// Four separately owned ports plus a log sink and a cancel check, seen as one [`Host`].
struct Ports<'a> {
    screen: &'a mut dyn Screen,
    hands: &'a mut dyn Hands,
    models: &'a mut dyn Models,
    confirm: &'a mut dyn Confirm,
    log: &'a mut dyn FnMut(&Value),
    cancelled: &'a dyn Fn() -> bool,
    tools: Option<&'a mut dyn Tools>,
}

impl Screen for Ports<'_> {
    fn observe(&mut self) -> PortResult<ScreenState> {
        self.screen.observe()
    }
}

impl Hands for Ports<'_> {
    fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> {
        self.hands.act(op)
    }
}

impl Models for Ports<'_> {
    fn complete(&mut self, role: ModelRole, messages: &[ChatMessage], max_tokens: u32) -> PortResult<Completion> {
        self.models.complete(role, messages, max_tokens)
    }
    fn limits(&mut self, role: ModelRole) -> PortResult<ModelLimits> {
        self.models.limits(role)
    }
    fn count_tokens(&mut self, role: ModelRole, messages: &[ChatMessage]) -> PortResult<Option<u32>> {
        self.models.count_tokens(role, messages)
    }
}

impl Confirm for Ports<'_> {
    fn ask(&mut self, request: &ConfirmRequest) -> PortResult<ConfirmResult> {
        self.confirm.ask(request)
    }
}

impl Tools for Ports<'_> {
    fn call(&mut self, request: &crate::tools::ToolRequest) -> PortResult<crate::tools::ToolResult> {
        match self.tools.as_mut() {
            Some(tools) => tools.call(request),
            None => Ok(crate::tools::ToolResult::Failed {
                code: "unsupported".into(), message: "host has no Tools adapter".into(),
            }),
        }
    }
}

impl Host for Ports<'_> {
    fn log(&mut self, event: &Value) -> PortResult<()> {
        (self.log)(event);
        Ok(())
    }
    fn cancelled(&self) -> bool {
        (self.cancelled)()
    }
}

/// Runs `request` to a terminal report. `log` receives each step event (the object the harness
/// receives as `{"ask":"log","event":...}`). A stop returns a report with `"stopped": true`.
/// Harness options: no judge. Hosts that want the app defaults call [`run_with_options`].
#[allow(clippy::too_many_arguments)]
pub fn run(
    request: &str,
    max_steps: u32,
    screen: &mut dyn Screen,
    hands: &mut dyn Hands,
    models: &mut dyn Models,
    confirm: &mut dyn Confirm,
    log: &mut dyn FnMut(&Value),
    cancelled: &dyn Fn() -> bool,
) -> PortResult<Value> {
    let mut host = Ports { screen, hands, models, confirm, log, cancelled, tools: None };
    agent::drive(&mut host, request, max_steps)
}

/// [`run`] with explicit [`Options`]; `options.max_steps` is ignored in favour of `max_steps`.
#[allow(clippy::too_many_arguments)]
pub fn run_with_options(
    request: &str,
    max_steps: u32,
    options: &Options,
    screen: &mut dyn Screen,
    hands: &mut dyn Hands,
    models: &mut dyn Models,
    confirm: &mut dyn Confirm,
    log: &mut dyn FnMut(&Value),
    cancelled: &dyn Fn() -> bool,
) -> PortResult<Value> {
    let mut host = Ports { screen, hands, models, confirm, log, cancelled, tools: None };
    agent::drive_task(&mut host, &agent::Task::owner(request), max_steps, options)
}

/// One conversation turn (`chat.rs`): the history the host keeps, the owner's new message.
/// A `task` turn runs the agent loop on the same ports; a `reply` never reaches Hands.
#[allow(clippy::too_many_arguments)]
pub fn converse(
    history: &[crate::chat::Turn],
    message: &str,
    options: &Options,
    screen: &mut dyn Screen,
    hands: &mut dyn Hands,
    models: &mut dyn Models,
    confirm: &mut dyn Confirm,
    log: &mut dyn FnMut(&Value),
    cancelled: &dyn Fn() -> bool,
) -> PortResult<crate::chat::TurnResult> {
    let mut host = Ports { screen, hands, models, confirm, log, cancelled, tools: None };
    crate::chat::converse(&mut host, history, message, options)
}

/// The same loop with an optional read-only Tools host port. No request variants are
/// currently supported, so this port cannot be called by the planner.
#[allow(clippy::too_many_arguments)]
pub fn run_with_tools(
    request: &str,
    max_steps: u32,
    screen: &mut dyn Screen,
    hands: &mut dyn Hands,
    models: &mut dyn Models,
    confirm: &mut dyn Confirm,
    tools: &mut dyn Tools,
    log: &mut dyn FnMut(&Value),
    cancelled: &dyn Fn() -> bool,
) -> PortResult<Value> {
    let mut host = Ports { screen, hands, models, confirm, log, cancelled, tools: Some(tools) };
    agent::drive(&mut host, request, max_steps)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::cell::Cell;

    fn screen() -> ScreenState {
        serde_json::from_value(json!({"package":"example.notes","activity":"Main","width":100,"height":100,
            "capabilities":{"operations":["open_app","tap","wait"],"app_grants":["example.notes"]},
            "installed_apps":[{"label":"Notes","package":"example.notes"}],
            "nodes":[{"id":1,"role":"button","text":"Delete","bounds":{"x1":0,"y1":0,"x2":40,"y2":20},
                "flags":{"enabled":true,"visible":true,"clickable":true}}]})).unwrap()
    }

    struct Fixed(ScreenState);
    impl Screen for Fixed {
        fn observe(&mut self) -> PortResult<ScreenState> {
            Ok(self.0.clone())
        }
    }
    struct CountingHands(usize);
    impl Hands for CountingHands {
        fn act(&mut self, _: &HandsOp) -> PortResult<HandsResult> {
            self.0 += 1;
            Ok(HandsResult::Ok)
        }
    }
    struct Scripted(Vec<&'static str>, usize);
    impl Models for Scripted {
        fn complete(&mut self, _: ModelRole, _: &[ChatMessage], _: u32) -> PortResult<Completion> {
            let text = self.0.get(self.1).copied().unwrap_or(r#"{"action":"give_up","reason":"end of script"}"#);
            self.1 += 1;
            Ok(Completion { text: text.into(), usage: Usage::default() })
        }
    }
    struct Answer(ConfirmResult, usize);
    impl Confirm for Answer {
        fn ask(&mut self, _: &ConfirmRequest) -> PortResult<ConfirmResult> {
            self.1 += 1;
            Ok(self.0)
        }
    }

    #[test]
    fn open_app_through_separate_ports_logs_the_bridge_step_event() {
        let (mut s, mut h, mut m, mut c) = (Fixed(screen()), CountingHands(0), Scripted(vec![r#"{"action":"open_app","app":"Notes","why":"asked"}"#], 0), Answer(ConfirmResult::Approved, 0));
        let mut events = Vec::new();
        let report = run("open notes", 1, &mut s, &mut h, &mut m, &mut c, &mut |e| events.push(e.clone()), &|| false).unwrap();
        assert_eq!(report["task_status"], "opened_ui");
        assert_eq!(h.0, 1);
        assert_eq!(m.1, 1);
        assert_eq!(events.len(), 1);
        for key in ["step", "action", "why", "outcome", "task_status", "execution_status", "verification", "failure_category", "step_execution_status", "capability_mode", "seconds"] {
            assert!(events[0].get(key).is_some(), "{key}");
        }
    }

    #[test]
    fn cancelled_before_start_makes_no_calls() {
        let (mut s, mut h, mut m, mut c) = (Fixed(screen()), CountingHands(0), Scripted(vec![], 0), Answer(ConfirmResult::Approved, 0));
        let report = run("open notes", 5, &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| true).unwrap();
        assert_eq!(report["stopped"], true);
        assert_eq!(report["steps"], 0);
        assert_eq!((h.0, m.1, c.1), (0, 0, 0));
    }

    #[test]
    fn stop_after_approval_prevents_the_dispatch() {
        // The owner approves, then presses Stop before core dispatches: nothing reaches Hands.
        let stop = Cell::new(false);
        struct StopOnAsk<'a>(&'a Cell<bool>);
        impl Confirm for StopOnAsk<'_> {
            fn ask(&mut self, _: &ConfirmRequest) -> PortResult<ConfirmResult> {
                self.0.set(true);
                Ok(ConfirmResult::Approved)
            }
        }
        let (mut s, mut h, mut m) = (Fixed(screen()), CountingHands(0), Scripted(vec![r#"{"action":"tap","target":"e1"}"#], 0));
        let report = run("delete the note", 5, &mut s, &mut h, &mut m, &mut StopOnAsk(&stop), &mut |_| {}, &|| stop.get()).unwrap();
        assert_eq!(report["stopped"], true);
        assert_eq!(report["execution_status"], "not_executed");
        assert_eq!(h.0, 0);
    }

    #[test]
    fn stop_between_steps_prevents_the_next_model_call() {
        let calls = Cell::new(0usize);
        struct CountingModels<'a>(&'a Cell<usize>);
        impl Models for CountingModels<'_> {
            fn complete(&mut self, _: ModelRole, _: &[ChatMessage], _: u32) -> PortResult<Completion> {
                self.0.set(self.0.get() + 1);
                Ok(Completion { text: r#"{"action":"wait"}"#.into(), usage: Usage::default() })
            }
        }
        let (mut s, mut h, mut c) = (Fixed(screen()), CountingHands(0), Answer(ConfirmResult::Approved, 0));
        let report = run("wait", 5, &mut s, &mut h, &mut CountingModels(&calls), &mut c, &mut |_| {}, &|| calls.get() >= 1).unwrap();
        assert_eq!(report["stopped"], true);
        assert_eq!(calls.get(), 1);
        // Stop arrived during the model call: the wait it planned is not dispatched either.
        assert_eq!(h.0, 0);
    }

    #[test]
    fn denied_approval_dispatches_nothing() {
        let (mut s, mut h, mut m, mut c) = (Fixed(screen()), CountingHands(0), Scripted(vec![r#"{"action":"tap","target":"e1"}"#], 0), Answer(ConfirmResult::Denied, 0));
        let report = run("delete the note", 5, &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| false).unwrap();
        assert_eq!(report["reason"], "owner denied confirmation");
        assert_eq!((h.0, c.1), (0, 1));
    }

}
