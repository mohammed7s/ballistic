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
