//! Planner output handling: replies longer than the role cap (Haiku 2 produced 2,360–2,623 text
//! tokens of repeated tags against core's 800), and the correction that follows. Scripted ports.
mod common;
use ballistic_core::{actions, chat::TurnKind, ports::*, types::*};
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
fn runaway_replies_after_every_correction_end_the_run_as_a_planning_failure_with_nothing_dispatched() {
    // The default is two corrections: three replies in all.
    let mut f = fake(vec![runaway(), runaway(), runaway(), WAIT.into()]);
    let report = drive(&mut f, "wait for the screen", 3, &harness());
    assert_eq!(report["failure_category"], "planning");
    assert!(report["reason"].as_str().unwrap().contains("over the 800-token limit"), "{report}");
    assert!(f.acted.is_empty());
    assert_eq!(f.count(ModelRole::Planner), 3);
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

// ---- forgiving parsing for small models ---------------------------------------------------------

#[test]
fn extra_fields_on_argumentless_actions_are_ignored_and_logged() {
    for (text, field) in [(r#"{"action":"back","target":"e3","why":"x"}"#, "target"), (r#"{"action":"wait","seconds":2}"#, "seconds"),
        (r#"{"action":"home","text":"bad"}"#, "text")] {
        let reply = actions::parse_reply(text).unwrap();
        assert!(matches!(reply.action, Action::Back | Action::Wait | Action::Home));
        assert!(reply.notes.iter().any(|n| n.contains(&format!("ignored field {field:?}"))), "{:?}", reply.notes);
    }
    // Other actions still reject unknown fields: an extra field there may change the meaning.
    assert!(actions::parse_reply(r#"{"action":"tap","target":"e1","x":5}"#).is_err());
    let mut f = fake(vec![r#"{"action":"back","target":"e3"}"#.into()]);
    drive(&mut f, "go back", 1, &harness());
    assert_eq!(f.acted, vec![HandsOp::Back]);
    assert_eq!(f.events[0]["parse_notes"], serde_json::json!(["ignored field \"target\" on back"]));
}

#[test]
fn unambiguous_key_aliases_are_read_as_the_schema_key() {
    let parse = |t: &str| actions::parse_reply(t).map(|r| (r.action, r.notes));
    assert_eq!(parse(r#"{"action":"tap","element":"e4"}"#).unwrap().0, Action::Tap { target: 4 });
    assert_eq!(parse(r#"{"action":"type","target":"e2","value":"hi"}"#).unwrap().0, Action::Type { target: 2, text: "hi".into(), submit: false });
    assert_eq!(parse(r#"{"action":"open_app","app_name":"Clock"}"#).unwrap().0, Action::OpenApp { app: "Clock".into() });
    assert_eq!(parse(r#"{"action":"scroll","dir":"down"}"#).unwrap().0, Action::Scroll { target: None, direction: Direction::Down, until: None });
    assert_eq!(parse(r#"{"action":"read","target":"c4","question":"q","shape":"facts"}"#).unwrap().0,
        Action::Read { targets: vec![4], question: "q".into(), shape: ReadShape::Facts });
    let (action, notes) = parse(r#"{"action":"tap","target":"e1","reasoning":"open it"}"#).unwrap();
    assert_eq!(action, Action::Tap { target: 1 });
    assert_eq!(notes, vec!["read \"reasoning\" as \"why\"".to_string()]);
    // give_up's reason is its own field, not an alias of why.
    assert_eq!(parse(r#"{"action":"give_up","reason":"no such app"}"#).unwrap().0, Action::GiveUp { reason: "no such app".into() });
    // Two aliases for one key, or an alias next to the key itself: ambiguous, rejected.
    assert!(parse(r#"{"action":"tap","element":"e1","element_id":"e2"}"#).is_err());
    assert!(parse(r#"{"action":"tap","target":"e1","element":"e2"}"#).is_err());
    // An alias never lends authority: the id rules still apply.
    assert!(parse(r#"{"action":"tap","element":5}"#).is_err());
}

fn apps(labels: &[(&str, &str)]) -> Vec<AppEntry> {
    labels.iter().map(|(l, p)| AppEntry { label: l.to_string(), package: p.to_string(), activity: None }).collect()
}

#[test]
fn close_app_names_map_only_to_advertised_apps() {
    let installed = apps(&[("settings", "com.android.settings"), ("simple sms messenger", "com.simplemobiletools.smsmessenger"), ("clock", "com.google.android.deskclock")]);
    for (asked, package) in [("system_settings", "com.android.settings"), ("SystemSettings", "com.android.settings"), ("Settings app", "com.android.settings"),
        ("SimpleSMSMessenger", "com.simplemobiletools.smsmessenger"), ("simple_sms_messenger", "com.simplemobiletools.smsmessenger"), ("CLOCK", "com.google.android.deskclock")] {
        assert_eq!(actions::match_app(asked, &installed).map(|a| a.package.as_str()), Some(package), "{asked}");
    }
    // Settings not advertised: no mapping to anything else.
    let without = apps(&[("clock", "com.google.android.deskclock"), ("contacts", "com.google.android.contacts")]);
    assert_eq!(actions::match_app("system_settings", &without), None);
    assert_eq!(actions::match_app("SystemSettings", &without), None);
}

#[test]
fn a_missing_action_is_not_guessed_and_the_correction_names_what_fits() {
    let error = actions::parse_reply(r#"{"target":"e1","why":"open it"}"#).unwrap_err();
    assert_eq!(error, actions::ActionError::NoAction { fits: vec!["tap", "long_press"] });
    let message = actions::correction(&error, r#"{"target":"e1"}"#);
    assert!(message.contains("its fields fit \"tap\" or \"long_press\", but core does not guess the action"), "{message}");
    assert!(message.contains(r#"A correct tap action: {"action":"tap","target":"e3","why":"open the menu"}"#), "{message}");
    let mut f = fake(vec![r#"{"target":"e1","why":"open it"}"#.into(), WAIT.into()]);
    drive(&mut f, "wait for the screen", 1, &harness());
    assert_eq!(f.acted, vec![HandsOp::Wait { millis: actions::WAIT_MILLIS }], "the object without an action was not executed");
}

#[test]
fn corrections_show_one_correct_example_of_the_attempted_action() {
    for (reply, attempted) in [(r#"{"action":"type","target":"e1"}"#, "type"), (r#"{"action":"click","target":"e1"}"#, "tap"),
        (r#"{"action":"open_app","application":"Clock"}"#, "open_app")] {
        let error = actions::parse_reply(reply).unwrap_err();
        let message = actions::correction(&error, reply);
        let example = actions::example(attempted).unwrap();
        assert!(message.contains(&format!("A correct {attempted} action: {example}")), "{message}");
    }
    // Every example is itself a valid reply.
    for name in actions::ACTION_NAMES {
        let example = actions::example(name).unwrap();
        assert!(actions::parse_reply(example).is_ok(), "{example}");
    }
}

#[test]
fn the_number_of_corrections_is_configurable() {
    for (corrections, calls) in [(0, 1), (1, 2), (2, 3)] {
        let mut f = fake(vec!["no json".into(); 4]);
        let report = drive(&mut f, "wait for the screen", 1, &ballistic_core::run::Options { planner_corrections: corrections, ..harness() });
        assert_eq!(report["failure_category"], "planning");
        assert_eq!(f.count(ModelRole::Planner), calls, "{corrections}");
    }
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
