//! The question form of the owner excerpt channel and the calendar list's section dates (loop 2,
//! 9 Nov 2026), on the event list recorded in the Opus 5.5 suite (b22da1f,
//! SimpleCalendarEventsOnDate). Scripted models only; no emulator or checker.
use ballistic_core::{agent, bridge::Bridge, reader::summary, screen_filter::{self, FilterContext}, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

fn fixture() -> ScreenState {
    let text = include_str!("screens/navigation/calendar_events_list.json");
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}
const QUESTION: &str = "What events do I have October 27 2023 in Simple Calendar Pro? Answer with the titles only. If there are multiple titles, format your answer as a comma separated list.";

fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}

#[test]
fn question_forms_opt_in_and_others_do_not() {
    assert!(summary::requested(QUESTION));
    assert_eq!(summary::tokens(QUESTION), summary::QUESTION_TOKENS);
    assert!(summary::requested("Is the note titled 'Grocery' in Joplin a todo?"));
    assert_eq!(summary::tokens("Summarize the note."), summary::SUMMARY_TOKENS);
    for other in ["Turn wifi off.", "Delete the note in Markor named x.", "Send a text message to +15550100 with message: what?",
        "Open Settings?", "Reply with message: are you there? I am late."] {
        assert!(!summary::requested(other), "{other}");
    }
}

#[test]
fn recorded_section_headers_carry_their_day_and_weekday_but_stay_content() {
    let s = fixture();
    let view = screen_filter::filter(&s, &FilterContext { request: QUESTION, typed: &[] });
    let rendered = screen_filter::render(&view);
    assert!(rendered.contains("[observed section day=27 weekday=5 month=unknown"), "{rendered}");
    assert!(rendered.contains("[observed section day=30 weekday=1 month=unknown"), "{rendered}");
    assert!(!rendered.contains("27 Friday") && !rendered.contains("Bug Bash"), "{rendered}");
    // Derived: another grammar observes nothing.
    let mut other = s.clone();
    other.nodes.iter_mut().find(|n| n.text.as_deref() == Some("27 Friday")).unwrap().text = Some("27 Freitag".into());
    let rendered = screen_filter::render(&screen_filter::filter(&other, &FilterContext { request: QUESTION, typed: &[] }));
    assert!(!rendered.contains("section day=27"), "{rendered}");
}

#[test]
fn a_question_ends_with_exact_owner_excerpts_from_the_rows_the_planner_chose() {
    let s = fixture();
    let view = screen_filter::filter(&s, &FilterContext { request: QUESTION, typed: &[] });
    // The row right after the "27 Friday" header (recorded: Bug Bash).
    let header = view.elements.iter().position(|e| e.observed_values.iter()
        .any(|v| matches!(v.value, screen_filter::ObservedValue::DayOfMonth { day: 27, .. }))).unwrap();
    let row = &view.elements[header + 1];
    let c = row.content.unwrap();
    let read = json!({"action":"read","targets":[format!("c{c}")],"question":"titles of the events on the 27th","shape":"summary"});
    let reply = json!({"items":[{"ref":format!("c{c}"),"text":"Bug Bash"}]});
    let (report, calls) = run(QUESTION, vec![json!(s.clone()), json!({"text":read.to_string()}), json!({"text":reply.to_string()}), json!(s)], 10);
    assert_eq!(report["owner_output"]["status"], "available", "{report}");
    assert_eq!(report["owner_output"]["items"][0]["text"], "Bug Bash");
    assert_eq!(report["done"], false);
    assert_eq!(report["verification"]["evidence_basis"], "model_selected_source_excerpt");
    let reader = calls.iter().find(|c| c["role"] == "reader").unwrap();
    assert_eq!(reader["max_tokens"], summary::QUESTION_TOKENS);
    assert!(reader["messages"][0]["content"].as_str().unwrap().starts_with("The owner asked a question."));
    // The planner never saw the title.
    for p in calls.iter().filter(|c| c["role"] == "planner") { assert!(!p.to_string().contains("Bug Bash")); }
    // An excerpt that is not in the source is rejected, as for a summary.
    let bad = json!({"items":[{"ref":format!("c{c}"),"text":"Bug Bash Party"}]});
    let (report, _) = run(QUESTION, vec![json!(fixture()), json!({"text":read.to_string()}), json!({"text":bad.to_string()})], 10);
    assert_eq!(report["owner_output"]["status"], "rejected", "{report}");
}
