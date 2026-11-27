//! Owner field values (core/README.md §10, "Owner field values", loop 3, 16 Nov 2026).
//!
//! A request that lists field values gives values the owner wants entered exactly:
//!
//!   lines   two or more lines `key: value` ("servings: 1 serving"), the key one to three words
//!   table   a header row of keys separated by `|` ("name|amount_dollars|note"), then rows of
//!           values with the same number of cells ("Taxi Fare|$179.68|A need")
//!
//! Before core accepts any done claim (a verifier's or a typed check's) in a run that entered
//! text, each value must have been entered in this run, checked in code:
//!
//!   typed     an acknowledged `type`, or a string a typed operation filled in, equal to the
//!             value with whitespace collapsed; a value that is exactly one amount or number also
//!             matches a typed amount or number with the same decimal value ("$179.68", "179.68")
//!   selected  a tap or long press on a control whose own strings are exactly the value (a
//!             category chip)
//!   shown     an editable field showing exactly the value on a screen this run planned an input
//!             on, or (for the done gate only) any element showing exactly the value on the fresh
//!             screen the done claim is checked on
//!
//! Recorded case: the intents run of 15 Nov 2026, RecipeAddSingleRecipe. The request said
//! "servings: 1 serving", the planner typed "1" and the verifier accepted the saved recipe; the
//! benchmark state failed. Nothing here reads content into a prompt: values come from the
//! owner's request, and screen strings are compared in code only.

use crate::{postcondition::Effect, types::*, values::{self, ValueKind}};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Field {
    pub key: String,
    pub value: String,
}

const MAX_VALUE_CHARS: usize = 2000;

/// Whitespace collapsed to single spaces, trimmed.
pub fn norm(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// A field key: one to three words of letters, digits, `_` or `-`, starting with a letter.
fn key_ok(k: &str) -> bool {
    let k = k.trim();
    !k.is_empty() && k.chars().count() <= 40 && k.starts_with(|c: char| c.is_ascii_alphabetic())
        && k.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | ' '))
        && k.split_whitespace().count() <= 3
}

fn value_ok(v: &str) -> bool {
    let v = v.trim();
    !v.is_empty() && v.chars().count() <= MAX_VALUE_CHARS && !v.chars().any(char::is_control)
}

/// The field values the owner's request lists, in order. Empty unless there are at least two
/// `key: value` lines or a `|` table with at least one row of values.
pub fn owner_fields(request: &str) -> Vec<Field> {
    let lines: Vec<&str> = request.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
    if lines.len() < 2 { return vec![]; }
    let mut out = Vec::new();
    let mut i = 0;
    while i < lines.len() {
        let cells: Vec<&str> = lines[i].split('|').map(str::trim).collect();
        if cells.len() >= 2 && cells.iter().all(|c| key_ok(c)) {
            let mut j = i + 1;
            while j < lines.len() {
                let row: Vec<&str> = lines[j].split('|').map(str::trim).collect();
                if row.len() != cells.len() || !row.iter().all(|v| value_ok(v)) { break; }
                out.extend(cells.iter().zip(&row).map(|(k, v)| Field { key: k.to_string(), value: norm(v) }));
                j += 1;
            }
            if j > i + 1 { i = j; continue; }
        }
        i += 1;
    }
    let pairs: Vec<Field> = lines.iter().filter(|l| !l.contains('|')).filter_map(|l| {
        let (k, v) = l.split_once(':')?;
        (key_ok(k) && value_ok(v)).then(|| Field { key: k.trim().to_string(), value: norm(v) })
    }).collect();
    if pairs.len() >= 2 { out.extend(pairs); }
    out
}

/// One record: no key twice (a single recipe, one table row).
pub fn single_record(fields: &[Field]) -> bool {
    let mut keys: Vec<String> = fields.iter().map(|f| f.key.to_ascii_lowercase()).collect();
    keys.sort();
    let n = keys.len();
    keys.dedup();
    n >= 2 && keys.len() == n
}

/// `typed` is the same value as `value`: equal with whitespace collapsed, or both exactly one
/// amount or number with the same decimal value.
pub fn same_value(value: &str, typed: &str) -> bool {
    if norm(value) == norm(typed) { return true; }
    let kinds = [ValueKind::Amount, ValueKind::Number];
    match (values::single(value.trim(), &kinds), values::single(typed.trim(), &kinds)) {
        (Some(a), Some(b)) => a.key() == b.key() && !a.key().starts_with("t:"),
        _ => false,
    }
}

fn node(s: &ScreenState, id: NodeId) -> Option<&Node> { s.nodes.iter().find(|n| n.id == id) }

/// Every non-empty label and text in a node's subtree.
fn subtree_strings(s: &ScreenState, id: NodeId) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![id];
    while let Some(i) = stack.pop() {
        if let Some(n) = node(s, i) {
            out.extend(n.label.iter().chain(n.text.iter()).map(|t| norm(t)).filter(|t| !t.is_empty()));
        }
        stack.extend(s.nodes.iter().filter(|n| n.parent == Some(i)).map(|n| n.id));
    }
    out
}

/// An editable field showing exactly `value` (its text, not merely its hint).
fn field_shows(s: &ScreenState, value: &str) -> bool {
    s.nodes.iter().any(|n| n.flags.editable && n.flags.visible
        && n.text.as_deref().is_some_and(|t| norm(t) == value && n.label.as_deref().map(norm).as_deref() != Some(value)))
}

/// How a value was entered in this run, if it was.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Entered {
    /// The index of the effect that typed or selected it (`None` for a typed operation's
    /// filled-in string, which has no target field).
    Typed(Option<usize>),
    Selected(usize),
    Shown(usize),
}

impl Entered {
    pub fn at(self) -> Option<usize> {
        match self { Entered::Typed(i) => i, Entered::Selected(i) | Entered::Shown(i) => Some(i) }
    }
}

/// Where `value` was entered in this run: typed, selected, or shown in an editable field on a
/// screen an input was planned on.
pub fn entered(value: &str, typed: &[String], effects: &[Effect]) -> Option<Entered> {
    let value = norm(value);
    if let Some(i) = effects.iter().rposition(|e| matches!(&e.action, Action::Type { text, .. } if same_value(&value, text))) {
        return Some(Entered::Typed(Some(i)));
    }
    if typed.iter().any(|t| same_value(&value, t)) { return Some(Entered::Typed(None)); }
    if let Some(i) = effects.iter().rposition(|e| matches!(e.action, Action::Tap { .. } | Action::LongPress { .. })
        && e.target.is_some_and(|t| node(&e.before, t).is_some_and(|n| !n.flags.editable) && {
            let strings = subtree_strings(&e.before, t);
            !strings.is_empty() && strings.iter().all(|s| *s == value)
        })) {
        return Some(Entered::Selected(i));
    }
    effects.iter().rposition(|e| field_shows(&e.before, &value)).map(Entered::Shown)
}

/// The owner values not entered in this run. With `fresh`, a value an element on the fresh screen
/// shows exactly also counts (a saved entry's details).
pub fn missing<'a>(fields: &'a [Field], typed: &[String], effects: &[Effect], fresh: Option<&ScreenState>) -> Vec<&'a Field> {
    fields.iter().filter(|f| {
        if entered(&f.value, typed, effects).is_some() { return false; }
        !fresh.is_some_and(|s| s.nodes.iter().any(|n| n.flags.visible && n.text.as_deref().is_some_and(|t| norm(t) == f.value)))
    }).collect()
}

/// Fixed feedback naming the owner's own keys and values (never screen text).
pub fn feedback(missing: &[&Field]) -> String {
    let named: Vec<String> = missing.iter().take(4).map(|f| {
        let v: String = f.value.chars().take(60).collect();
        let more = if f.value.chars().count() > 60 { "…" } else { "" };
        format!("{} {:?}", f.key, format!("{v}{more}"))
    }).collect();
    let rest = if missing.len() > 4 { format!(" and {} more", missing.len() - 4) } else { String::new() };
    format!("done not accepted: the request gives values that were not entered exactly as written in this run: {}{rest}. \
        Enter each value exactly as the request writes it, words and units included (\"1 serving\", not \"1\"); \
        type adds to text already in a field", named.join(", "))
}

#[cfg(test)]
mod tests {
    use super::*;

    const RECIPE: &str = "Add the following recipes into the Broccoli app:\nRecipe: Chicken Alfredo Pasta\n description: A delicious and healthy choice for any time of the day.\n servings: 1 serving\n preparationTime: 1 hrs\n ingredients: n/a\n directions: Cook fettuccine pasta, toss with Alfredo sauce.\n";
    const EXPENSE: &str = "Add the following expenses into the pro expense:\nname|amount_dollars|category_name|note\nTaxi Fare|$179.68|Transportation|A need";

    #[test]
    fn lines_and_tables_are_fields() {
        let f = owner_fields(RECIPE);
        assert_eq!(f.len(), 6);
        assert_eq!(f[0], Field { key: "Recipe".into(), value: "Chicken Alfredo Pasta".into() });
        assert_eq!(f[2], Field { key: "servings".into(), value: "1 serving".into() });
        assert!(single_record(&f));
        let e = owner_fields(EXPENSE);
        assert_eq!(e.iter().map(|f| f.value.as_str()).collect::<Vec<_>>(), ["Taxi Fare", "$179.68", "Transportation", "A need"]);
        assert_eq!(e[1].key, "amount_dollars");
        assert!(single_record(&e));
        let two = owner_fields("Add the following expenses:\nname|amount\nA|$1\nB|$2");
        assert_eq!(two.len(), 4);
        assert!(!single_record(&two));
    }

    #[test]
    fn single_line_requests_and_one_pair_are_not_fields() {
        for r in ["Send a text message using Simple SMS Messenger to +16604876475 with message: The squeaky wheel gets the grease.",
            "Create a new note in Markor named a.txt with the following text: Actions speak louder than words.",
            "Turn wifi off.", "Add the following:\nNote: be quick", "Do this:\nfirst step and then the second step: now"] {
            assert!(owner_fields(r).is_empty(), "{r}");
        }
    }

    #[test]
    fn values_compare_exactly_except_amounts_and_numbers() {
        assert!(same_value("1 serving", "1 serving"));
        assert!(same_value("1 serving", "  1   serving "));
        assert!(!same_value("1 serving", "1"));
        assert!(!same_value("1 serving", "1 Serving") );
        assert!(same_value("$179.68", "179.68"));
        assert!(same_value("$179.68", "$179.680"));
        assert!(!same_value("$179.68", "179.6"));
        assert!(!same_value("1 hrs", "1"));
        assert!(same_value("12", "12"));
    }

    #[test]
    fn feedback_names_the_owner_values() {
        let f = owner_fields(RECIPE);
        let text = feedback(&[&f[2]]);
        assert!(text.contains("servings \"1 serving\""), "{text}");
    }
}
