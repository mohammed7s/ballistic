//! Typed postconditions (postcondition.rs) on screens recorded in the Haiku 2 suite
//! (`ballistic_runs/suite_haiku55_20261108-194804/`, 8 Nov 2026). In that run each of these four
//! tasks reached its goal state (the benchmark checker passed) but core rejected the planner's
//! done claims or ran out of budget after the effect. Variants that change one recorded field
//! are marked as derived. Scripted ports only; no model, emulator or benchmark database.
use ballistic_core::{agent, bridge::Bridge, postcondition::{self, Effect, Goal, Recipient}, readback,
    screen_filter::{self, FilterContext}, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

fn fixture(name: &str) -> ScreenState {
    let text = match name {
        "launcher" => include_str!("screens/postconditions/openapp_launcher.json"),
        "settings" => include_str!("screens/postconditions/openapp_settings.json"),
        "display" => include_str!("screens/postconditions/brightness_display.json"),
        "dialog" => include_str!("screens/postconditions/brightness_dialog.json"),
        "send_before" => include_str!("screens/postconditions/sms_send_before.json"),
        "send_after" => include_str!("screens/postconditions/sms_send_after.json"),
        "reply_list" => include_str!("screens/postconditions/sms_reply_list.json"),
        "reply_before" => include_str!("screens/postconditions/sms_reply_before.json"),
        "reply_after" => include_str!("screens/postconditions/sms_reply_after.json"),
        "camera_before" => include_str!("screens/postconditions/camera_before.json"),
        "camera_after" => include_str!("screens/postconditions/camera_after.json"),
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}

const OPEN_SETTINGS: &str = "Open the settings app. Clear any pop-ups that may appear by granting all permissions that are required.";
const BRIGHTNESS: &str = "Turn brightness to the max value.";
const SEND: &str = "Send a text message using Simple SMS Messenger to +16604876475 with message: The squeaky wheel gets the grease.";
const REPLY: &str = "Reply to the most recent text message using Simple SMS Messenger with message: The squeaky wheel gets the grease.";
const PHOTO: &str = "Take one photo.";

fn completion(action: Value) -> Value { json!({"text": action.to_string()}) }
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn count(calls: &[Value], f: impl Fn(&Value) -> bool) -> usize { calls.iter().filter(|c| f(c)).count() }
fn planner_calls(calls: &[Value]) -> usize { count(calls, |c| c["role"] == "planner") }
fn verifier_calls(calls: &[Value]) -> usize { count(calls, |c| c["role"] == "verifier") }
fn node_by_rid(s: &ScreenState, rid: &str) -> NodeId {
    s.nodes.iter().find(|n| n.resource_id.as_deref() == Some(rid)).unwrap_or_else(|| panic!("{rid}")).id
}
fn element_for(s: &ScreenState, request: &str, node: NodeId) -> String {
    let view = screen_filter::filter(s, &FilterContext { request, typed: &[] });
    format!("e{}", view.elements.iter().find(|e| e.node == node).unwrap().id)
}
fn apps(s: &ScreenState) -> Vec<AppEntry> { s.installed_apps.clone() }

// ---- OpenAppTaskEval -------------------------------------------------------------------------

#[test]
fn launch_with_the_pop_up_clause_is_recognized_only_in_its_exact_form() {
    let installed = apps(&fixture("launcher"));
    assert!(matches!(postcondition::recognize(OPEN_SETTINGS, &installed),
        Some(Goal::Launch { popups: true, ref launch_request, .. }) if launch_request == "Open the settings app."));
    for other in ["Open the settings app. Delete all my data.",
        "Open the settings app. Clear any pop-ups that may appear by granting all permissions that are required. Then turn off Wi-Fi.",
        "Open the settings app and clear any pop-ups that may appear by granting all permissions that are required.",
        "Clear any pop-ups that may appear by granting all permissions that are required. Open the settings app."] {
        assert!(!matches!(postcondition::recognize(other, &installed), Some(Goal::Launch { .. })), "{other}");
    }
}

#[test]
fn recorded_open_settings_completes_on_the_readback_after_the_launch() {
    // Recorded: Settings was open after step 1, then nine done claims were rejected because the
    // verifier cited an unreadable list. Core now checks the foreground itself.
    let (report, calls) = run(OPEN_SETTINGS, vec![json!(fixture("launcher")),
        completion(json!({"action":"open_app","app":"settings"})), json!({"result":"ok"}), json!(fixture("settings"))], 10);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["steps"], 1);
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_host_foreground");
    assert_eq!(report["verification"]["foreground"]["package"], "com.android.settings");
    assert_eq!((planner_calls(&calls), verifier_calls(&calls)), (1, 0));
}

#[test]
fn a_permission_prompt_over_the_app_is_not_completion() {
    let mut prompt = fixture("settings");
    let mut button = prompt.nodes.last().unwrap().clone();
    button.id = 999; button.parent = None; button.package = Some("com.google.android.permissioncontroller".into());
    button.resource_id = Some("com.android.permissioncontroller:id/permission_allow_button".into());
    prompt.nodes.push(button);
    let installed = apps(&prompt);
    let goal = postcondition::recognize(OPEN_SETTINGS, &installed).unwrap();
    let check = postcondition::assess(&goal, &[], &fixture("launcher"), &prompt, 1, &installed);
    assert_eq!(check.verification.status, VerificationStatus::Unknown);
    // The planner keeps going; a done claim on the same screen is not accepted and the run is
    // not stopped (the clause asks the planner to clear the pop-up).
    let (report, calls) = run(OPEN_SETTINGS, vec![json!(fixture("launcher")),
        completion(json!({"action":"open_app","app":"settings"})), json!({"result":"ok"}), json!(prompt.clone()),
        completion(json!({"action":"done","evidence":"settings open"})), json!(prompt)], 2);
    assert_eq!(report["done"], false);
    assert_eq!(verifier_calls(&calls), 0);
    assert_eq!(report["reason"], "step budget exhausted");
}

// ---- SystemBrightnessMax -------------------------------------------------------------------

fn display_at(percent: &str) -> ScreenState {
    // Derived: the recorded Display page showed "0%"; only that summary text changes.
    let mut s = fixture("display");
    let summary = s.nodes.iter_mut().find(|n| n.text.as_deref() == Some("0%")).unwrap();
    summary.text = Some(percent.into());
    s
}

#[test]
fn brightness_is_read_from_the_proven_display_page_only() {
    let goal = postcondition::recognize(BRIGHTNESS, &[]).unwrap();
    assert_eq!(goal, Goal::Brightness { percent: 100 });
    let check = |s: &ScreenState| postcondition::assess(&goal, &[], s, s, 0, &[]).verification.status;
    assert_eq!(check(&fixture("display")), VerificationStatus::Unknown);
    assert_eq!(check(&display_at("100%")), VerificationStatus::Verified);
    assert_eq!(check(&display_at("99%")), VerificationStatus::Unknown);
    // The slider has no readable value: the dialog never establishes it.
    assert_eq!(check(&fixture("dialog")), VerificationStatus::Unknown);
    // A page whose whole signature is not proven (one extra Settings row) is not evidence.
    let mut unproven = display_at("100%");
    let mut extra = unproven.nodes.iter().find(|n| n.text.as_deref() == Some("Colors")).unwrap().clone();
    extra.id = 999;
    unproven.nodes.push(extra);
    assert_eq!(check(&unproven), VerificationStatus::Unknown);
    // A foreground readback naming another app contradicts the tree's header.
    let mut other = display_at("100%");
    other.foreground = Some(ForegroundObservation { package: "org.example.evil".into(), activity: other.activity.clone(), sequence: 9 });
    assert_eq!(check(&other), VerificationStatus::Unknown);
}

#[test]
fn brightness_swipe_then_back_completes_without_a_done_claim() {
    let dialog = fixture("dialog");
    let slider = element_for(&dialog, BRIGHTNESS, node_by_rid(&dialog, "com.android.systemui:id/slider"));
    let (report, calls) = run(BRIGHTNESS, vec![json!(dialog.clone()),
        completion(json!({"action":"swipe","target":slider,"direction":"right"})), json!({"result":"ok"}), json!(dialog),
        completion(json!({"action":"back"})), json!({"result":"ok"}), json!(display_at("100%"))], 10);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["steps"], 2);
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_settings_value");
    assert_eq!((planner_calls(&calls), verifier_calls(&calls)), (2, 0));
}

#[test]
fn a_done_claim_on_the_slider_dialog_is_not_completion_and_says_where_to_look() {
    let dialog = fixture("dialog");
    // Before this check a verifier citing the labeled slider was accepted as evidence (the
    // slider's value is not on the screen). The model is no longer asked for this goal.
    let (report, calls) = run(BRIGHTNESS, vec![json!(dialog.clone()),
        completion(json!({"action":"done","evidence":"slider swiped to the right"})), json!(dialog.clone())], 1);
    assert_eq!(report["done"], false);
    assert_eq!(verifier_calls(&calls), 0);
    let event = calls.iter().find(|c| c["ask"] == "log").unwrap();
    assert!(event["event"]["outcome"].as_str().unwrap().contains("Brightness level"), "{event}");
}

// ---- SimpleSmsSend ---------------------------------------------------------------------------

fn send_tap(before: &ScreenState, request: &str) -> String {
    element_for(before, request, node_by_rid(before, "com.simplemobiletools.smsmessenger:id/thread_send_message"))
}

#[test]
fn recorded_send_completes_on_the_readback_with_the_new_outgoing_row() {
    // Recorded: sent at step 6, readback changed (field_emptied), then five done claims were
    // rejected and the budget ran out. Core now checks the new row itself.
    let before = fixture("send_before");
    let (report, calls) = run(SEND, vec![json!(before.clone()),
        completion(json!({"action":"tap","target":send_tap(&before, SEND)})), json!("approved"), json!({"result":"ok"}),
        json!(fixture("send_after"))], 10);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_sent_row");
    assert_eq!(count(&calls, |c| c["ask"] == "confirm"), 1, "the Send stays guarded");
    assert_eq!(count(&calls, |c| c["ask"] == "act"), 1);
    assert_eq!((planner_calls(&calls), verifier_calls(&calls)), (1, 0));
}

fn send_effect(before: &ScreenState, after: &ScreenState, confirmed: bool) -> Effect {
    let target = node_by_rid(before, "com.simplemobiletools.smsmessenger:id/thread_send_message");
    Effect { action: Action::Tap { target: 1 }, before: before.clone(), target: Some(target), confirmed,
        readback: Some(readback::compare(before, after, Some(target))) }
}

#[test]
fn sent_row_check_rejects_other_numbers_bodies_and_unconfirmed_or_unchanged_sends() {
    let (before, after) = (fixture("send_before"), fixture("send_after"));
    let installed = apps(&before);
    let assess = |request: &str, effects: &[Effect], fresh: &ScreenState| {
        let goal = postcondition::recognize(request, &installed).unwrap();
        postcondition::assess(&goal, effects, &before, fresh, 0, &installed).verification.status
    };
    let good = [send_effect(&before, &after, true)];
    assert_eq!(assess(SEND, &good, &after), VerificationStatus::Verified);
    // Another recipient, another body.
    assert_eq!(assess(&SEND.replace("+16604876475", "+16604876476"), &good, &after), VerificationStatus::Unknown);
    assert_eq!(assess(&SEND.replace("grease.", "grease!"), &good, &after), VerificationStatus::Unknown);
    // No confirmed Send, or a readback that did not empty the composer.
    assert_eq!(assess(SEND, &[send_effect(&before, &after, false)], &after), VerificationStatus::Unknown);
    assert_eq!(assess(SEND, &[send_effect(&before, &before, true)], &after), VerificationStatus::Unknown);
    assert_eq!(assess(SEND, &[], &after), VerificationStatus::Unknown);
    // The row must be new: the screen before the Send already showing it is not evidence.
    assert_eq!(assess(SEND, &[send_effect(&after, &after, true)], &after), VerificationStatus::Unknown);
    // An incoming row (with the sender photo) with the same text is not an outgoing row.
    let mut incoming = after.clone();
    let holder = incoming.nodes.iter().find(|n| n.resource_id.as_deref() == Some("com.simplemobiletools.smsmessenger:id/thread_message_holder")).unwrap().id;
    let mut photo = incoming.nodes.iter().find(|n| n.parent == Some(holder)).unwrap().clone();
    photo.id = 999; photo.resource_id = Some("com.simplemobiletools.smsmessenger:id/thread_message_sender_photo".into()); photo.text = None;
    incoming.nodes.push(photo);
    assert_eq!(assess(SEND, &good, &incoming), VerificationStatus::Unknown);
}

#[test]
fn a_failed_sent_row_check_falls_back_to_the_verifier_unchanged() {
    // Another recipient: the code check does not pass, so the ordinary verifier decides, as before.
    let before = fixture("send_before");
    let request = SEND.replace("+16604876475", "+15550000000");
    let (report, calls) = run(&request, vec![json!(before.clone()),
        completion(json!({"action":"tap","target":send_tap(&before, &request)})), json!("approved"), json!({"result":"ok"}),
        json!(fixture("send_after")), completion(json!({"action":"done","evidence":"sent"})), json!(fixture("send_after")),
        json!({"text":r#"{"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}"#})], 2);
    assert_eq!(report["done"], false);
    assert_eq!(verifier_calls(&calls), 1);
}

// ---- SimpleSmsReplyMostRecent ----------------------------------------------------------------

fn reply_effects(row_rank: usize, scrolled: bool) -> Vec<Effect> {
    let list = fixture("reply_list");
    let mut rows: Vec<&Node> = list.nodes.iter().filter(|n| n.resource_id.as_deref() == Some("com.simplemobiletools.smsmessenger:id/conversation_frame")).collect();
    rows.sort_by_key(|n| n.bounds.y1);
    let open = Effect { action: Action::Tap { target: 7 }, before: list.clone(), target: Some(rows[row_rank].id), confirmed: false, readback: None };
    let (before, after) = (fixture("reply_before"), fixture("reply_after"));
    let mut effects = vec![];
    if scrolled {
        effects.push(Effect { action: Action::Scroll { target: None, direction: Direction::Down }, before: list.clone(), target: None, confirmed: false, readback: None });
    }
    effects.push(open);
    effects.push(send_effect(&before, &after, true));
    effects
}

#[test]
fn reply_completes_only_in_the_thread_opened_from_the_top_conversation() {
    let after = fixture("reply_after");
    let installed = apps(&after);
    let goal = postcondition::recognize(REPLY, &installed).unwrap();
    assert!(matches!(goal, Goal::MessageSent { to: Recipient::MostRecent, .. }));
    let assess = |effects: &[Effect]| postcondition::assess(&goal, effects, &fixture("reply_before"), &after, 0, &installed).verification.status;
    // Recorded path: the top row (+16604876475), then the confirmed Send.
    assert_eq!(assess(&reply_effects(0, false)), VerificationStatus::Verified);
    // The second row is not the most recent conversation; a scrolled list proves nothing.
    assert_eq!(assess(&reply_effects(1, false)), VerificationStatus::Unknown);
    assert_eq!(assess(&reply_effects(0, true)), VerificationStatus::Unknown);
    // A thread entered some other way (no conversation tap in this run).
    assert_eq!(assess(&reply_effects(0, false)[1..]), VerificationStatus::Unknown);
    // A top row with an extra child (a pin marker, say) is not the recorded shape.
    let mut effects = reply_effects(0, false);
    let row = effects[0].target.unwrap();
    let mut pin = effects[0].before.nodes.iter().find(|n| n.parent == Some(row)).unwrap().clone();
    pin.id = 999; pin.resource_id = Some("com.simplemobiletools.smsmessenger:id/conversation_pin".into());
    effects[0].before.nodes.push(pin);
    assert_eq!(assess(&effects), VerificationStatus::Unknown);
}

// ---- CameraTakePhoto -------------------------------------------------------------------------

#[test]
fn recorded_shutter_press_completes_on_the_new_thumbnail_and_is_not_repeated() {
    // Recorded: the shutter was pressed at step 2 and again at step 3 (two photos), then done
    // was rejected. Core now checks after the first press and stops there.
    let before = fixture("camera_before");
    let shutter = element_for(&before, PHOTO, node_by_rid(&before, "com.android.camera2:id/shutter_button"));
    let (report, calls) = run(PHOTO, vec![json!(before), completion(json!({"action":"tap","target":shutter})),
        json!({"result":"ok"}), json!(fixture("camera_after"))], 12);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_new_photo_thumbnail");
    assert_eq!(count(&calls, |c| c["ask"] == "act"), 1);
    assert_eq!(planner_calls(&calls), 1);
}

#[test]
fn photo_check_needs_exactly_one_press_and_a_thumbnail_that_was_not_there_before() {
    let (before, after) = (fixture("camera_before"), fixture("camera_after"));
    let goal = postcondition::recognize(PHOTO, &[]).unwrap();
    let press = |on: &ScreenState| Effect { action: Action::Tap { target: 2 }, before: on.clone(),
        target: Some(node_by_rid(on, "com.android.camera2:id/shutter_button")), confirmed: false, readback: None };
    let assess = |effects: &[Effect]| postcondition::assess(&goal, effects, &before, &after, 0, &[]).verification.status;
    assert_eq!(assess(&[press(&before)]), VerificationStatus::Verified);
    assert_eq!(assess(&[press(&before), press(&after)]), VerificationStatus::Unknown);
    assert_eq!(assess(&[press(&after)]), VerificationStatus::Unknown);
    assert_eq!(assess(&[]), VerificationStatus::Unknown);
}
