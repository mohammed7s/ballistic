//! The context guard (core/src/context.rs) over scripted ports: a host-advertised context limit,
//! each bounded reduction in its fixed order, the refusal, and "fits, no change". Limits are
//! computed from the prompts an unlimited run actually sent, so each test lands on one branch.
mod common;
use ballistic_core::{chat::{self, Turn, TurnKind, TurnSource}, context::{estimate, thousands}, ports::*, run::{self, Options}, types::*};
use common::*;
use serde_json::{json, Value};

const WAIT: &str = r#"{"action":"wait","why":"settle"}"#;
const TAP_DELETE: &str = r#"{"action":"tap","target":"e2"}"#;

/// `n` harmless inputs that never repeat in a pattern (core stops repeated inputs on an
/// unchanged screen), so each step adds one history line.
fn varied(n: usize) -> Vec<&'static str> {
    [WAIT, r#"{"action":"back","why":"settle"}"#, r#"{"action":"home","why":"settle"}"#].into_iter().cycle().take(n).collect()
}
const MESSAGE: &str = "From Ali: my new number is +1 555 0100. Dinner was $40 on Tue 3 Nov 14:05.";
const READ_FACTS: &str = r#"{"action":"read","targets":["c1"],"question":"what does the newest message say?","shape":"facts"}"#;

fn facts_reply() -> String {
    json!({"sender":"Ali","when":"Tue 3 Nov 14:05","phone_numbers":["+1 555 0100"],"amounts":["$40"],"urls":[],"summary":"New number."}).to_string()
}

fn fake(planner: Vec<&str>, limits: Vec<(ModelRole, u32)>) -> Fake {
    Fake { screen: Some(screen("example.sms", &[MESSAGE])), planner: planner.into_iter().map(String::from).collect(), limits, ..Fake::default() }
}

fn calls(fake: &Fake, role: ModelRole) -> Vec<Vec<ChatMessage>> {
    fake.calls.iter().filter(|(r, _)| *r == role).map(|(_, m)| m.clone()).collect()
}

fn sent_max(fake: &Fake, role: ModelRole) -> Vec<u32> {
    fake.sent_max.iter().filter(|(r, _)| *r == role).map(|(_, n)| *n).collect()
}

/// The planner prompt with its `k` oldest history lines removed, as core's trim builds it.
fn drop_oldest_history(messages: &[ChatMessage], k: usize) -> Vec<ChatMessage> {
    let user = &messages[1].content;
    let start = user.find("\nHistory: ").unwrap() + "\nHistory: ".len();
    let end = start + [user[start..].find("\nReader facts:"), user[start..].find("\nScreen ")].into_iter().flatten().min().unwrap();
    let lines: Vec<&str> = user[start..end].split('\n').collect();
    assert!(lines.len() > k, "{lines:?}");
    let mut out = messages.to_vec();
    out[1].content = format!("{}{}{}", &user[..start], lines[k..].join("\n"), &user[end..]);
    out
}

/// The planner prompt without its reader facts block (one fact set and no reader pick).
fn drop_facts_block(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    let user = &messages[1].content;
    let start = user.find("Reader facts:\n").unwrap();
    let line_end = start + "Reader facts:\n".len() + user[start + "Reader facts:\n".len()..].find('\n').unwrap() + 1;
    let mut out = messages.to_vec();
    out[1].content = format!("{}{}", &user[..start], &user[line_end..]);
    out
}

fn check(event: &Value) -> &Value {
    let list = event["context_guard"].as_array().unwrap_or_else(|| panic!("no context_guard in {event}"));
    &list[0]
}

#[test]
fn no_limit_changes_nothing_and_logs_no_guard() {
    // The harness: no limit advertised, so prompts and max_tokens are exactly as built.
    let mut base = fake(vec![WAIT, WAIT], vec![]);
    drive(&mut base, "wait for the screen", 3, &harness());
    let mut wide = fake(vec![WAIT, WAIT], vec![(ModelRole::Planner, 1_000_000)]);
    drive(&mut wide, "wait for the screen", 3, &harness());
    assert_eq!(calls(&base, ModelRole::Planner), calls(&wide, ModelRole::Planner));
    assert_eq!(sent_max(&base, ModelRole::Planner), vec![800, 800, 800]);
    assert_eq!(sent_max(&wide, ModelRole::Planner), vec![800, 800, 800]);
    for e in &base.events {
        assert!(e.get("context_guard").is_none(), "{e}");
        assert!(e.get("prompt_tokens").is_none(), "unknown usage is not logged as a number: {e}");
    }
}

#[test]
fn fits_no_change_logs_the_estimate_limit_and_reported_usage() {
    let mut f = fake(vec![WAIT], vec![(ModelRole::Planner, 4096)]);
    f.usage = Usage { prompt_tokens: 2441, completion_tokens: 36 };
    drive(&mut f, "wait for the screen", 1, &harness());
    let sent = &calls(&f, ModelRole::Planner)[0];
    let c = check(&f.events[0]);
    assert_eq!(c["role"], "planner");
    assert_eq!(c["context_tokens"], 4096);
    assert_eq!(c["prompt_estimate"], estimate(sent));
    assert_eq!(c["full_prompt_estimate"], estimate(sent));
    assert_eq!(c["counted_by"], "estimate");
    assert_eq!((c["requested_max_tokens"].as_u64(), c["max_tokens"].as_u64()), (Some(800), Some(800)));
    assert_eq!(c["reductions"], json!([]));
    assert_eq!(c["fits"], true);
    assert_eq!((c["prompt_tokens"].as_u64(), c["completion_tokens"].as_u64()), (Some(2441), Some(36)));
    // The step event carries the host's real usage, for every call in the step.
    assert_eq!((f.events[0]["prompt_tokens"].as_u64(), f.events[0]["completion_tokens"].as_u64()), (Some(2441), Some(36)));
    assert!(estimate(sent) + 800 <= 4096, "a one-node screen fits a 4,096-token context");
}

#[test]
fn a_reported_usage_without_a_limit_still_reaches_the_step_event() {
    let mut f = fake(vec![WAIT], vec![]);
    f.usage = Usage { prompt_tokens: 120, completion_tokens: 0 };
    drive(&mut f, "wait for the screen", 1, &harness());
    assert_eq!(f.events[0]["prompt_tokens"], 120);
    assert!(f.events[0].get("completion_tokens").is_none());
    assert!(f.events[0].get("context_guard").is_none());
}

#[test]
fn the_planner_budget_shrinks_before_any_history_line_goes() {
    // Recorded planner replies are 36-49 tokens: a smaller budget costs nothing, a lost history
    // line costs context. Step 6 keeps all five lines and gets the room left.
    let mut base = fake(varied(5), vec![]);
    drive(&mut base, "wait for the screen", 6, &harness());
    let full = &calls(&base, ModelRole::Planner)[5];
    let limit = estimate(full) + 300;
    let mut f = fake(varied(5), vec![(ModelRole::Planner, limit)]);
    drive(&mut f, "wait for the screen", 6, &harness());
    assert_eq!(&calls(&f, ModelRole::Planner)[5], full);
    assert_eq!(sent_max(&f, ModelRole::Planner)[5], 300);
    let c = check(&f.events[5]);
    assert_eq!(c["reductions"], json!(["max_tokens"]));
    assert!(c.get("history_lines_dropped").is_none());
}

#[test]
fn reduction_a_drops_the_oldest_history_lines_only_when_the_minimum_budget_does_not_fit() {
    let mut base = fake(varied(5), vec![]);
    drive(&mut base, "wait for the screen", 6, &harness());
    let full = &calls(&base, ModelRole::Planner)[5];
    let trimmed = drop_oldest_history(full, 1);
    let limit = estimate(&trimmed) + 256;
    assert!(estimate(full) + 256 > limit);
    let mut f = fake(varied(5), vec![(ModelRole::Planner, limit)]);
    drive(&mut f, "wait for the screen", 6, &harness());
    let sent = calls(&f, ModelRole::Planner);
    // Step 6 lost exactly its oldest line and kept the planner's minimum budget.
    assert_eq!(sent[5], trimmed);
    assert_eq!(sent_max(&f, ModelRole::Planner)[5], 256);
    let c = check(&f.events[5]);
    assert_eq!(c["reductions"], json!(["max_tokens", "history"]));
    assert_eq!(c["history_lines_dropped"], 1);
    assert_eq!(c["full_prompt_estimate"], estimate(full));
    assert_eq!(c["prompt_estimate"], estimate(&trimmed));
    // The rules, schema, request and screen are all still there.
    assert_eq!(sent[5][0], full[0]);
    for kept in ["Owner request: wait for the screen", "Screen example.sms / Main:"] {
        assert!(sent[5][1].content.contains(kept));
    }
}

#[test]
fn history_never_goes_below_its_floor() {
    let mut base = fake(varied(5), vec![]);
    drive(&mut base, "wait for the screen", 6, &harness());
    let full = &calls(&base, ModelRole::Planner)[5];
    let at_floor = drop_oldest_history(full, 3);
    // At the floor (two lines) exactly the minimum fits.
    let limit = estimate(&at_floor) + 256;
    let mut f = fake(varied(5), vec![(ModelRole::Planner, limit)]);
    drive(&mut f, "wait for the screen", 6, &harness());
    assert_eq!(calls(&f, ModelRole::Planner)[5], at_floor);
    assert_eq!(sent_max(&f, ModelRole::Planner)[5], 256);
    let c = check(&f.events[5]);
    assert_eq!(c["reductions"], json!(["max_tokens", "history"]));
    assert_eq!(c["history_lines_dropped"], 3);
    // One token less: the floors hold and a call is refused rather than cut further (an earlier
    // step with two lines of its own may already be the one refused).
    let mut g = fake(varied(5), vec![(ModelRole::Planner, limit - 1)]);
    let report = drive(&mut g, "wait for the screen", 6, &harness());
    assert_eq!(report["failure_category"], "model_context");
    let refused = report["context_guard"].as_array().unwrap().last().unwrap();
    assert_eq!((refused["fits"].as_bool(), refused["max_tokens"].as_u64()), (Some(false), Some(256)));
}

#[test]
fn reduction_b_drops_reader_facts_older_than_the_previous_step_only() {
    let mut base = fake(vec![READ_FACTS, WAIT, WAIT], vec![]);
    base.reader = vec![facts_reply()];
    drive(&mut base, "what does the newest message say?", 3, &harness());
    let full = &calls(&base, ModelRole::Planner)[2];
    assert!(full[1].content.contains("reader facts f1"), "{}", full[1].content);
    let trimmed = drop_facts_block(full);
    // Room for step 3 without its old facts at the planner's minimum budget, and for step 2.
    let step2 = &calls(&base, ModelRole::Planner)[1];
    let limit = estimate(&trimmed).max(estimate(step2)) + 256;
    assert!(estimate(full) + 256 > limit);
    let mut f = fake(vec![READ_FACTS, WAIT, WAIT], vec![(ModelRole::Planner, limit), (ModelRole::Reader, limit)]);
    f.reader = vec![facts_reply()];
    drive(&mut f, "what does the newest message say?", 3, &harness());
    let sent = calls(&f, ModelRole::Planner);
    // Step 2 acts on the facts just read: they stay, whatever else had to give.
    assert!(sent[1][1].content.contains("reader facts f1"));
    assert!(!f.events[1]["context_guard"][0]["reductions"].as_array().unwrap().contains(&json!("old_reader_facts")));
    // Step 3: the budget is at its minimum and history at its floor (two lines), so the step-1
    // fact set goes.
    assert_eq!(sent[2], trimmed);
    assert_eq!(sent_max(&f, ModelRole::Planner)[2], limit - estimate(&trimmed));
    assert_eq!(check(&f.events[2])["reductions"], json!(["max_tokens", "old_reader_facts"]));
    // The reader call itself was checked too, and never shortened.
    let reader = f.events[0]["context_guard"].as_array().unwrap().iter().find(|c| c["role"] == "reader").unwrap();
    assert_eq!(reader["reductions"], json!([]));
}

#[test]
fn reduction_d_shrinks_max_tokens_and_leaves_the_prompt() {
    let mut base = fake(vec![WAIT], vec![]);
    drive(&mut base, "wait for the screen", 1, &harness());
    let prompt = &calls(&base, ModelRole::Planner)[0];
    let limit = estimate(prompt) + 500;
    let mut f = fake(vec![WAIT], vec![(ModelRole::Planner, limit)]);
    drive(&mut f, "wait for the screen", 1, &harness());
    assert_eq!(&calls(&f, ModelRole::Planner)[0], prompt);
    assert_eq!(sent_max(&f, ModelRole::Planner), vec![500]);
    let c = check(&f.events[0]);
    assert_eq!(c["reductions"], json!(["max_tokens"]));
    assert_eq!((c["requested_max_tokens"].as_u64(), c["max_tokens"].as_u64()), (Some(800), Some(500)));
}

#[test]
fn reduction_e_refuses_without_calling_the_model() {
    let mut base = fake(vec![WAIT], vec![]);
    drive(&mut base, "wait for the screen", 1, &harness());
    let limit = estimate(&calls(&base, ModelRole::Planner)[0]) + 255;
    let mut f = fake(vec![WAIT], vec![(ModelRole::Planner, limit)]);
    let report = drive(&mut f, "wait for the screen", 1, &harness());
    assert!(f.calls.is_empty() && f.acted.is_empty() && f.asked.is_empty());
    assert_eq!(report["failure_category"], "model_context");
    assert_eq!(report["task_status"], "failed");
    assert_eq!(report["execution_status"], "not_executed");
    let reason = report["reason"].as_str().unwrap();
    assert!(reason.starts_with(&format!("prompt does not fit this model's {}-token context", thousands(limit))), "{reason}");
    assert!(reason.ends_with("the model was not asked"), "{reason}");
    let c = &report["context_guard"][0];
    assert_eq!(c["fits"], false);
    // The planner tried its smaller budget first; nothing else could be dropped.
    assert_eq!(c["reductions"], json!(["max_tokens"]));
}

#[test]
fn a_4096_context_refuses_what_a_1280_bundle_cannot_hold() {
    // Gemma 3 1B on Tensor G5 is compiled for 1,280 tokens: the planner's rules alone are more.
    let mut f = fake(vec![WAIT], vec![(ModelRole::Planner, 1280)]);
    let report = drive(&mut f, "wait for the screen", 1, &harness());
    assert!(report["reason"].as_str().unwrap().starts_with("prompt does not fit this model's 1,280-token context"));
    assert!(f.calls.is_empty());
}

#[test]
fn a_host_count_replaces_the_estimate() {
    let mut f = fake(vec![WAIT], vec![(ModelRole::Planner, 3500)]);
    f.exact = Some(3000);
    drive(&mut f, "wait for the screen", 1, &harness());
    let c = check(&f.events[0]);
    assert_eq!(c["counted_by"], "host");
    assert_eq!(c["prompt_estimate"], 3000);
    assert_eq!(sent_max(&f, ModelRole::Planner), vec![500]);
}

#[test]
fn a_judge_call_that_does_not_fit_stops_before_confirmation_and_dispatch() {
    let mut f = fake(vec![TAP_DELETE], vec![(ModelRole::Judge, 50)]);
    f.approve = true;
    let report = drive(&mut f, "delete the draft", 1, &with_judge());
    assert_eq!(report["failure_category"], "model_context");
    assert!(f.acted.is_empty() && f.asked.is_empty());
    assert_eq!(f.count(ModelRole::Judge), 0);
    // The planner had no limit and was asked as before.
    assert_eq!(sent_max(&f, ModelRole::Planner), vec![800]);
    let roles: Vec<&Value> = report["context_guard"].as_array().unwrap().iter().map(|c| &c["role"]).collect();
    assert_eq!(roles, vec!["judge"]);
}

/// One conversation turn through the in-process entry point, the four ports lent from one fake.
fn converse(fake: &mut Fake, history: &[Turn], message: &str) -> chat::TurnResult {
    let cell = std::cell::RefCell::new(std::mem::take(fake));
    struct View<'a>(&'a std::cell::RefCell<Fake>);
    impl Screen for View<'_> { fn observe(&mut self) -> PortResult<ScreenState> { self.0.borrow_mut().observe() } }
    impl Hands for View<'_> { fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> { self.0.borrow_mut().act(op) } }
    impl Models for View<'_> {
        fn complete(&mut self, r: ModelRole, m: &[ChatMessage], t: u32) -> PortResult<Completion> { self.0.borrow_mut().complete(r, m, t) }
        fn limits(&mut self, r: ModelRole) -> PortResult<ModelLimits> { self.0.borrow_mut().limits(r) }
        fn count_tokens(&mut self, r: ModelRole, m: &[ChatMessage]) -> PortResult<Option<u32>> { self.0.borrow_mut().count_tokens(r, m) }
    }
    impl Confirm for View<'_> { fn ask(&mut self, q: &ConfirmRequest) -> PortResult<ConfirmResult> { self.0.borrow_mut().ask(q) } }
    let (mut s, mut h, mut m, mut c) = (View(&cell), View(&cell), View(&cell), View(&cell));
    let result = run::converse(history, message, &Options::harness(), &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| false).unwrap();
    *fake = cell.into_inner();
    result
}

fn conversation() -> Vec<Turn> {
    (0..6).map(|i| Turn {
        source: if i % 2 == 0 { TurnSource::Owner } else { TurnSource::Assistant },
        text: format!("turn {i}: {}", "words ".repeat(40)),
        values: vec![],
    }).collect()
}

#[test]
fn reduction_c_drops_the_oldest_chat_turns() {
    let history = conversation();
    let admitted = chat::admit(&history, Options::harness().chat_history_turns);
    let trimmed = chat::prompt(&admitted[2..], "hello");
    let limit = estimate(&trimmed) + 400;
    assert!(estimate(&chat::prompt(&admitted, "hello")) + 400 > limit);
    let mut f = Fake { planner: vec![r#"{"kind":"reply","text":"Hi."}"#.into()], limits: vec![(ModelRole::Chat, limit)], ..Fake::default() };
    let turn = converse(&mut f, &history, "hello");
    assert_eq!(turn.kind, TurnKind::Reply);
    assert_eq!(calls(&f, ModelRole::Chat), vec![trimmed]);
    assert_eq!(sent_max(&f, ModelRole::Chat), vec![400]);
    let c = serde_json::to_value(&turn.context_guard).unwrap();
    assert_eq!(c[0]["reductions"], json!(["chat_history"]));
    assert_eq!(c[0]["chat_turns_dropped"], 2);
    // The owner's new message is always in the prompt.
    assert!(calls(&f, ModelRole::Chat)[0][1].content.contains("hello"));
}

#[test]
fn a_chat_turn_that_cannot_fit_is_refused_and_appends_nothing() {
    let mut f = Fake { planner: vec![r#"{"kind":"reply","text":"Hi."}"#.into()], limits: vec![(ModelRole::Chat, 300)], ..Fake::default() };
    let turn = converse(&mut f, &conversation(), "hello");
    assert_eq!(turn.kind, TurnKind::DoesNotFit);
    assert!(turn.history.is_empty());
    assert!(f.calls.is_empty() && f.acted.is_empty());
    assert!(turn.text.contains("prompt does not fit this model's 300-token context"), "{}", turn.text);
    assert_eq!(turn.context_guard[0].chat_turns_dropped, 4, "every turn above the floor was tried first");
    let json = serde_json::to_value(&turn).unwrap();
    assert_eq!(json["kind"], "does_not_fit");
}

#[test]
fn a_chat_turn_without_a_limit_has_no_guard_field() {
    let mut f = Fake { planner: vec![r#"{"kind":"reply","text":"Hi."}"#.into()], ..Fake::default() };
    let turn = converse(&mut f, &conversation(), "hello");
    assert_eq!(turn.kind, TurnKind::Reply);
    assert!(serde_json::to_value(&turn).unwrap().get("context_guard").is_none());
}
