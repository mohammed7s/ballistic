//! Placing a call is guarded like Send (core/src/calls.rs, SPEC.md §7.1), on every route: a tap on
//! a dialer's call control asks the owner, a denial dispatches nothing, and a `dial` intent only
//! fills the dialer, so the call is still that guarded tap. Scripted ports only.
mod common;
use ballistic_core::{operations::{IntentGrant, IntentOp}, screen_filter::{self, FilterContext}, types::*};
use common::*;
use serde_json::json;

const DIALER: &str = "com.google.android.dialer";

/// A dialpad as the AOSP/Google dialer exposes it: the call button (label "dial", resource id
/// `dialpad_voice_call_button`) and the Recents tab (`tab_call_history`).
fn dialpad() -> ScreenState {
    let node = |id: u32, label: &str, rid: &str, y: i32| json!({"id":id,"role":"button","label":label,"package":DIALER,
        "resource_id":format!("{DIALER}:id/{rid}"),"bounds":{"x1":0,"y1":y,"x2":400,"y2":y+80},
        "flags":{"enabled":true,"visible":true,"clickable":true}});
    serde_json::from_value(json!({"package":DIALER,"activity":"Main","width":400,"height":800,
        "capabilities":{"operations":["tap","open_app","back","home","wait"],"app_grants":[DIALER],
            "intents":[{"operation":"dial","package":DIALER}]},
        "installed_apps":[{"label":"Phone","package":DIALER}],
        "nodes":[node(1, "dial", "dialpad_voice_call_button", 600), node(2, "Recents", "tab_call_history", 700),
            node(3, "Call Ana Lopez", "primary_action_view", 100)]})).unwrap()
}

fn element(s: &ScreenState, request: &str, node: NodeId) -> String {
    let view = screen_filter::filter(s, &FilterContext { request, typed: &[] });
    format!("e{}", view.elements.iter().find(|e| e.node == node).unwrap().id)
}

fn tap(target: &str) -> String { format!(r#"{{"action":"tap","target":"{target}"}}"#) }

#[test]
fn a_tap_on_the_dialers_call_button_needs_the_owners_yes_and_a_denial_dispatches_nothing() {
    let s = dialpad();
    let request = "call 5550100";
    for node in [1, 3] {
        let mut fake = Fake { screen: Some(s.clone()), planner: vec![tap(&element(&s, request, node))], approve: false, ..Fake::default() };
        let r = drive(&mut fake, request, 1, &harness());
        assert_eq!(fake.asked.len(), 1, "node {node}");
        assert!(fake.asked[0].hard);
        assert_eq!(fake.asked[0].reason, "activate a control that places a phone call");
        assert!(fake.acted.is_empty(), "denied: no tap");
        assert_eq!(r["failure_category"], "policy_denial");
    }
    let mut fake = Fake { screen: Some(s.clone()), planner: vec![tap(&element(&s, request, 1))], approve: true, ..Fake::default() };
    drive(&mut fake, request, 1, &harness());
    assert_eq!((fake.asked.len(), fake.acted.len()), (1, 1), "approved: the one tap is dispatched");
}

#[test]
fn call_navigation_is_not_guarded() {
    let s = dialpad();
    let mut fake = Fake { screen: Some(s.clone()), planner: vec![tap(&element(&s, "show my recent calls", 2))], ..Fake::default() };
    drive(&mut fake, "show my recent calls", 1, &harness());
    assert!(fake.asked.is_empty(), "the Recents tab places no call");
    assert_eq!(fake.acted.len(), 1);
}

#[test]
fn a_dial_intent_fills_the_dialer_and_the_call_is_still_a_guarded_tap() {
    let s = dialpad();
    let request = "call 555 0100";
    let dial = r#"{"action":"intent","operation":"dial","args":{"number":"555 0100"}}"#;
    let mut fake = Fake { screen: Some(s.clone()), planner: vec![dial.into(), tap(&element(&s, request, 1))], approve: false, ..Fake::default() };
    let r = drive(&mut fake, request, 3, &harness());
    assert_eq!(fake.acted, vec![HandsOp::Intent { package: DIALER.into(), intent: IntentOp::Dial { number: "5550100".into() } }],
        "the owner's number fills the dialer without a confirmation; the denied call tap is not dispatched");
    assert_eq!(fake.asked.len(), 1);
    assert_eq!(fake.asked[0].reason, "activate a control that places a phone call");
    assert_eq!(r["failure_category"], "policy_denial");
}

#[test]
fn a_recipient_shown_only_in_a_control_label_needs_a_hard_confirmation() {
    // An app writes its own labels: a number the owner did not give is confirmed whatever shows it.
    let mut s = dialpad();
    s.nodes.push(serde_json::from_value(json!({"id":4,"role":"button","label":"+1 900 555 0199","package":DIALER,
        "resource_id":format!("{DIALER}:id/suggestion"),"bounds":{"x1":0,"y1":300,"x2":400,"y2":380},
        "flags":{"enabled":true,"visible":true,"clickable":true}})).unwrap());
    s.capabilities.as_mut().unwrap().intents = vec![IntentGrant { operation: "dial".into(), package: DIALER.into() }];
    let dial = r#"{"action":"intent","operation":"dial","args":{"number":"+1 900 555 0199"}}"#;
    let mut fake = Fake { screen: Some(s), planner: vec![dial.into()], approve: false, ..Fake::default() };
    drive(&mut fake, "call the number on the screen", 1, &harness());
    assert_eq!(fake.asked.len(), 1);
    assert_eq!(fake.asked[0].values, vec![Tagged { value: "+1 900 555 0199".into(), source: Source::Model("planner".into()) }]);
    assert!(fake.acted.is_empty());
}
