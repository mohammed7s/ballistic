//! Planner output handling: replies longer than the role cap (Haiku 2 produced 2,360–2,623 text
//! tokens of repeated tags against core's 800), and the correction that follows. Scripted ports.
mod common;
use ballistic_core::{chat::TurnKind, ports::*, types::*};
use common::*;

const WAIT: &str = r#"{"action":"wait","why":"settle"}"#;

fn fake(planner: Vec<String>) -> Fake {
    Fake { screen: Some(screen("example.sms", &["hello"])), planner, ..Fake::default() }
}

/// A runaway reply as recorded: a valid-looking action followed by the same tag over and over.
fn runaway() -> String {
    format!("{WAIT}\n{}", "<parameter_end>\n\n\n".repeat(400))
}

#[test]
fn a_reply_beyond_the_cap_is_invalid_even_with_an_action_in_it() {
    let mut f = fake(vec![runaway(), WAIT.into()]);
    let report = drive(&mut f, "wait for the screen", 1, &harness());
    assert_eq!(report["reason"], "step budget exhausted", "{report}");
    assert_eq!(f.acted.len(), 1, "the corrected reply runs; the runaway one does not");
    let prompts = f.calls.iter().filter(|(r, _)| *r == ModelRole::Planner).map(|(_, m)| m.clone()).collect::<Vec<_>>();
    let retry = &prompts[1];
    let echoed = &retry[retry.len() - 2];
    let correction = &retry[retry.len() - 1];
    assert_eq!(echoed.role, "assistant");
    assert!(echoed.content.chars().count() <= 401, "the runaway text is not sent back whole");
    assert!(correction.content.contains("over the 800-token limit"), "{}", correction.content);
    assert!(correction.content.contains("exactly one corrected JSON object and nothing else"));
}

#[test]
fn two_runaway_replies_end_the_run_as_a_planning_failure_with_nothing_dispatched() {
    let mut f = fake(vec![runaway(), runaway(), runaway()]);
    let report = drive(&mut f, "wait for the screen", 3, &harness());
    assert_eq!(report["failure_category"], "planning");
    assert!(report["reason"].as_str().unwrap().contains("over the 800-token limit"), "{report}");
    assert!(f.acted.is_empty());
    assert_eq!(f.count(ModelRole::Planner), 2);
}

#[test]
fn ordinary_replies_with_a_sentence_of_prose_are_not_overlong() {
    let mut f = fake(vec![format!("I'll wait for the screen to settle.\n{WAIT}")]);
    drive(&mut f, "wait for the screen", 1, &harness());
    assert_eq!(f.acted.len(), 1);
    assert_eq!(f.count(ModelRole::Planner), 1);
}

#[test]
fn the_planner_rules_demand_exactly_one_json_object() {
    let mut f = fake(vec![WAIT.into()]);
    drive(&mut f, "wait for the screen", 1, &harness());
    let system = &f.calls[0].1[0].content;
    assert!(system.starts_with("You control Android for its owner. Reply with exactly one JSON object"), "{system}");
}

#[test]
fn a_chat_reply_beyond_its_cap_gets_one_correction() {
    let long = format!(r#"{{"kind":"reply","text":"{}"}}"#, "hello ".repeat(400));
    let mut f = Fake { planner: vec![long, r#"{"kind":"reply","text":"Hello."}"#.into()], ..Fake::default() };
    let cell = std::cell::RefCell::new(std::mem::take(&mut f));
    struct View<'a>(&'a std::cell::RefCell<Fake>);
    impl Screen for View<'_> { fn observe(&mut self) -> PortResult<ScreenState> { panic!("a reply turn never observes") } }
    impl Hands for View<'_> { fn act(&mut self, _: &HandsOp) -> PortResult<HandsResult> { panic!("a reply turn never acts") } }
    impl Models for View<'_> {
        fn complete(&mut self, r: ModelRole, m: &[ChatMessage], t: u32) -> PortResult<Completion> { self.0.borrow_mut().complete(r, m, t) }
    }
    impl Confirm for View<'_> { fn ask(&mut self, _: &ConfirmRequest) -> PortResult<ConfirmResult> { panic!("no confirmation") } }
    let (mut s, mut h, mut m, mut c) = (View(&cell), View(&cell), View(&cell), View(&cell));
    let turn = ballistic_core::run::converse(&[], "hi", &harness(), &mut s, &mut h, &mut m, &mut c, &mut |_| {}, &|| false).unwrap();
    assert_eq!(turn.kind, TurnKind::Reply);
    assert_eq!(turn.text, "Hello.");
    let f = cell.into_inner();
    let retry = &f.calls[1].1;
    assert!(retry.last().unwrap().content.contains("over the 400-token limit"), "{}", retry.last().unwrap().content);
}
