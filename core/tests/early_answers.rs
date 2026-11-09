//! Honest early answers (capability_gaps.rs): a request needing a capability the host does not
//! advertise is answered at once in plain words, with no planner call and no input. Fake ports.
mod common;
use ballistic_core::{chat::TurnKind, ports::*, run, types::*};
use common::*;
use serde_json::json;
use std::cell::RefCell;

/// The Android app today advertises IntentHands' operations only (open_app, wait).
fn phone() -> ScreenState {
    serde_json::from_value(json!({"package":"com.android.systemui","activity":"notification_listener","width":100,"height":100,"nodes":[],
        "installed_apps":[{"label":"Clock","package":"com.google.android.deskclock"},{"label":"Settings","package":"com.android.settings"}],
        "capabilities":{"operations":["open_app","wait"],"app_grants":["com.google.android.deskclock","com.android.settings"]}})).unwrap()
}

fn emulator() -> ScreenState {
    let mut s = phone();
    s.capabilities.as_mut().unwrap().operations = ["open_app", "tap", "type", "scroll", "swipe", "back", "home", "wait"].map(String::from).to_vec();
    s
}

fn converse(fake: &mut Fake, message: &str) -> ballistic_core::chat::TurnResult {
    let cell = RefCell::new(std::mem::take(fake));
    struct View<'a>(&'a RefCell<Fake>);
    impl Screen for View<'_> { fn observe(&mut self) -> PortResult<ScreenState> { self.0.borrow_mut().observe() } }
    impl Hands for View<'_> { fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> { self.0.borrow_mut().act(op) } }
    impl Models for View<'_> {
        fn complete(&mut self, r: ModelRole, m: &[ChatMessage], t: u32) -> PortResult<Completion> { self.0.borrow_mut().complete(r, m, t) }
    }
    impl Confirm for View<'_> { fn ask(&mut self, q: &ConfirmRequest) -> PortResult<ConfirmResult> { self.0.borrow_mut().ask(q) } }
    let (mut s, mut h, mut m, mut c) = (View(&cell), View(&cell), View(&cell), View(&cell));
    let turn = run::converse(&[], message, &harness(), &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| false).unwrap();
    *fake = cell.into_inner();
    turn
}

#[test]
fn an_alarm_on_the_phone_is_answered_without_a_planner_call() {
    let mut f = Fake { screen: Some(phone()), planner: vec![r#"{"kind":"task","request":"set an alarm for 7am"}"#.into()], ..Fake::default() };
    let turn = converse(&mut f, "wake me at 7");
    assert_eq!(turn.kind, TurnKind::Task);
    assert_eq!(turn.text, "I can't set alarms on this phone yet.");
    assert_eq!((f.count(ModelRole::Chat), f.count(ModelRole::Planner)), (1, 0));
    assert!(f.acted.is_empty() && f.asked.is_empty());
    let report = turn.report.unwrap();
    assert_eq!(report["task_status"], "unsupported");
    assert_eq!(report["failure_category"], "capability");
    assert_eq!(report["steps"], 0);
    // The history line says what happened in core's fixed words.
    assert_eq!(turn.history[1].text, "Task: set an alarm for 7am → not supported");
}

#[test]
fn airplane_mode_and_settings_changes_on_the_phone_are_answered_too() {
    for (request, answer) in [("turn on airplane mode", "I can't turn airplane mode on or off on this phone yet."),
        ("turn off wifi", "I can't change settings like Wi-Fi, Bluetooth or brightness on this phone yet.")] {
        let mut f = Fake { screen: Some(phone()), ..Fake::default() };
        let report = drive(&mut f, request, 10, &harness());
        assert_eq!(report["reason"], answer);
        assert_eq!(f.count(ModelRole::Planner), 0);
        assert!(f.acted.is_empty());
    }
}

#[test]
fn a_host_that_can_tap_in_the_app_plans_as_before() {
    let mut f = Fake { screen: Some(emulator()), planner: vec![r#"{"action":"open_app","app":"Clock"}"#.into()], ..Fake::default() };
    drive(&mut f, "set an alarm for 7am", 1, &harness());
    assert_eq!(f.count(ModelRole::Planner), 1);
    assert_eq!(f.acted.len(), 1);
    // And on the phone, what it can do (opening an app) still plans.
    let mut g = Fake { screen: Some(phone()), planner: vec![r#"{"action":"open_app","app":"Clock"}"#.into()], ..Fake::default() };
    drive(&mut g, "open the clock app", 1, &harness());
    assert_eq!(g.count(ModelRole::Planner), 1);
}
