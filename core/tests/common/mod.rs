//! Scripted ports shared by tests/reader_facts.rs and tests/taint_and_judge.rs. Every model
//! reply comes from a per-role queue; every call is recorded.
#![allow(dead_code)]
use ballistic_core::{ports::*, run::{self, Options}, types::*};
use serde_json::{json, Value};
use std::cell::RefCell;

pub fn screen(package: &str, rows: &[&str]) -> ScreenState {
    let mut nodes = vec![json!({"id":1,"role":"edit","label":"To","package":package,"resource_id":format!("{package}:id/to"),
        "bounds":{"x1":0,"y1":0,"x2":400,"y2":40},"flags":{"enabled":true,"visible":true,"editable":true,"clickable":true}}),
        json!({"id":2,"role":"button","label":"Delete","package":package,"resource_id":format!("{package}:id/delete"),
        "bounds":{"x1":0,"y1":50,"x2":100,"y2":90},"flags":{"enabled":true,"visible":true,"clickable":true}})];
    for (i, r) in rows.iter().enumerate() {
        nodes.push(json!({"id":i+3,"role":"text","text":r,"package":package,"resource_id":format!("{package}:id/body{i}"),
            "bounds":{"x1":0,"y1":100+i*100,"x2":400,"y2":190+i*100},"flags":{"enabled":true,"visible":true}}));
    }
    serde_json::from_value(json!({"package":package,"activity":"Main","width":400,"height":800,
        "capabilities":{"operations":["tap","type","open_app","back","home","wait","scroll"],"app_grants":[package]},
        "installed_apps":[{"label":"Messages","package":package}],"nodes":nodes})).unwrap()
}

#[derive(Default)]
pub struct Fake {
    pub screen: Option<ScreenState>,
    pub planner: Vec<String>,
    pub reader: Vec<String>,
    pub judge: Vec<String>,
    pub calls: Vec<(ModelRole, Vec<ChatMessage>)>,
    pub acted: Vec<HandsOp>,
    pub asked: Vec<ConfirmRequest>,
    pub approve: bool,
    pub events: Vec<Value>,
    /// Context limit advertised per role (none listed: no limit), the exact count the host
    /// claims for every prompt (none: core estimates), the usage every completion reports, and
    /// the max_tokens core sent with each call, in call order.
    pub limits: Vec<(ModelRole, u32)>,
    pub exact: Option<u32>,
    pub usage: Usage,
    pub sent_max: Vec<(ModelRole, u32)>,
}

impl Fake {
    pub fn prompts(&self, role: ModelRole) -> Vec<String> {
        self.calls.iter().filter(|(r, _)| *r == role).map(|(_, m)| serde_json::to_string(m).unwrap()).collect()
    }
    pub fn count(&self, role: ModelRole) -> usize {
        self.calls.iter().filter(|(r, _)| *r == role).count()
    }
}

impl Screen for Fake {
    fn observe(&mut self) -> PortResult<ScreenState> { Ok(self.screen.clone().expect("screen")) }
}
impl Hands for Fake {
    fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> { self.acted.push(op.clone()); Ok(HandsResult::Ok) }
}
impl Models for Fake {
    fn limits(&mut self, role: ModelRole) -> PortResult<ModelLimits> {
        Ok(ModelLimits { context_tokens: self.limits.iter().find(|(r, _)| *r == role).map(|(_, n)| *n) })
    }
    fn count_tokens(&mut self, _: ModelRole, _: &[ChatMessage]) -> PortResult<Option<u32>> { Ok(self.exact) }
    fn complete(&mut self, role: ModelRole, messages: &[ChatMessage], max_tokens: u32) -> PortResult<Completion> {
        self.calls.push((role, messages.to_vec()));
        self.sent_max.push((role, max_tokens));
        let (queue, end) = match role {
            ModelRole::Reader => (&mut self.reader, "none"),
            ModelRole::Judge => (&mut self.judge, r#"{"verdict":"allow"}"#),
            ModelRole::Verifier => (&mut self.reader, r#"{"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}"#),
            _ => (&mut self.planner, r#"{"action":"give_up","reason":"end of script"}"#),
        };
        let text = if queue.is_empty() { end.to_string() } else { queue.remove(0) };
        Ok(Completion { text, usage: self.usage.clone() })
    }
}
impl Confirm for Fake {
    fn ask(&mut self, request: &ConfirmRequest) -> PortResult<ConfirmResult> {
        self.asked.push(request.clone());
        Ok(if self.approve { ConfirmResult::Approved } else { ConfirmResult::Denied })
    }
}

/// One run through the in-process entry point; the four ports are views of one fake.
pub fn drive(fake: &mut Fake, request: &str, steps: u32, options: &Options) -> Value {
    let cell = RefCell::new(std::mem::take(fake));
    struct View<'a>(&'a RefCell<Fake>);
    impl Screen for View<'_> { fn observe(&mut self) -> PortResult<ScreenState> { self.0.borrow_mut().observe() } }
    impl Hands for View<'_> { fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> { self.0.borrow_mut().act(op) } }
    impl Models for View<'_> {
        fn complete(&mut self, r: ModelRole, m: &[ChatMessage], t: u32) -> PortResult<Completion> { self.0.borrow_mut().complete(r, m, t) }
        fn limits(&mut self, r: ModelRole) -> PortResult<ModelLimits> { self.0.borrow_mut().limits(r) }
        fn count_tokens(&mut self, r: ModelRole, m: &[ChatMessage]) -> PortResult<Option<u32>> { self.0.borrow_mut().count_tokens(r, m) }
    }
    impl Confirm for View<'_> { fn ask(&mut self, q: &ConfirmRequest) -> PortResult<ConfirmResult> { self.0.borrow_mut().ask(q) } }
    let (mut s, mut h, mut m, mut c) = (View(&cell), View(&cell), View(&cell), View(&cell));
    let mut events = Vec::new();
    let report = run::run_with_options(request, steps, options, &mut s, &mut h, &mut m, &mut c, &mut |e| events.push(e.clone()), &|| false).unwrap();
    *fake = cell.into_inner();
    fake.events = events;
    report
}

pub fn harness() -> Options { Options::harness() }
pub fn with_judge() -> Options { Options { judge: true, ..Options::harness() } }
