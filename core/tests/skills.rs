//! Skills record and replay (core/README.md §11). Scripted models: what a verified run records,
//! that replay needs no planner call but keeps every check, and when it hands back.
mod common;
use ballistic_core::{run::Options, skills::Skill, types::*};
use common::*;
use serde_json::{json, Value};

const VERIFIED: &str = r#"{"status":"verified","evidence_basis":"visible_ui","nodes":["e1"]}"#;
const TYPE_OWNER: &str = r#"{"action":"type","target":"e1","text":"+15550100"}"#;
const TAP_DELETE: &str = r#"{"action":"tap","target":"e2"}"#;
const DONE: &str = r#"{"action":"done","evidence":"e1 shows it"}"#;

fn sms_screen() -> ScreenState { screen("example.sms", &["Some message"]) }
fn with_skills(skills: Vec<Skill>, judge: bool) -> Options { Options { skills, judge, ..Options::harness() } }

/// A verified run: type the owner's number, delete, done.
fn recorded() -> (Value, Skill) {
    let mut fake = Fake { screen: Some(sms_screen()), planner: vec![TYPE_OWNER.into(), TAP_DELETE.into(), DONE.into()],
        reader: vec![VERIFIED.into()], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "put +15550100 in To and delete", 5, &harness());
    let skill: Skill = serde_json::from_value(r["skill"].clone()).expect("a skill");
    (r, skill)
}

#[test]
fn a_verified_run_records_targets_by_identity() {
    let (r, skill) = recorded();
    assert_eq!(r["done"], true);
    assert_eq!(json!(skill), json!({"request":"put +15550100 in To and delete","steps":[
        {"package":"example.sms","activity":"Main","action":{"action":"type","target":{"role":"edit","resource_id":"example.sms:id/to","label":"To"},"text":"+15550100","submit":false}},
        {"package":"example.sms","activity":"Main","action":{"action":"tap","target":{"role":"button","resource_id":"example.sms:id/delete","label":"Delete"}}}]}));
    assert!(!r["skill"].to_string().contains("\"e1\"") && !r["skill"].to_string().contains("Some message"));
}

#[test]
fn replay_makes_no_planner_call_and_keeps_confirmation_and_the_done_check() {
    let (_, skill) = recorded();
    let mut fake = Fake { screen: Some(sms_screen()), reader: vec![VERIFIED.into()], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "Put +15550100 in To  and delete", 5, &with_skills(vec![skill.clone()], false));
    assert_eq!(fake.count(ModelRole::Planner), 0);
    assert_eq!(fake.asked.len(), 1, "Delete still needs the owner");
    assert_eq!(fake.acted.len(), 2);
    assert_eq!(fake.count(ModelRole::Verifier), 1, "done is still checked");
    assert_eq!(r["done"], true);
    assert_eq!(r["skill_replay"], json!({"steps":2,"stopped_at":null}));
    assert!(fake.events.iter().all(|e| e["why"] == "skill replay"));

    // Denied on replay: nothing deleted, and the judge still runs when it is on.
    let mut fake = Fake { screen: Some(sms_screen()), judge: vec![r#"{"verdict":"allow"}"#.into(), r#"{"verdict":"block","reason":"no"}"#.into()], ..Fake::default() };
    let r = drive(&mut fake, "put +15550100 in To and delete", 5, &with_skills(vec![skill], true));
    assert_eq!(fake.count(ModelRole::Judge), 2);
    assert_eq!(fake.acted.len(), 1, "typed, then blocked before Delete");
    assert_eq!(r["blocked_by"], "judge");
}

#[test]
fn a_changed_screen_hands_over_to_the_planner() {
    let (_, skill) = recorded();
    let mut changed = sms_screen();
    changed.nodes[1].label = Some("Archive".into());
    changed.nodes[1].resource_id = Some("example.sms:id/archive".into());
    let mut fake = Fake { screen: Some(changed), ..Fake::default() };
    let r = drive(&mut fake, "put +15550100 in To and delete", 5, &with_skills(vec![skill], false));
    assert_eq!(fake.acted.len(), 1, "the first step still matched");
    let planner = &fake.prompts(ModelRole::Planner)[0];
    assert!(planner.contains("skill replay stopped at step 2: screen or control differs; planning from here"), "{planner}");
    assert_eq!(r["skill_replay"], json!({"steps":1,"stopped_at":2}));
}

#[test]
fn only_an_exact_request_replays() {
    let (_, skill) = recorded();
    let mut fake = Fake { screen: Some(sms_screen()), ..Fake::default() };
    drive(&mut fake, "put +15550100 in To and delete it", 2, &with_skills(vec![skill], false));
    assert_eq!(fake.count(ModelRole::Planner), 1);
}

#[test]
fn content_choices_unverified_runs_and_model_values_are_not_recorded() {
    // A reader read in the run.
    let mut fake = Fake { screen: Some(sms_screen()), planner: vec![r#"{"action":"read","targets":["c1"],"question":"which?","shape":"element"}"#.into(), TAP_DELETE.into(), DONE.into()],
        reader: vec!["none".into(), VERIFIED.into()], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "delete it", 5, &harness());
    assert_eq!(r["done"], true);
    assert!(r["skill"].is_null());
    // Typed text with a value the owner did not give.
    let mut fake = Fake { screen: Some(sms_screen()), planner: vec![r#"{"action":"type","target":"e1","text":"+15550199"}"#.into(), DONE.into()],
        reader: vec![VERIFIED.into()], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "put my number in To", 5, &harness());
    assert_eq!(r["done"], true);
    assert!(r["skill"].is_null());
    // Not verified.
    let mut fake = Fake { screen: Some(sms_screen()), planner: vec![TYPE_OWNER.into(), DONE.into()],
        reader: vec![r#"{"status":"unknown","evidence_basis":"visible_ui","nodes":[]}"#.into()], ..Fake::default() };
    let r = drive(&mut fake, "put +15550100 in To", 2, &harness());
    assert!(r["skill"].is_null());
}

#[test]
fn skills_cross_in_options_json() {
    let (_, skill) = recorded();
    let o: Options = serde_json::from_value(json!({"judge":false,"skills":[skill.clone()]})).unwrap();
    assert_eq!(o.skills, vec![skill]);
    assert!(serde_json::from_value::<Options>(json!({"skills":[{"request":"x","steps":[{"package":"p","activity":"a","action":{"action":"tap","target":{"role":"button","resource_id":null,"label":"OK"},"x":1}}]}]})).is_err());
}
