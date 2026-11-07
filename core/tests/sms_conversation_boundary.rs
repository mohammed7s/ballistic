//! One additional post-implementation boundary probe requested during review.
//! Real core agent/bridge, scripted host replies only; no device or model service.
use ballistic_core::{agent, bridge::Bridge, screen_filter::{self, FilterContext}, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

#[test]
fn opening_conversation_does_not_authorize_the_subsequent_send() {
    let parse = |text: &str| -> ScreenState {
        serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
    };
    let entry = parse(include_str!("screens/known_controls/sms_new_conversation.json"));
    let composer = parse(include_str!("screens/benchmark_regressions/sms_before_send.json"));
    let request = "Open the conversation and send the draft.";
    let find = |s: &ScreenState, rid: &str| {
        screen_filter::filter(s, &FilterContext { request, typed: &[] }).elements.into_iter()
            .find(|e| e.resource_id.as_deref() == Some(rid)).unwrap()
    };
    let open = find(&entry, "com.simplemobiletools.smsmessenger:id/new_conversation_confirm");
    let send = find(&composer, "com.simplemobiletools.smsmessenger:id/thread_send_message");
    assert_eq!(open.label.as_deref(), Some("Open conversation"));
    assert_eq!(send.label.as_deref(), Some("Send message"));
    let tap = |id| json!({"text": json!({"action": "tap", "target": format!("e{id}")}).to_string()});
    let replies = [json!(entry), tap(open.id), json!({"result":"ok"}),
        json!(composer), tap(send.id), json!("denied")];
    let input = replies.into_iter().enumerate().map(|(i, data)|
        json!({"id": i + 1, "ok": true, "data": data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let result = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, 3).unwrap();
    let calls: Vec<Value> = String::from_utf8(output).unwrap().lines()
        .map(|line| serde_json::from_str(line).unwrap()).collect();
    let asks: Vec<_> = calls.iter().filter(|c| c["ask"] != "log").collect();
    assert_eq!(asks.iter().map(|c| c["ask"].as_str().unwrap()).collect::<Vec<_>>(),
        ["observe", "complete", "act", "observe", "complete", "confirm"]);
    let (x, y) = open.bounds.centre();
    assert_eq!(asks[2]["op"], json!({"op":"tap", "x":x, "y":y}));
    let (x, y) = send.bounds.centre();
    assert_eq!(asks[5]["request"]["operation"], json!({"op":"tap", "x":x, "y":y}));
    assert_eq!(asks[5]["request"]["app"], "com.simplemobiletools.smsmessenger");
    assert_eq!(asks[5]["request"]["hard"], true);
    assert_eq!(result["failure_category"], "policy_denial");
    assert_eq!(result["reason"], "owner denied confirmation");
    assert_eq!(result["done"], false);
}
