//! Owner field values (fields.rs) and the saved-entry check (postcondition.rs, EntrySaved), on
//! screens recorded on the emulator (`tests/screens/loop3/`, extracted by step):
//!
//!   recipe_add_intents.json  intents run, 15 Nov 2026, RecipeAddSingleRecipe: the request said
//!                            "servings: 1 serving", the planner typed "1", saved, and the verifier
//!                            accepted the recipe; the benchmark state failed (the false done)
//!   expense_add.json         loop 2 run, 15 Nov 2026, ExpenseAddSingle: every value entered and
//!                            saved (state passed), then the planner spent its budget on reads
//!
//! Variants that change one recorded field are marked as derived. Scripted ports only.
use ballistic_core::{actions, agent, bridge::Bridge, fields, postcondition::{self, Effect, Goal}, screen_filter::{self, FilterContext}, types::*};
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
fn recipe() -> Recorded { load(include_str!("screens/loop3/recipe_add_intents.json")) }
fn expense() -> Recorded { load(include_str!("screens/loop3/expense_add.json")) }

fn completion(action: &Action) -> Value {
    let mut a = actions::to_json(action);
    a["why"] = json!("recorded");
    json!({"text": a.to_string()})
}
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn count(calls: &[Value], f: impl Fn(&Value) -> bool) -> usize { calls.iter().filter(|c| f(c)).count() }

/// Replies for a run of the recorded inputs, each planned on its recorded screen; the observation
/// core takes after each input (its typed check) is the next recorded screen, then `after`.
fn scripted(r: &Recorded, then: Vec<Value>) -> Vec<Value> {
    let mut replies = vec![json!(r.steps[0].1)];
    for (i, (action, _)) in r.steps.iter().enumerate() {
        replies.push(completion(action));
        replies.push(json!({"result":"ok"}));
        replies.push(json!(r.steps.get(i + 1).map_or(&r.after, |s| &s.1)));
    }
    replies.extend(then);
    replies
}

/// The recorded inputs as effects, targets resolved on the planner's view of each screen.
fn effects(r: &Recorded) -> Vec<Effect> {
    let mut typed: Vec<String> = Vec::new();
    r.steps.iter().map(|(action, screen)| {
        let view = screen_filter::filter(screen, &FilterContext { request: &r.request, typed: &typed });
        let target = match action { Action::Tap { target } | Action::Type { target, .. } => view.element(*target).map(|e| e.node), _ => None };
        if let Action::Type { text, .. } = action { typed.push(text.clone()); }
        Effect { action: action.clone(), before: screen.clone(), target, confirmed: false, readback: None }
    }).collect()
}

fn set_text(s: &mut ScreenState, from: &str, to: &str) {
    let n = s.nodes.iter_mut().find(|n| n.text.as_deref() == Some(from)).unwrap_or_else(|| panic!("{from}"));
    n.text = Some(to.into());
}

// ---- the recorded false done -------------------------------------------------------------------

#[test]
fn recorded_recipe_with_a_shortened_servings_value_is_not_done() {
    let r = recipe();
    let goal = postcondition::recognize(&r.request, &[]).unwrap();
    assert!(matches!(goal, Goal::EntrySaved { ref fields } if fields.len() == 6));
    let fx = effects(&r);
    assert_eq!(postcondition::assess(&goal, &fx, &r.steps.last().unwrap().1, &r.after, 0, &[]).verification.status, VerificationStatus::Unknown);
    let owner = fields::owner_fields(&r.request);
    let missing = fields::missing(&owner, &["1".into()], &fx, Some(&r.after));
    assert_eq!(missing.iter().map(|f| f.value.as_str()).collect::<Vec<_>>(), ["1 serving"]);

    // The whole recorded path, then the recorded done claim, and a verifier that accepts it (as
    // the recorded one did). Core rejects it in code and names the value.
    let done = Action::Done { evidence: "recorded".into() };
    let verifier = json!({"text": json!({"status":"verified","nodes":["e7","e9"],"evidence_basis":"visible_ui","reason":"ok"}).to_string()});
    let budget = r.steps.len() as u32 + 1;
    let (report, calls) = run(&r.request, scripted(&r, vec![completion(&done), json!(r.after), verifier]), budget);
    assert_eq!(report["done"], false, "{report}");
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 1);
    let last = calls.iter().rev().find(|c| c["ask"] == "log").unwrap();
    let outcome = last["event"]["outcome"].as_str().unwrap();
    assert!(outcome.contains("servings \"1 serving\""), "{outcome}");
    assert_eq!(last["event"]["verification"]["status"], "unknown");
}

#[test]
fn recipe_with_every_value_entered_completes_in_code_at_the_save() {
    // Derived: servings typed as written, and the saved details show it.
    let mut r = recipe();
    let i = r.steps.iter().position(|(a, _)| matches!(a, Action::Type { text, .. } if text == "1")).unwrap();
    if let Action::Type { text, .. } = &mut r.steps[i].0 { *text = "1 serving".into(); }
    set_text(&mut r.after, "1", "1 serving");
    let (report, calls) = run(&r.request, scripted(&r, vec![]), r.steps.len() as u32);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_saved_entry");
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
    assert_eq!(report["steps"], r.steps.len());
}

// ---- ExpenseAddSingle ----------------------------------------------------------------------------

#[test]
fn recorded_expense_completes_at_the_save() {
    // Recorded: name, amount "179.68" for "$179.68", the Transportation chip, the note, SAVE; the
    // list then shows the new row. The planner spent three more steps on reads and ran out.
    let r = expense();
    let (report, calls) = run(&r.request, scripted(&r, vec![]), r.steps.len() as u32);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_saved_entry");
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
}

#[test]
fn saved_entry_needs_every_value_the_save_and_a_closed_form() {
    let r = expense();
    let goal = postcondition::recognize(&r.request, &[]).unwrap();
    let fx = effects(&r);
    let assess = |fx: &[Effect], fresh: &ScreenState| postcondition::assess(&goal, fx, &fx.last().unwrap().before, fresh, 0, &[]).verification.status;
    assert_eq!(assess(&fx, &r.after), VerificationStatus::Verified);
    // No category chip tapped.
    let mut no_chip = fx.clone();
    no_chip.remove(3); // step 7, the Transportation chip
    assert_eq!(assess(&no_chip, &r.after), VerificationStatus::Unknown);
    // Derived: another amount typed.
    let mut other = fx.clone();
    for e in &mut other { if let Action::Type { text, .. } = &mut e.action { if text == "179.68" { *text = "17.968".into(); } } }
    assert_eq!(assess(&other, &r.after), VerificationStatus::Unknown);
    // No save: the form screen itself is not a saved entry.
    assert_eq!(assess(&fx[..fx.len() - 1], &r.after), VerificationStatus::Unknown);
    assert_eq!(assess(&fx, &fx.last().unwrap().before), VerificationStatus::Unknown);
    // Derived: the list shows no row with the name.
    let mut gone = r.after.clone();
    set_text(&mut gone, "Taxi Fare", "Groceries");
    assert_eq!(assess(&fx, &gone), VerificationStatus::Unknown);
    // Derived: the name was already on the list before the entry began.
    let mut seen = fx.clone();
    seen[0].before = r.after.clone();
    assert_eq!(assess(&seen, &r.after), VerificationStatus::Unknown);
}

#[test]
fn only_add_requests_with_one_record_are_entries() {
    let r = expense();
    assert!(matches!(postcondition::recognize(&r.request, &[]), Some(Goal::EntrySaved { .. })));
    for other in ["Delete the following expenses from pro expense:\nname|amount_dollars\nTaxi Fare|$1",
        "Add the following expenses into the pro expense:\nname|amount_dollars\nTaxi Fare|$1\nRent|$2",
        "Add the following expenses into the pro expense: Taxi Fare"] {
        assert!(!matches!(postcondition::recognize(other, &[]), Some(Goal::EntrySaved { .. })), "{other}");
    }
}
