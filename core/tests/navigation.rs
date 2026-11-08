//! Navigation mechanisms on screens recorded in the Haiku 2 suite
//! (`ballistic_runs/suite_haiku55_20261108-194804/`, 8 Nov 2026): repeated-input detection and the
//! typed-fields line. Scripted ports only; no model, emulator or benchmark database.
use ballistic_core::{agent, bridge::Bridge, screen_filter::{self, FilterContext}, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

fn fixture(name: &str) -> ScreenState {
    let text = match name {
        "calendar_form" => include_str!("screens/navigation/calendar_form.json"),
        "calendar_picker" => include_str!("screens/navigation/calendar_picker.json"),
        "calendar_form_again" => include_str!("screens/navigation/calendar_form_again.json"),
        "calendar_picker_again" => include_str!("screens/navigation/calendar_picker_again.json"),
        "recipe_a" => include_str!("screens/navigation/recipe_a.json"),
        "recipe_b" => include_str!("screens/navigation/recipe_b.json"),
        "recipe_a_again" => include_str!("screens/navigation/recipe_a_again.json"),
        "recipe_b_again" => include_str!("screens/navigation/recipe_b_again.json"),
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}

const CALENDAR: &str = "In Simple Calendar Pro, create a calendar event on 2023-10-27 at 13h with the title 'Workshop on Campaign' and the description 'We will explore team roles. Let's be punctual.'. The event should last for 15 mins.";
const RECIPE: &str = "Add the following recipes into the Broccoli app:\nRecipe: Chicken Alfredo Pasta\n servings: 1 serving";

fn completion(action: Value) -> Value { json!({"text": action.to_string()}) }
fn ok() -> Value { json!({"result":"ok"}) }
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn planner_users(calls: &[Value]) -> Vec<String> {
    calls.iter().filter(|c| c["ask"] == "complete" && c["role"] == "planner")
        .map(|c| c["messages"][1]["content"].as_str().unwrap().to_string()).collect()
}
fn element(s: &ScreenState, request: &str, rid: &str) -> String {
    let view = screen_filter::filter(s, &FilterContext { request, typed: &[] });
    let node = s.nodes.iter().find(|n| n.resource_id.as_deref() == Some(rid)).unwrap_or_else(|| panic!("{rid}")).id;
    format!("e{}", view.elements.iter().find(|e| e.node == node).unwrap().id)
}
fn tap(target: &str) -> Value { completion(json!({"action":"tap","target":target})) }

#[test]
fn recorded_time_picker_alternation_is_warned_then_stopped() {
    // Recorded: from step 16, 18 taps alternated Start time (opens the picker) and OK (closes it
    // unchanged at 13:15) until the 34-step budget ran out.
    let start = element(&fixture("calendar_form"), CALENDAR, "com.simplemobiletools.calendar.pro:id/event_start_time");
    let okay = element(&fixture("calendar_picker"), CALENDAR, "com.simplemobiletools.calendar.pro:id/material_timepicker_ok_button");
    let (report, calls) = run(CALENDAR, vec![json!(fixture("calendar_form")),
        tap(&start), ok(), json!(fixture("calendar_picker")),
        tap(&okay), ok(), json!(fixture("calendar_form_again")),
        tap(&start), ok(), json!(fixture("calendar_picker_again")),
        tap(&okay), ok(), json!(fixture("calendar_form")),
        tap(&start), ok(), json!(fixture("calendar_picker")),
    ], 34);
    let users = planner_users(&calls);
    assert_eq!(users.len(), 5);
    // Steps 1-4 carry no warning; step 5's prompt names the alternation in core's words only.
    assert!(users[..4].iter().all(|u| !u.contains("warning:")));
    assert!(users[4].contains(&format!("warning: tap {start} and tap {okay} alternated twice")), "{}", users[4]);
    // The planner continued the pattern: the run stops before another planner call.
    assert_eq!(report["done"], false);
    assert_eq!(report["failure_category"], "planning");
    assert!(report["reason"].as_str().unwrap().starts_with("stopped:"), "{report}");
    assert_eq!(report["steps"], 5);
}

#[test]
fn a_warning_followed_by_a_different_action_continues_the_run() {
    let start = element(&fixture("calendar_form"), CALENDAR, "com.simplemobiletools.calendar.pro:id/event_start_time");
    let okay = element(&fixture("calendar_picker"), CALENDAR, "com.simplemobiletools.calendar.pro:id/material_timepicker_ok_button");
    let (report, calls) = run(CALENDAR, vec![json!(fixture("calendar_form")),
        tap(&start), ok(), json!(fixture("calendar_picker")),
        tap(&okay), ok(), json!(fixture("calendar_form_again")),
        tap(&start), ok(), json!(fixture("calendar_picker_again")),
        tap(&okay), ok(), json!(fixture("calendar_form")),
        // Warned at step 5: a different input breaks the pattern and the run goes on.
        completion(json!({"action":"back"})), ok(), json!(fixture("calendar_form_again")),
        completion(json!({"action":"give_up","reason":"checked"})),
    ], 34);
    assert_eq!(report["reason"], "planner gave up", "{report}");
    assert_eq!(planner_users(&calls).len(), 6);
}

#[test]
fn recorded_scroll_up_down_oscillation_is_detected() {
    // Recorded: after every field was typed, 14 scrolls mostly alternated down/up on the form.
    let s = fixture("recipe_a");
    let list = {
        let view = screen_filter::filter(&s, &FilterContext { request: RECIPE, typed: &[] });
        format!("e{}", view.elements.iter().find(|e| e.flags.scrollable).unwrap().id)
    };
    let scroll = |d: &str| completion(json!({"action":"scroll","target":list,"direction":d}));
    let (report, calls) = run(RECIPE, vec![json!(fixture("recipe_a")),
        scroll("down"), ok(), json!(fixture("recipe_b")),
        scroll("up"), ok(), json!(fixture("recipe_a_again")),
        scroll("down"), ok(), json!(fixture("recipe_b_again")),
        scroll("up"), ok(), json!(fixture("recipe_a")),
        scroll("down"), ok(), json!(fixture("recipe_b")),
    ], 24);
    let users = planner_users(&calls);
    assert!(users[4].contains("alternated twice"), "{}", users[4]);
    assert!(report["reason"].as_str().unwrap().starts_with("stopped:"), "{report}");
}

#[test]
fn typed_field_labels_stay_in_the_prompt_after_they_scroll_away() {
    let top = fixture("recipe_a");
    let view = screen_filter::filter(&top, &FilterContext { request: RECIPE, typed: &[] });
    let title = view.elements.iter().find(|e| e.label.as_deref() == Some("Title") && e.flags.editable).map(|e| format!("e{}", e.id));
    let Some(title) = title else {
        // This recorded observation is the lower half of the form; use the other one.
        let other = fixture("recipe_b");
        let view = screen_filter::filter(&other, &FilterContext { request: RECIPE, typed: &[] });
        let title = format!("e{}", view.elements.iter().find(|e| e.label.as_deref() == Some("Title") && e.flags.editable).unwrap().id);
        return typed_field_case(other, title);
    };
    typed_field_case(top, title);
}

fn typed_field_case(with_title: ScreenState, title: String) {
    let (_, calls) = run(RECIPE, vec![json!(with_title.clone()),
        completion(json!({"action":"type","target":title,"text":"Chicken Alfredo Pasta","submit":false})), ok(),
        json!(with_title),
        completion(json!({"action":"give_up","reason":"checked"}))], 4);
    let users = planner_users(&calls);
    assert!(!users[0].contains("Fields typed in this task"));
    assert!(users[1].contains("Fields typed in this task: Title\n"), "{}", users[1]);
}

#[test]
fn the_same_input_on_changing_screens_is_progress_not_a_loop() {
    use ballistic_core::postcondition::Effect;
    let effect = |before: ScreenState, action: Action| Effect { action, before, target: None, confirmed: false, readback: None };
    // Three identical inputs, each planned on a different screen (a keypad display changing
    // after every Backspace, a list moving after every scroll): not repetition.
    let changing = vec![effect(fixture("recipe_a"), Action::Back), effect(fixture("recipe_b"), Action::Back), effect(fixture("calendar_form"), Action::Back)];
    assert_eq!(agent::repeated_inputs(&changing), None);
    // The same three on one unchanged screen are.
    let same = vec![effect(fixture("calendar_form"), Action::Back), effect(fixture("calendar_form_again"), Action::Back), effect(fixture("calendar_form"), Action::Back)];
    assert_eq!(agent::repeated_inputs(&same).as_deref(), Some("back was repeated three times"));
    // Scrolling down three times through changing screens is progress.
    let down = || Action::Scroll { target: Some(4), direction: Direction::Down };
    let scrolls = vec![effect(fixture("recipe_a"), down()), effect(fixture("recipe_b"), down()), effect(fixture("calendar_form"), down())];
    assert_eq!(agent::repeated_inputs(&scrolls), None);
}
