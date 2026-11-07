//! Core as a library: the agent loop over any four ports, a log sink and a cancel check.
//! The harness reaches the same loop through the JSON-lines [`Bridge`](crate::bridge::Bridge)
//! (`agent::run`); the Android and iOS shells reach it through UniFFI (`ffi.rs`). Both get the
//! same step events and the same report.

use serde_json::Value;

use crate::agent;
use crate::bridge::Bridge;
use crate::ports::*;
use crate::types::*;

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

/// The same loop with a host-owned, read-only Tools adapter. The older entry point and
/// current UniFFI shell fail closed for tools until that shell supplies an adapter.
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

    #[test]
    fn stop_during_planning_prevents_a_granted_tool_call() {
        let stop = Cell::new(false);
        struct ToolModel<'a>(&'a Cell<bool>);
        impl Models for ToolModel<'_> {
            fn complete(&mut self, _: ModelRole, _: &[ChatMessage], _: u32) -> PortResult<Completion> {
                self.0.set(true);
                Ok(Completion { text: r#"{"action":"tool","tool":"wallet.balance","args":{"wallet_id":"main","chain_id":1,"asset":"native"}}"#.into(), usage: Usage::default() })
            }
        }
        struct CountingTools(usize);
        impl Tools for CountingTools {
            fn call(&mut self, _: &crate::tools::ToolRequest) -> PortResult<crate::tools::ToolResult> {
                self.0 += 1;
                Ok(crate::tools::ToolResult::Failed { code: "test".into(), message: String::new() })
            }
        }
        let mut state = screen();
        state.capabilities.as_mut().unwrap().tool_grants = vec![crate::tools::ToolGrant {
            tool: "wallet.balance".into(), scope: vec![crate::tools::GrantScope { id: "main".into(), label: None }],
        }];
        let (mut s, mut h, mut c, mut tools) = (Fixed(state), CountingHands(0), Answer(ConfirmResult::Denied, 0), CountingTools(0));
        let result = run_with_tools("balance", 2, &mut s, &mut h, &mut ToolModel(&stop), &mut c,
            &mut tools, &mut |_| {}, &|| stop.get()).unwrap();
        assert_eq!(result["stopped"], true);
        assert_eq!((tools.0, h.0), (0, 0));
    }

    #[test]
    fn legacy_library_entry_has_no_implicit_tool_adapter() {
        let mut state = screen();
        state.capabilities.as_mut().unwrap().tool_grants = vec![crate::tools::ToolGrant {
            tool: "wallet.balance".into(), scope: vec![crate::tools::GrantScope { id: "main".into(), label: None }],
        }];
        let (mut s, mut h, mut m, mut c) = (Fixed(state), CountingHands(0),
            Scripted(vec![r#"{"action":"tool","tool":"wallet.balance","args":{"wallet_id":"main","chain_id":1,"asset":"native"}}"#], 0),
            Answer(ConfirmResult::Denied, 0));
        let result = run("balance", 2, &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| false).unwrap();
        assert_eq!(result["done"], false);
        assert_eq!(result["reason"], "balance could not be refreshed: unsupported");
        assert!(result.get("tool_results").is_none());
        assert_eq!(h.0, 0);
    }
}
