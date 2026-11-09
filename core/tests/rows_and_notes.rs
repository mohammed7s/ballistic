//! Typed postconditions for a deleted named row and a saved Markor note (postcondition.rs), on
//! screens recorded in the Opus 5.5 suite (`ballistic_runs/suite_opus55_b22da1f_20261109-003201/`,
//! 9 Nov 2026). In that run ExpenseDeleteSingle and MarkorCreateNote reached their goal state
//! (checker passed) but core did not accept completion. Variants that change recorded fields are
//! marked as derived. Scripted ports only; no model, emulator or benchmark database.
use ballistic_core::{agent, bridge::Bridge, postcondition::{self, Effect, Goal}, screen_filter::{self, FilterContext}, types::*};
use serde_json::{json, Value};
use std::io::Cursor;

fn fixture(name: &str) -> ScreenState {
    let text = match name {
        "expense_list" => include_str!("screens/postconditions/expense_delete_list.json"),
        "expense_detail" => include_str!("screens/postconditions/expense_delete_detail.json"),
        "expense_sheet" => include_str!("screens/postconditions/expense_delete_sheet.json"),
        "expense_after" => include_str!("screens/postconditions/expense_delete_after.json"),
        "markor_files" => include_str!("screens/postconditions/markor_files_empty.json"),
        "markor_empty" => include_str!("screens/postconditions/markor_editor_empty.json"),
        "markor_typed" => include_str!("screens/postconditions/markor_editor_typed.json"),
        "markor_saved" => include_str!("screens/postconditions/markor_editor_saved.json"),
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}

const DELETE: &str = "Delete the following expenses from pro expense: Taxi Fare.";
const CREATE: &str = "Create a new note in Markor named qFzW_polite_wolf.txt with the following text: Actions speak louder than words.";
const TEXT: &str = "Actions speak louder than words.";

fn completion(action: Value) -> Value { json!({"text": action.to_string()}) }
fn ok() -> Value { json!({"result":"ok"}) }
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate().map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let report = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), request, budget).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (report, calls)
}
fn count(calls: &[Value], f: impl Fn(&Value) -> bool) -> usize { calls.iter().filter(|c| f(c)).count() }
fn node_by_rid(s: &ScreenState, rid: &str) -> NodeId {
    s.nodes.iter().find(|n| n.resource_id.as_deref() == Some(rid)).unwrap_or_else(|| panic!("{rid}")).id
}
fn element_for(s: &ScreenState, request: &str, node: NodeId) -> String {
    let view = screen_filter::filter(s, &FilterContext { request, typed: &[] });
    format!("e{}", view.elements.iter().find(|e| e.node == node).unwrap().id)
}
fn tap(s: &ScreenState, request: &str, rid: &str) -> Value {
    completion(json!({"action":"tap","target":element_for(s, request, node_by_rid(s, rid))}))
}
fn effect(action: Action, before: &ScreenState, target: Option<NodeId>, confirmed: bool) -> Effect {
    Effect { action, before: before.clone(), target, confirmed, readback: None }
}
fn markor_apps() -> Vec<AppEntry> { vec![AppEntry { label: "Markor".into(), package: "net.gsantner.markor".into(), activity: None }] }

// ---- recognition -----------------------------------------------------------------------------

#[test]
fn delete_and_create_forms_are_recognized_with_the_owners_names() {
    assert_eq!(postcondition::recognize(DELETE, &[]), Some(Goal::RowsDeleted { names: vec!["Taxi Fare".into()] }));
    assert_eq!(postcondition::recognize("Delete the note in Markor named qFzW_polite_wolf.", &[]),
        Some(Goal::RowsDeleted { names: vec!["qFzW_polite_wolf".into()] }));
    assert_eq!(postcondition::recognize("Delete the following recipes from Broccoli app: Pasta Bake, Lentil Soup and Chili.", &[]),
        Some(Goal::RowsDeleted { names: vec!["Pasta Bake".into(), "Lentil Soup".into(), "Chili".into()] }));
    assert_eq!(postcondition::recognize(CREATE, &markor_apps()),
        Some(Goal::NoteSaved { name: "qFzW_polite_wolf.txt".into(), text: TEXT.into() }));
    for other in ["Delete the following expenses from pro expense and send them: Taxi Fare.",
        "Delete Taxi Fare.", "Delete the note named x and then open Settings: y",
        "Delete the following expenses from pro expense: Taxi Fare. Then add Rent.",
        "Delete the file sure_ant_2023_09_19.mp3 from the Android filesystem located in the Music folder."] {
        assert!(!matches!(postcondition::recognize(other, &[]), Some(Goal::RowsDeleted { .. })), "{other}");
    }
    // Markor must be the installed app the request names.
    assert_eq!(postcondition::recognize(CREATE, &[]), None);
}

// ---- ExpenseDeleteSingle ---------------------------------------------------------------------

#[test]
fn recorded_expense_delete_completes_when_the_list_shows_the_row_gone() {
    // Recorded: row, Delete expense (confirmed), CONFIRM, then the list without the row; the
    // planner then read facts, claimed done (rejected) and gave up. Core now checks the list.
    let (list, detail, sheet, after) = (fixture("expense_list"), fixture("expense_detail"), fixture("expense_sheet"), fixture("expense_after"));
    let (report, calls) = run(DELETE, vec![json!(list.clone()),
        tap(&list, DELETE, "com.arduia.expense:id/cd_expense"), ok(), json!(detail.clone()),
        tap(&detail, DELETE, "com.arduia.expense:id/btn_delete"), json!("approved"), ok(), json!(sheet.clone()),
        tap(&sheet, DELETE, "com.arduia.expense:id/btn_confirm"), ok(), json!(after)], 10);
    assert_eq!(report["done"], true, "{report}");
    assert_eq!(report["verification"]["evidence_basis"], "code_validated_rows_removed");
    assert_eq!(count(&calls, |c| c["ask"] == "confirm"), 1, "the delete stays guarded");
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
    assert_eq!(report["steps"], 3);
}

#[test]
fn row_gone_needs_the_confirmed_input_the_same_page_and_the_row_really_gone() {
    let (list, detail, sheet, after) = (fixture("expense_list"), fixture("expense_detail"), fixture("expense_sheet"), fixture("expense_after"));
    let goal = postcondition::recognize(DELETE, &[]).unwrap();
    let row = node_by_rid(&list, "com.arduia.expense:id/cd_expense");
    let path = |confirmed: bool| vec![effect(Action::Tap { target: 1 }, &list, Some(row), false),
        effect(Action::Tap { target: 2 }, &detail, None, confirmed), effect(Action::Tap { target: 3 }, &sheet, None, false)];
    let assess = |effects: &[Effect], fresh: &ScreenState| postcondition::assess(&goal, effects, &sheet, fresh, 0, &[]).verification.status;
    assert_eq!(assess(&path(true), &after), VerificationStatus::Verified);
    // No owner-confirmed input after the row was seen.
    assert_eq!(assess(&path(false), &after), VerificationStatus::Unknown);
    // The row was never seen on a list in this run.
    assert_eq!(assess(&path(true)[1..], &after), VerificationStatus::Unknown);
    // The list still shows it (the delete was cancelled).
    assert_eq!(assess(&path(true), &list), VerificationStatus::Unknown);
    // Another page of the same activity (the detail sheet, the confirmation sheet) is not the list.
    assert_eq!(assess(&path(true), &detail), VerificationStatus::Unknown);
    assert_eq!(assess(&path(true), &sheet), VerificationStatus::Unknown);
    // Derived: the list shows a different row instead (another expense was deleted, or the list
    // moved): the rows seen with the named one must stay in order, and with none seen, none may show.
    let mut moved = list.clone();
    let name = moved.nodes.iter_mut().find(|n| n.resource_id.as_deref() == Some("com.arduia.expense:id/tv_name")).unwrap();
    name.text = Some("Groceries".into());
    assert_eq!(assess(&path(true), &moved), VerificationStatus::Unknown);
    // Another name in the request that was never seen keeps the check from passing.
    let two = postcondition::recognize("Delete the following expenses from pro expense: Taxi Fare, Rent.", &[]).unwrap();
    assert_eq!(postcondition::assess(&two, &path(true), &sheet, &after, 0, &[]).verification.status, VerificationStatus::Unknown);
}

// ---- MarkorCreateNote ------------------------------------------------------------------------

/// Derived: the recorded empty file list with one file row titled `title`, in the recorded
/// caption form ("File <title> "), next to the recorded folder rows.
fn files_with(title: &str) -> ScreenState {
    let mut s = fixture("markor_files");
    let rows: Vec<Node> = s.nodes.iter().filter(|n| n.resource_id.as_deref() == Some("net.gsantner.markor:id/opoc_filesystem_item__root")).cloned().collect();
    let mut row = rows.last().unwrap().clone();
    row.id = 9001;
    row.label = Some(format!("File {title} "));
    row.bounds.y1 += 144; row.bounds.y2 += 144;
    s.nodes.push(row);
    s
}

fn note_effects(saved: bool) -> Vec<Effect> {
    let (files, empty, typed, saved_screen) = (fixture("markor_files"), fixture("markor_empty"), fixture("markor_typed"), fixture("markor_saved"));
    let editor = node_by_rid(&empty, "net.gsantner.markor:id/document__fragment__edit__highlighting_editor");
    let mut effects = vec![effect(Action::Tap { target: 9 }, &files, None, false),
        effect(Action::Type { target: 8, text: TEXT.into(), submit: false }, &empty, Some(editor), false),
        effect(Action::Tap { target: 5 }, &typed, None, false)];
    if saved { effects.push(effect(Action::Back, &saved_screen, None, false)); }
    effects
}

#[test]
fn a_saved_note_completes_when_the_file_list_shows_its_full_name() {
    let goal = postcondition::recognize(CREATE, &markor_apps()).unwrap();
    let assess = |effects: &[Effect], fresh: &ScreenState| postcondition::assess(&goal, effects, &fixture("markor_saved"), fresh, 0, &markor_apps()).verification.status;
    assert_eq!(assess(&note_effects(true), &files_with("qFzW_polite_wolf.txt")), VerificationStatus::Verified);
    // Recorded end of the Opus run: still in the editor, the list never shown.
    assert_eq!(assess(&note_effects(true), &fixture("markor_saved")), VerificationStatus::Unknown);
    // No screen with the text saved (Save still enabled when the editor was left).
    assert_eq!(assess(&note_effects(false), &files_with("qFzW_polite_wolf.txt")), VerificationStatus::Unknown);
    // Another extension, or the bare stem, is not the requested file name.
    assert_eq!(assess(&note_effects(true), &files_with("qFzW_polite_wolf.md")), VerificationStatus::Unknown);
    assert_eq!(assess(&note_effects(true), &files_with("qFzW_polite_wolf")), VerificationStatus::Unknown);
    // Other text typed: not the requested content.
    let other = postcondition::recognize(&CREATE.replace("louder", "softer"), &markor_apps()).unwrap();
    assert_eq!(postcondition::assess(&other, &note_effects(true), &fixture("markor_saved"), &files_with("qFzW_polite_wolf.txt"), 0, &markor_apps()).verification.status,
        VerificationStatus::Unknown);
    // A file with that name already listed before the text was typed is not the new note.
    let mut early = note_effects(true);
    early[0].before = files_with("qFzW_polite_wolf.txt");
    assert_eq!(assess(&early, &files_with("qFzW_polite_wolf.txt")), VerificationStatus::Unknown);
}

#[test]
fn a_failed_note_check_says_where_core_looks_and_asks_no_verifier() {
    let saved = fixture("markor_saved");
    let mut apps_screen = saved.clone();
    apps_screen.installed_apps = markor_apps();
    let (report, calls) = run(CREATE, vec![json!(apps_screen), completion(json!({"action":"done","evidence":"typed"})), json!(saved)], 1);
    assert_eq!(report["done"], false);
    assert_eq!(count(&calls, |c| c["role"] == "verifier"), 0);
    let event = calls.iter().find(|c| c["ask"] == "log").unwrap();
    assert!(event["event"]["outcome"].as_str().unwrap().starts_with("core checks a saved note"), "{event}");
}
