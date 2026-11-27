//! Loop 3 (16 Nov 2026): runs whose goal state passed in the loop 2 Haiku suite
//! (`ballistic_runs/suite_loop2_haiku_f14c4ca_20261115-204902/`) but which core did not recognize as
//! done. Screens are the recorded ones (`tests/screens/loop3/`, extracted by step); variants that
//! change one recorded field are marked as derived. Scripted ports only.
use ballistic_core::{actions, agent, bridge::Bridge, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

struct Recorded { request: String, steps: Vec<(Action, ScreenState)>, after: ScreenState }

fn load(text: &str) -> Recorded {
    let v: Value = serde_json::from_str(text).unwrap();
    let screen = |s: &Value| -> ScreenState { serde_json::from_value(s.clone()).unwrap() };
    Recorded {
        request: v["request"].as_str().unwrap().to_string(),
        steps: v["steps"].as_array().unwrap().iter().map(|s| (actions::parse_reply(&s["action"].to_string()).unwrap().action, screen(&s["screen"]))).collect(),
        after: screen(&v["after"]),
    }
}
fn completion(action: &Action) -> Value {
    let mut a = actions::to_json(action);
    a["why"] = json!("recorded");
    json!({"text": a.to_string()})
}
fn give_up() -> Value { json!({"text": json!({"action":"give_up","reason":"script ended"}).to_string()}) }
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn count(calls: &[Value], f: impl Fn(&Value) -> bool) -> usize { calls.iter().filter(|c| f(c)).count() }

// ---- SimpleSmsReply: "Reply to <number> with message: <body> in <SMS app>" ---------------------

fn sms() -> Recorded { load(include_str!("screens/loop3/sms_reply_number.json")) }

/// Open the thread, type, Send (confirmed; its readback does not show the new row yet), then the
/// recorded done claim, whose fresh observation is `after`.
fn sms_replies(r: &Recorded) -> Vec<Value> {
    let (open, typed, send, done) = (&r.steps[0], &r.steps[1], &r.steps[2], &r.steps[3]);
    vec![json!(open.1), completion(&open.0), json!({"result":"ok"}), json!(typed.1),
        completion(&typed.0), json!({"result":"ok"}), json!(send.1),
        completion(&send.0), json!("approved"), json!({"result":"ok"}), json!(done.1),
        completion(&done.0), json!(r.after)]
}

#[test]
fn recorded_reply_to_a_number_completes_on_the_done_readback() {
    // Recorded: open the thread, type the body, Send (confirmed, composer emptied); the new row
    // showed on the next observation, then six done claims the verifier would not accept and the
    // budget ran out. The request form was not one the sent-row check knew; it now is.
    let r = sms();
    let (report, calls) = run(&r.request, sms_replies(&r), 10);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_sent_row");
    assert_eq!(report["steps"], 4);
    assert_eq!(count(&calls, |c| c["ask"] == "confirm"), 1, "Send stays guarded");
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
}

#[test]
fn reply_to_a_number_is_not_done_in_another_thread() {
    // Derived: every thread screen's title shows another number. The typed check fails and the
    // verifier (asked as before for message goals) finds nothing.
    let mut r = sms();
    for s in r.steps[1..].iter_mut().map(|s| &mut s.1).chain([&mut r.after]) {
        let n = s.nodes.iter_mut().find(|n| n.text.as_deref() == Some("+16604876475")).unwrap();
        n.text = Some("+16604876476".into());
    }
    let mut replies = sms_replies(&r);
    replies.push(json!({"text": json!({"status":"unknown","nodes":[],"evidence_basis":"visible_ui","reason":""}).to_string()}));
    replies.push(give_up());
    let (report, _) = run(&r.request, replies, 10);
    assert_eq!(report["done"], false, "{report}");
}

// ---- ClockStopWatchPausedVerify / ClockStopWatchRunning: the run state on the app's own page ----

/// Replies for recorded inputs, each followed by core's typed-check observation (the next
/// recorded screen, then `after`).
fn inputs(steps: &[(Action, ScreenState)], after: &ScreenState) -> Vec<Value> {
    let mut replies = vec![json!(steps[0].1)];
    for (i, (action, _)) in steps.iter().enumerate() {
        replies.push(completion(action));
        replies.push(json!({"result":"ok"}));
        replies.push(json!(steps.get(i + 1).map_or(after, |s| &s.1)));
    }
    replies
}

#[test]
fn recorded_paused_stopwatch_is_done_when_its_page_shows_start_without_pause() {
    // Recorded: Clock, then the Stopwatch tab showing Start and no Pause (state passed); the
    // verifier would not accept "not running" and the planner gave up.
    let r = load(include_str!("screens/loop3/stopwatch_paused.json"));
    let (report, calls) = run(&r.request, inputs(&r.steps, &r.after), 6);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_run_state");
    assert_eq!(report["steps"], 2);
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
}

#[test]
fn recorded_running_stopwatch_is_done_after_start() {
    let r = load(include_str!("screens/loop3/stopwatch_running.json"));
    let (report, _) = run(&r.request, inputs(&r.steps[..3], &r.after), 6);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_run_state");
    assert_eq!(report["steps"], 3);
}

#[test]
fn run_state_needs_the_named_selected_page_and_only_the_opposite_control() {
    use ballistic_core::postcondition::{self, Goal};
    let paused = load(include_str!("screens/loop3/stopwatch_paused.json"));
    let running = load(include_str!("screens/loop3/stopwatch_running.json"));
    let goal = |r: &str| postcondition::recognize(r, &[]).unwrap();
    assert_eq!(goal("Pause the stopwatch."), Goal::RunState { noun: "stopwatch".into(), running: false });
    assert_eq!(goal("Run the stopwatch."), Goal::RunState { noun: "stopwatch".into(), running: true });
    for other in ["pause the stopwatch and reset it", "stop the alarm at 7", "pause"] {
        assert!(!matches!(postcondition::recognize(other, &[]), Some(Goal::RunState { .. })), "{other}");
    }
    let check = |g: &Goal, s: &ScreenState| postcondition::assess(g, &[], s, s, 0, &[]).verification.status;
    // Paused is not running, and the other way round.
    assert_eq!(check(&goal("Pause the stopwatch."), &paused.after), VerificationStatus::Verified);
    assert_eq!(check(&goal("Pause the stopwatch."), &running.after), VerificationStatus::Unknown);
    assert_eq!(check(&goal("Run the stopwatch."), &running.after), VerificationStatus::Verified);
    assert_eq!(check(&goal("Run the stopwatch."), &paused.after), VerificationStatus::Unknown);
    // The Clock tab (not the named page) is not evidence about the stopwatch.
    assert_eq!(check(&goal("Pause the stopwatch."), &paused.steps[1].1), VerificationStatus::Unknown);
    assert_eq!(check(&goal("Pause the timer."), &paused.after), VerificationStatus::Unknown);
}

// ---- SystemBrightnessMax: the dialog's slider shows no value ------------------------------------

#[test]
fn brightness_dialog_feedback_says_to_go_back_to_the_display_page() {
    use ballistic_core::postcondition;
    let text = include_str!("screens/postconditions/brightness_dialog.json");
    let dialog: ScreenState = serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap();
    let goal = postcondition::recognize("Turn brightness to the max value.", &[]).unwrap();
    let a = postcondition::assess(&goal, &[], &dialog, &dialog, 0, &[]);
    assert_eq!(a.verification.status, VerificationStatus::Unknown);
    assert!(a.feedback.contains("press back"), "{}", a.feedback);
}
