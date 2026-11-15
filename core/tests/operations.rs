//! Typed operations (intents), core/src/operations.rs: strict argument checks, host-advertised
//! routing, requested-app preservation, the taint rule and confirmations, no auto-submit, and the
//! hand-off to the ordinary screen loop and typed postconditions. Scripted ports only.
mod common;
use ballistic_core::{agent, bridge::Bridge, capabilities, operations::{self, IntentGrant, IntentOp}, types::*};
use common::*;
use serde_json::{json, Map, Value};
use std::io::Cursor;

const SMS: &str = "com.simplemobiletools.smsmessenger";
const GOOGLE_SMS: &str = "com.google.android.apps.messaging";
const CLOCK: &str = "com.google.android.deskclock";
const SIMPLE_CAL: &str = "com.simplemobiletools.calendar.pro";
const GOOGLE_CAL: &str = "com.google.android.calendar";

fn args(v: Value) -> Map<String, Value> { v.as_object().unwrap().clone() }
fn err(op: &str, v: Value) -> String { operations::parse(op, &args(v)).unwrap_err().to_string() }

/// A screen in `package` whose host advertises `grants` (operation, package) with app grants
/// for every package named, and the installed apps listed.
fn host_screen(package: &str, grants: &[(&str, &str)]) -> ScreenState {
    let mut s = screen(package, &[]);
    let caps = s.capabilities.as_mut().unwrap();
    caps.intents = grants.iter().map(|(o, p)| IntentGrant { operation: o.to_string(), package: p.to_string() }).collect();
    for (_, p) in grants { if !caps.app_grants.iter().any(|a| a == p) { caps.app_grants.push(p.to_string()); } }
    s.installed_apps = vec![
        AppEntry { label: "Simple SMS Messenger".into(), package: SMS.into(), activity: None },
        AppEntry { label: "Messages".into(), package: GOOGLE_SMS.into(), activity: None },
        AppEntry { label: "Clock".into(), package: CLOCK.into(), activity: None },
        AppEntry { label: "Simple Calendar Pro".into(), package: SIMPLE_CAL.into(), activity: None },
        AppEntry { label: "Google Calendar".into(), package: GOOGLE_CAL.into(), activity: None },
        AppEntry { label: "Settings".into(), package: "com.android.settings".into(), activity: None },
    ];
    s
}

fn intents_acted(fake: &Fake) -> Vec<(String, IntentOp)> {
    fake.acted.iter().filter_map(|op| match op { HandsOp::Intent { package, intent } => Some((package.clone(), intent.clone())), _ => None }).collect()
}
fn planner_corrections(fake: &Fake) -> Vec<String> {
    // Each call repeats the step's earlier corrections; its newest one is its last message.
    fake.calls.iter().filter(|(r, m)| *r == ModelRole::Planner && m.len() > 2).filter_map(|(_, m)| m.last().map(|c| c.content.clone())).collect()
}

// ---- argument validation --------------------------------------------------------------------

#[test]
fn arguments_are_checked_strictly() {
    assert!(err("set_timer", json!({"seconds":0})).contains("between 1 and 86400"));
    assert!(err("set_timer", json!({"seconds":86401})).contains("between 1 and 86400"));
    assert!(err("set_timer", json!({"seconds":"300"})).contains("whole number"));
    assert!(err("set_timer", json!({"seconds":30.5})).contains("whole number"));
    assert!(err("set_timer", json!({"seconds":60,"skip_ui":true})).contains("no argument \"skip_ui\""), "no skip-UI or submit argument exists");
    assert!(err("set_alarm", json!({"hour":24,"minute":0})).contains("between 0 and 23"));
    assert!(err("set_alarm", json!({"hour":7})).contains("needs \"minute\""));
    assert!(err("set_alarm", json!({"hour":7,"minute":0,"days":["monday","monday"]})).contains("repeat"));
    assert!(err("set_alarm", json!({"hour":7,"minute":0,"days":["mon"]})).contains("days must be a list"));
    assert!(err("create_event", json!({"title":"x","begin":"2023-02-29T10:00","duration_minutes":30})).contains("YYYY-MM-DDTHH:MM"));
    assert!(err("create_event", json!({"title":"x","begin":"2023-10-23 10:00","duration_minutes":30})).contains("YYYY-MM-DDTHH:MM"));
    assert!(err("create_event", json!({"title":"x","begin":"2023-10-23T10:00"})).contains("end or duration_minutes"));
    assert!(err("create_event", json!({"title":"x","begin":"2023-10-23T10:00","end":"2023-10-23T11:00","duration_minutes":60})).contains("not both"));
    assert!(err("create_event", json!({"title":"x","begin":"2023-10-23T10:00","end":"2023-10-23T09:00"})).contains("after begin"));
    assert!(err("create_event", json!({"title":"  ","begin":"2023-10-23T10:00","duration_minutes":5})).contains("must not be empty"));
    assert!(err("create_event", json!({"title":"a\u{202E}b","begin":"2023-10-23T10:00","duration_minutes":5})).contains("control characters"));
    assert!(err("compose_sms", json!({"to":"*#06#","body":"hi"})).contains("phone number"), "service codes are not numbers");
    assert!(err("compose_sms", json!({"to":"12","body":"hi"})).contains("3 to 15 digits"));
    assert!(err("compose_sms", json!({"to":"Ana","body":"hi"})).contains("phone number"));
    assert!(err("dial", json!({"number":"555;0100"})).contains("phone number"));
    assert!(err("add_contact", json!({"name":"Ana","email":"ana at example"})).contains("email"));
    assert!(err("open_settings", json!({"page":"developer_options"})).contains("must be one of"));
    assert!(err("open_camera", json!({"mode":"burst"})).contains("photo, video"));
    for bad in ["http://example.com", "javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "content://x",
        "https://bank.example@evil.example/", "https:// example.com", "https://", "geo:91,0", "geo:abc,1", "tel:5550100", "sms:5550100"] {
        assert!(err("view_uri", json!({"uri":bad})).contains("https:// link or geo"), "{bad}");
    }
    assert!(err("frobnicate", json!({})).contains("unknown operation"));
}

#[test]
fn valid_arguments_are_normalized() {
    let op = operations::parse("create_event", &args(json!({"title":"Review","begin":"2023-10-31T23:30","duration_minutes":90,"location":null}))).unwrap();
    assert_eq!(op, IntentOp::CreateEvent { title: "Review".into(), begin: "2023-10-31T23:30".into(), end: "2023-11-01T01:00".into(), location: None, description: None });
    assert_eq!(operations::parse("compose_sms", &args(json!({"to":"+1 (660) 487-6475","body":" hi "}))).unwrap(),
        IntentOp::ComposeSms { to: "+16604876475".into(), body: "hi".into() });
    assert_eq!(operations::parse("view_uri", &args(json!({"uri":"HTTPS://example.com/a?b=1"}))).unwrap(), IntentOp::ViewUri { uri: "https://example.com/a?b=1".into() });
    assert_eq!(operations::parse("view_uri", &args(json!({"uri":"geo:51.5,-0.12"}))).unwrap(), IntentOp::ViewUri { uri: "geo:51.5,-0.12".into() });
}

#[test]
fn the_hands_operation_carries_no_submit_or_skip_ui_field() {
    // Every operation's wire form: the op tag, the package, the typed intent. Nothing a host
    // could read as "send now" or "skip the UI".
    let samples = [("set_timer", json!({"seconds":60})), ("set_alarm", json!({"hour":7,"minute":0})),
        ("create_event", json!({"title":"t","begin":"2023-10-23T06:00","duration_minutes":60})),
        ("compose_sms", json!({"to":"5550100","body":"hi"})), ("add_contact", json!({"name":"Ana","phone":"5550100"})),
        ("open_settings", json!({"page":"wifi"})), ("open_camera", json!({"mode":"video"})), ("dial", json!({"number":"5550100"})),
        ("view_uri", json!({"uri":"https://example.com"}))];
    for (id, a) in samples {
        let op = HandsOp::Intent { package: "p".into(), intent: operations::parse(id, &args(a)).unwrap() };
        let text = serde_json::to_string(&op).unwrap();
        for word in ["submit", "skip", "send_now", "auto"] { assert!(!text.contains(word), "{id}: {text}"); }
        assert_eq!(serde_json::from_str::<HandsOp>(&text).unwrap(), op, "round trip");
        assert_eq!(capabilities::operation_id(&op), "intent");
    }
    assert!(serde_json::from_str::<HandsOp>(r#"{"op":"intent","package":"p","intent":{"operation":"set_timer","seconds":5,"label":null,"skip_ui":true}}"#).is_err(),
        "hosts and core reject unknown intent fields");
}

// ---- advertisement and routing --------------------------------------------------------------

#[test]
fn without_advertised_operations_the_prompt_has_no_intent_and_intents_are_refused() {
    let mut fake = Fake { screen: Some(screen(SMS, &[])), planner: vec![
        r#"{"action":"intent","operation":"set_timer","args":{"seconds":60}}"#.into(); 3], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "set a timer for 60 seconds", 2, &harness());
    let first = &fake.prompts(ModelRole::Planner)[0];
    assert!(!first.contains("\\\"intent\\\"") && !first.contains("Typed operations"), "no intent schema or list without advertisement");
    assert!(fake.acted.is_empty() && fake.asked.is_empty());
    assert!(planner_corrections(&fake)[0].contains("advertises no typed operations"));
    assert_eq!(r["failure_category"], "planning");
}

#[test]
fn the_prompt_lists_only_advertised_operations_with_their_apps() {
    let mut fake = Fake { screen: Some(host_screen(SMS, &[("compose_sms", SMS), ("open_settings", "com.android.settings")])), ..Fake::default() };
    drive(&mut fake, "hello", 1, &harness());
    let prompt = &fake.prompts(ModelRole::Planner)[0];
    assert!(prompt.contains("compose_sms{to:phone number, body}") && prompt.contains("Apps: Simple SMS Messenger."));
    assert!(prompt.contains("open_settings{page:"));
    for absent in ["set_timer{", "create_event{", "add_contact{", "dial{", "view_uri{", "open_camera{"] { assert!(!prompt.contains(absent), "{absent}"); }
}

#[test]
fn an_unadvertised_operation_or_an_ungranted_package_is_refused() {
    let mut s = host_screen(SMS, &[("compose_sms", SMS)]);
    s.capabilities.as_mut().unwrap().app_grants.retain(|p| p != SMS);
    let mut fake = Fake { screen: Some(s), planner: vec![
        r#"{"action":"intent","operation":"compose_sms","args":{"to":"5550100","body":"hi"}}"#.into(); 3], approve: true, ..Fake::default() };
    drive(&mut fake, "text 5550100 hi", 1, &harness());
    assert!(fake.acted.is_empty(), "a handler without an app grant authorizes nothing");
    assert!(planner_corrections(&fake)[0].contains("not available"));
    // Direct check: an intent must name the advertised package, and a changed package is denied.
    let caps = HostCapabilities { operations: vec![], app_grants: vec![SMS.into(), GOOGLE_SMS.into()], tool_grants: vec![], observations: vec![],
        intents: vec![IntentGrant { operation: "compose_sms".into(), package: SMS.into() }] };
    let op = |p: &str| HandsOp::Intent { package: p.into(), intent: IntentOp::ComposeSms { to: "5550100".into(), body: "hi".into() } };
    assert!(capabilities::permitted(&op(SMS), SMS, Some(&caps)));
    assert!(!capabilities::permitted(&op(GOOGLE_SMS), GOOGLE_SMS, Some(&caps)));
    assert!(!capabilities::permitted(&op(SMS), GOOGLE_SMS, Some(&caps)));
    let dial = HandsOp::Intent { package: SMS.into(), intent: IntentOp::Dial { number: "5550100".into() } };
    assert!(!capabilities::permitted(&dial, SMS, Some(&caps)), "the grant is per operation");
}

#[test]
fn the_app_the_owner_named_keeps_the_operation() {
    let request = "Send a text message using Simple SMS Messenger to +15550100 with message: hi";
    let grants = [("compose_sms", GOOGLE_SMS), ("compose_sms", SMS)];
    let to_google = r#"{"action":"intent","operation":"compose_sms","app":"Messages","args":{"to":"+15550100","body":"hi"}}"#;
    let unnamed = r#"{"action":"intent","operation":"compose_sms","args":{"to":"+15550100","body":"hi"}}"#;
    let to_simple = r#"{"action":"intent","operation":"compose_sms","app":"Simple SMS Messenger","args":{"to":"+15550100","body":"hi"}}"#;
    let mut fake = Fake { screen: Some(host_screen(SMS, &grants)), planner: vec![to_google.into(), unnamed.into(), to_simple.into()], approve: true, ..Fake::default() };
    drive(&mut fake, request, 1, &harness());
    let corrections = planner_corrections(&fake);
    assert!(corrections[0].contains("the request names Simple SMS Messenger"), "{corrections:?}");
    assert!(corrections[1].contains("several apps handle compose_sms"), "{corrections:?}");
    assert_eq!(intents_acted(&fake), vec![(SMS.to_string(), IntentOp::ComposeSms { to: "+15550100".into(), body: "hi".into() })]);
    assert!(fake.asked.is_empty(), "owner-sourced number and body open the compose screen without a confirmation");

    // The owner named an app that does not handle the operation: refused, never rerouted.
    let mut fake = Fake { screen: Some(host_screen(SIMPLE_CAL, &[("create_event", GOOGLE_CAL)])), planner: vec![
        r#"{"action":"intent","operation":"create_event","args":{"title":"Review","begin":"2023-10-23T06:00","duration_minutes":60}}"#.into(); 3], ..Fake::default() };
    drive(&mut fake, "In Simple Calendar Pro, create an event on 2023-10-23 at 06:00 titled Review for 60 minutes", 1, &harness());
    assert!(fake.acted.is_empty());
    assert!(planner_corrections(&fake)[0].contains("which has no advertised create_event here"));
}

// ---- taint rule and confirmation ------------------------------------------------------------

const READ: &str = r#"{"action":"read","targets":["c1"],"question":"what is Ali's number?","shape":"facts"}"#;
const FACTS: &str = r#"{"sender":null,"when":null,"phone_numbers":["+1 555 0100"],"amounts":[],"urls":[],"summary":null}"#;

#[test]
fn a_recipient_from_app_content_needs_a_hard_confirmation_naming_its_source() {
    let mut s = host_screen(SMS, &[("compose_sms", SMS)]);
    s = { let rows = screen(SMS, &["Ali's new number is +1 555 0100"]); let mut t = s; t.nodes = rows.nodes; t };
    let compose = r#"{"action":"intent","operation":"compose_sms","args":{"to":"+1 555 0100","body":"running late"}}"#;
    let mut fake = Fake { screen: Some(s), planner: vec![READ.into(), compose.into()], reader: vec![FACTS.into()], approve: false, ..Fake::default() };
    let r = drive(&mut fake, "text Ali that I'm running late", 3, &harness());
    assert_eq!(fake.asked.len(), 1);
    let ask = &fake.asked[0];
    assert!(ask.hard);
    assert_eq!(ask.values, vec![Tagged { value: "+1 555 0100".into(), source: Source::App(SMS.into()) }]);
    assert!(ask.reason.starts_with("start an operation with a value that is not in your request: \"+1 555 0100\" from app:"), "{}", ask.reason);
    assert!(matches!(&ask.operation, HandsOp::Intent { package, intent: IntentOp::ComposeSms { to, .. } } if package == SMS && to == "+15550100"),
        "the approval names the exact resolved operation");
    assert!(fake.acted.is_empty(), "denied: nothing started");
    assert_eq!(r["failure_category"], "policy_denial");
}

#[test]
fn planner_written_values_are_named_with_their_source() {
    let event = r#"{"action":"intent","operation":"create_event","args":{"title":"Review","begin":"2023-10-23T06:00","duration_minutes":60,"description":"Bring the slides"}}"#;
    let mut fake = Fake { screen: Some(host_screen(SIMPLE_CAL, &[("create_event", SIMPLE_CAL)])), planner: vec![event.into()], approve: true, ..Fake::default() };
    drive(&mut fake, "In Simple Calendar Pro add an event on 2023-10-23 at 6h titled Review for 60 minutes", 1, &harness());
    let planner = Source::Model("planner".into());
    assert_eq!(fake.asked.len(), 1);
    assert_eq!(fake.asked[0].values, vec![Tagged { value: "2023-10-23T06:00".into(), source: planner.clone() },
        Tagged { value: "Bring the slides".into(), source: planner }], "the computed time and an invented description; title and duration are the owner's");
    assert_eq!(intents_acted(&fake).len(), 1, "approved: the editor opens");

    // Exact date and time in the request: no confirmation for an editor.
    let mut fake = Fake { screen: Some(host_screen(SIMPLE_CAL, &[("create_event", SIMPLE_CAL)])), planner: vec![event.replace(r#","description":"Bring the slides""#, "")], ..Fake::default() };
    drive(&mut fake, "In Simple Calendar Pro add an event on 2023-10-23 at 06:00 titled Review for 60 minutes", 1, &harness());
    assert!(fake.asked.is_empty());
    assert_eq!(intents_acted(&fake).len(), 1);
}

#[test]
fn timers_and_alarms_always_need_the_owners_yes_and_are_not_repeated() {
    let timer = r#"{"action":"intent","operation":"set_timer","args":{"seconds":300}}"#;
    let mut fake = Fake { screen: Some(host_screen(CLOCK, &[("set_timer", CLOCK)])), planner: vec![timer.into(); 4], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "set a timer for 300 seconds", 4, &harness());
    assert_eq!(fake.asked.len(), 2, "each start asks; the second names the earlier acknowledgment");
    assert!(fake.asked[0].reason.contains("no separate save step") && fake.asked[0].values.is_empty(), "{}", fake.asked[0].reason);
    assert!(fake.asked[1].reason.contains("already acknowledged"));
    assert_eq!(intents_acted(&fake).len(), 2, "a third identical start is never dispatched");
    assert_eq!(r["done"], false);

    let mut fake = Fake { screen: Some(host_screen(CLOCK, &[("set_timer", CLOCK)])), planner: vec![timer.into()], approve: false, ..Fake::default() };
    drive(&mut fake, "set a timer for five minutes", 2, &harness());
    assert_eq!(fake.asked[0].values, vec![Tagged { value: "300".into(), source: Source::Model("planner".into()) }], "a converted value is shown as the planner's");
    assert!(fake.acted.is_empty());
}

#[test]
fn the_judge_sees_intents_and_their_value_sources() {
    let compose = r#"{"action":"intent","operation":"compose_sms","args":{"to":"5550100","body":"hi there"}}"#;
    let mut fake = Fake { screen: Some(host_screen(SMS, &[("compose_sms", SMS)])), planner: vec![compose.into()],
        judge: vec![r#"{"verdict":"block","reason":"not requested"}"#.into()], approve: true, ..Fake::default() };
    let r = drive(&mut fake, "text 5550100 hello", 1, &with_judge());
    let judge = &fake.prompts(ModelRole::Judge)[0];
    assert!(judge.contains("intent compose_sms") && judge.contains("\\\"hi there\\\" from model:planner"), "{judge}");
    assert!(fake.acted.is_empty());
    assert_eq!(r["blocked_by"], "judge");
}

#[test]
fn an_advertised_alarm_intent_removes_the_early_refusal_only_for_creating() {
    let caps = |intents: Vec<IntentGrant>| HostCapabilities { operations: vec!["open_app".into(), "wait".into()], app_grants: vec![CLOCK.into()],
        tool_grants: vec![], observations: vec![], intents };
    let with = caps(vec![IntentGrant { operation: "set_alarm".into(), package: CLOCK.into() }]);
    assert!(ballistic_core::capability_gaps::gap("Set an alarm for 7am", Some(&caps(vec![]))).is_some());
    assert!(ballistic_core::capability_gaps::gap("Set an alarm for 7am", Some(&with)).is_none());
    assert!(ballistic_core::capability_gaps::gap("Delete my 7am alarm", Some(&with)).is_some(), "deleting still needs the screens");
    assert_eq!(ballistic_core::capability_gaps::gap("Set an alarm for 7am and turn on airplane mode", Some(&with)).unwrap().needs,
        "taps in Settings or Quick Settings", "the other checks still apply to a compound request");
}

// ---- execution status and the hand-off to the screen loop ----------------------------------

fn completion(action: Value) -> Value { json!({"text": action.to_string()}) }
fn bridge_run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn fixture(name: &str) -> ScreenState {
    let text = match name {
        "send_before" => include_str!("screens/postconditions/sms_send_before.json"),
        "send_after" => include_str!("screens/postconditions/sms_send_after.json"),
        _ => panic!("{name}"),
    };
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}
fn with_sms_intent(mut s: ScreenState) -> ScreenState {
    s.capabilities.as_mut().unwrap().intents = vec![IntentGrant { operation: "compose_sms".into(), package: SMS.into() }];
    s
}

#[test]
fn a_host_that_did_not_start_the_intent_leaves_it_not_executed_and_an_error_stops() {
    let open = json!({"action":"intent","operation":"open_settings","args":{"page":"wifi"}});
    let mut s = host_screen(SMS, &[("open_settings", "com.android.settings")]);
    s.installed_apps.truncate(1);
    let (r, calls) = bridge_run("open the wifi settings", vec![json!(s.clone()), completion(open.clone()),
        json!({"result":"not_executed","message":"no app handles this"})], 1);
    assert_eq!(r["execution_status"], "not_executed");
    assert_eq!(calls.iter().filter(|c| c["ask"] == "act").count(), 1);
    let (r, _) = bridge_run("open the wifi settings", vec![json!(s), completion(open), json!({"result":"error","message":"adb lost"})], 3);
    assert_eq!((r["execution_status"].as_str(), r["failure_category"].as_str()), (Some("unknown"), Some("execution")));
    assert_eq!(r["steps"], 1, "an unknown effect is never retried");
}

#[test]
fn a_prefilled_compose_screen_still_needs_the_guarded_send_tap_and_the_typed_check_decides() {
    // compose_sms opens the recorded thread with the body already in the composer. The intent
    // itself is not a send: core then plans on that screen, the Send tap is confirmed as usual,
    // and the existing sent-row postcondition (postcondition.rs) checks the result in code.
    let request = "Send a text message using Simple SMS Messenger to +16604876475 with message: The squeaky wheel gets the grease.";
    let before = with_sms_intent(fixture("send_before"));
    let compose = json!({"action":"intent","operation":"compose_sms","args":{"to":"+16604876475","body":"The squeaky wheel gets the grease."}});
    let view = ballistic_core::screen_filter::filter(&before, &ballistic_core::screen_filter::FilterContext { request, typed: &[] });
    let send_node = before.nodes.iter().find(|n| n.resource_id.as_deref() == Some("com.simplemobiletools.smsmessenger:id/thread_send_message")).unwrap().id;
    let send = format!("e{}", view.elements.iter().find(|e| e.node == send_node).unwrap().id);
    let (r, calls) = bridge_run(request, vec![json!(before.clone()), completion(compose), json!({"result":"ok"}),
        json!(before), completion(json!({"action":"tap","target":send})), json!("approved"), json!({"result":"ok"}),
        json!(fixture("send_after"))], 6);
    let acts: Vec<&Value> = calls.iter().filter(|c| c["ask"] == "act").collect();
    assert_eq!(acts[0]["op"]["op"], "intent");
    assert_eq!(acts[0]["op"]["intent"], json!({"operation":"compose_sms","to":"+16604876475","body":"The squeaky wheel gets the grease."}));
    let confirms: Vec<&Value> = calls.iter().filter(|c| c["ask"] == "confirm").collect();
    assert_eq!(confirms.len(), 1, "only the Send tap asks");
    assert_eq!(confirms[0]["request"]["operation"]["op"], "tap");
    let first = calls.iter().find(|c| c["ask"] == "log" && c["event"]["step"] == 1).unwrap();
    assert_eq!(first["event"]["task_status"], "opened_ui", "an opened compose screen is not a sent message");
    assert!(first["event"]["outcome"].as_str().unwrap().contains("nothing is sent until its Send control is tapped"));
    assert_eq!(r["done"], true, "{r}");
    assert_eq!(r["verification"]["evidence_basis"], "code_validated_sent_row");
}

// ---- wire forms shared with the Kotlin mirror (JsonCompatibilityTest.intents) ---------------

#[test]
fn wire_forms_match_the_kotlin_mirror() {
    let cases: Vec<(HandsOp, &str)> = vec![
        (HandsOp::Intent { package: "com.google.android.deskclock".into(), intent: IntentOp::SetTimer { seconds: 300, label: None } },
            r#"{"op":"intent","package":"com.google.android.deskclock","intent":{"operation":"set_timer","seconds":300,"label":null}}"#),
        (HandsOp::Intent { package: "c".into(), intent: IntentOp::SetAlarm { hour: 7, minute: 30, label: Some("Gym".into()), days: vec![operations::Weekday::Monday] } },
            r#"{"op":"intent","package":"c","intent":{"operation":"set_alarm","hour":7,"minute":30,"label":"Gym","days":["monday"]}}"#),
        (HandsOp::Intent { package: "c".into(), intent: IntentOp::CreateEvent { title: "Review".into(), begin: "2023-10-23T06:00".into(),
            end: "2023-10-23T07:00".into(), location: None, description: Some("D".into()) } },
            r#"{"op":"intent","package":"c","intent":{"operation":"create_event","title":"Review","begin":"2023-10-23T06:00","end":"2023-10-23T07:00","location":null,"description":"D"}}"#),
        (HandsOp::Intent { package: "s".into(), intent: IntentOp::ComposeSms { to: "+15550100".into(), body: "hi".into() } },
            r#"{"op":"intent","package":"s","intent":{"operation":"compose_sms","to":"+15550100","body":"hi"}}"#),
        (HandsOp::Intent { package: "k".into(), intent: IntentOp::AddContact { name: "Ana".into(), phone: Some("5550100".into()), email: None } },
            r#"{"op":"intent","package":"k","intent":{"operation":"add_contact","name":"Ana","phone":"5550100","email":null}}"#),
        (HandsOp::Intent { package: "com.android.settings".into(), intent: IntentOp::OpenSettings { page: operations::SettingsPage::InternetPanel } },
            r#"{"op":"intent","package":"com.android.settings","intent":{"operation":"open_settings","page":"internet_panel"}}"#),
        (HandsOp::Intent { package: "m".into(), intent: IntentOp::OpenCamera { mode: operations::CameraMode::Video } },
            r#"{"op":"intent","package":"m","intent":{"operation":"open_camera","mode":"video"}}"#),
        (HandsOp::Intent { package: "d".into(), intent: IntentOp::Dial { number: "5550100".into() } },
            r#"{"op":"intent","package":"d","intent":{"operation":"dial","number":"5550100"}}"#),
        (HandsOp::Intent { package: "b".into(), intent: IntentOp::ViewUri { uri: "https://example.com".into() } },
            r#"{"op":"intent","package":"b","intent":{"operation":"view_uri","uri":"https://example.com"}}"#),
    ];
    for (op, text) in cases {
        assert_eq!(serde_json::to_string(&op).unwrap(), text);
        assert_eq!(serde_json::from_str::<HandsOp>(text).unwrap(), op);
    }
    let action = Action::Intent { operation: "set_timer".into(), app: None, args: args(json!({"seconds":300})) };
    assert_eq!(serde_json::to_string(&action).unwrap(), r#"{"action":"intent","operation":"set_timer","args":{"seconds":300}}"#);
    // The Kotlin host's encoding (no tool_grants field) decodes here.
    let caps: HostCapabilities = serde_json::from_str(r#"{"operations":["open_app","wait"],"app_grants":["c"],"intents":[{"operation":"set_alarm","package":"c"}]}"#).unwrap();
    assert_eq!(caps.intents, vec![IntentGrant { operation: "set_alarm".into(), package: "c".into() }]);
}
