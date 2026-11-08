//! The planner's reply → a checked `Action` → what Hands executes (core/README.md §6).
//!
//!   parse_reply   last JSON object in the text (tolerates a ```json fence and prose around it)
//!   validate      targets exist in the filtered view and have the right flags
//!   resolve       element ids → screen coordinates; app names → packages
//!
//! The JSON shape is described for other hosts in `core/schema/actions.json`; a test below keeps
//! the two in step.

use serde_json::Value;

use crate::screen_filter::FilteredScreen;
use crate::types::*;

/// The action schema, shared with every host.
pub const ACTIONS_SCHEMA: &str = include_str!("../schema/actions.json");

/// Every action name the planner may emit, in the order of §6.
pub const ACTION_NAMES: &[&str] = &[
    "open_app", "tap", "long_press", "type", "scroll", "swipe", "back", "home", "wait", "read",
    "answer", "done", "give_up",
];

/// How long `wait` waits.
pub const WAIT_MILLIS: u32 = 1500;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ActionError {
    #[error("no JSON object found in the reply")]
    NoJson,
    #[error("the JSON object has no \"action\" field")]
    NoAction,
    #[error("unknown action {0:?}; allowed: {list}", list = ACTION_NAMES.join(", "))]
    UnknownAction(String),
    #[error("bad arguments for {action}: {detail}")]
    BadArguments { action: String, detail: String },
    #[error("e{0} is not on this screen")]
    NoSuchElement(u32),
    #[error("e{0} is not an editable field; type requires [editable]. Use the visible input controls; a display is not a text field")]
    NotEditable(u32),
    #[error("e{0} is not scrollable; give an element marked [scrollable] or no target to scroll the whole screen")]
    NotScrollable(u32),
    #[error("e{0} is disabled")]
    Disabled(u32),
    #[error("e{0} does not support this action")]
    NotActionable(u32),
    #[error("e{0} has no identified control or matched item; hidden content and generic resource ids cannot authorize a guessed target")]
    Unidentified(u32),
    #[error("e{0} has a current reader pick authorizing tap only; long_press remains unsupported for this target. Use current permitted controls")]
    ReaderTapOnly(u32),
    #[error("no installed app matches {0:?}")]
    UnknownApp(String),
    #[error("c{0} is not on this screen")]
    NoSuchContent(u32),
    #[error("{0} must not be empty")]
    Empty(&'static str),
}

/// A parsed reply: the action and the planner's short reason (logged, not executed).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannerReply {
    pub action: Action,
    pub why: String,
}

/// Every top-level `{...}` in `text`, in order, respecting JSON strings and escapes.
fn json_objects(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let (mut depth, mut start, mut in_str, mut esc) = (0usize, 0usize, false, false);
    for (i, c) in text.char_indices() {
        if in_str {
            match c {
                _ if esc => esc = false,
                '\\' => esc = true,
                '"' => in_str = false,
                _ => {}
            }
            continue;
        }
        match c {
            '"' if depth > 0 => in_str = true,
            '{' => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            '}' if depth > 0 => {
                depth -= 1;
                if depth == 0 {
                    out.push(&text[start..=i]);
                }
            }
            _ => {}
        }
    }
    out
}

/// Planner ids use the schema's canonical eN/cN form, with positive u32 ids.
fn id_value(v: &Value, prefix: char) -> Option<Value> {
    let s = v.as_str()?.strip_prefix(prefix)?;
    if s.starts_with('0') || !s.bytes().all(|b| b.is_ascii_digit()) { return None; }
    s.parse::<u32>().ok().filter(|id| *id > 0).map(Value::from)
}

/// Parse the planner's reply. Takes the last JSON object that has an `action` field.
pub fn parse_reply(text: &str) -> Result<PlannerReply, ActionError> {
    let objects: Vec<serde_json::Map<String, Value>> = json_objects(text)
        .into_iter()
        .filter_map(|s| match serde_json::from_str::<Value>(s) {
            Ok(Value::Object(m)) => Some(m),
            _ => None,
        })
        .collect();
    if objects.is_empty() {
        return Err(ActionError::NoJson);
    }
    let mut obj = objects
        .into_iter()
        .rev()
        .find(|m| m.contains_key("action"))
        .ok_or(ActionError::NoAction)?;
    let name = obj
        .get("action")
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or(ActionError::NoAction)?;
    if !ACTION_NAMES.contains(&name.as_str()) {
        return Err(ActionError::UnknownAction(name));
    }
    obj.insert("action".into(), Value::from(name.clone()));
    let bad = |detail: String| ActionError::BadArguments { action: name.clone(), detail };
    let schema: Value = serde_json::from_str(ACTIONS_SCHEMA).expect("compiled action schema");
    let variant = schema["oneOf"].as_array().unwrap().iter()
        .find(|v| v["properties"]["action"]["const"] == name).unwrap();
    for field in obj.keys() {
        if variant["properties"].get(field).is_none() {
            return Err(bad(format!("unknown field {field:?}")));
        }
    }
    let why = match obj.remove("why") {
        None => String::new(),
        Some(Value::String(s)) => s,
        Some(_) => return Err(bad("why must be a string".into())),
    };
    if let Some(t) = obj.get("target").cloned() {
        if t.is_null() && name == "scroll" {
            obj.remove("target");
        } else {
            let id = id_value(&t, 'e').ok_or_else(|| bad(format!("target {t} is not an element id like \"e7\"")))?;
            obj.insert("target".into(), id);
        }
    }
    if let Some(Value::Array(ts)) = obj.get("targets").cloned() {
        let ids: Option<Vec<Value>> = ts.iter().map(|t| id_value(t, 'c')).collect();
        let ids = ids.ok_or_else(|| bad("targets must be content ids like \"c4\"".into()))?;
        obj.insert("targets".into(), Value::Array(ids));
    }
    let action: Action = serde_json::from_value(Value::Object(obj)).map_err(|e| bad(e.to_string()))?;
    Ok(PlannerReply { action, why })
}

/// Check an action against the screen the planner saw.
pub fn validate(action: &Action, view: &FilteredScreen) -> Result<(), ActionError> {
    let element = |id: u32| {
        let e = view.element(id).ok_or(ActionError::NoSuchElement(id))?;
        if !e.flags.enabled { return Err(ActionError::Disabled(id)); }
        Ok(e)
    };
    let identified = |id: u32| {
        let e = element(id)?;
        // Match-only item titles support selection, but never identify an unknown toggle,
        // slider or input control. A resource id or file_kind by itself is not an identity.
        let matched_item = e.package != "com.android.systemui" && !e.shown_content.is_empty() && !e.flags.checkable
            && !e.flags.editable && e.role != Role::Slider;
        if e.label.is_none() && !matched_item { return Err(ActionError::Unidentified(id)); }
        Ok(e)
    };
    match action {
        Action::OpenApp { app } if app.trim().is_empty() => Err(ActionError::Empty("app")),
        Action::Tap { target } if view.reader_picks.contains(target) => {
            // A reader pick identifies a row or toggle for a tap only, never a field or slider,
            // and never in the notification shade.
            let e = element(*target)?;
            if e.package == "com.android.systemui" || e.flags.editable || e.role == Role::Slider {
                return Err(ActionError::Unidentified(*target));
            }
            if e.flags.clickable || e.flags.checkable { Ok(()) } else { Err(ActionError::NotActionable(*target)) }
        }
        Action::Tap { target } => {
            let e = identified(*target)?;
            if e.flags.clickable || e.flags.editable || e.flags.checkable { Ok(()) }
            else { Err(ActionError::NotActionable(*target)) }
        }
        Action::LongPress { target } => {
            let e = identified(*target).map_err(|error| {
                // Explain only an existing identity rejection. Independently identified
                // controls keep their authority, even if also picked. Require the original
                // tap validation to pass; a pick alone does not authorize every target.
                if matches!(error, ActionError::Unidentified(_))
                    && view.reader_picks.contains(target)
                    && view.element(*target).is_some_and(|e| e.flags.long_clickable)
                    && validate(&Action::Tap { target: *target }, view).is_ok()
                {
                    ActionError::ReaderTapOnly(*target)
                } else {
                    error
                }
            })?;
            if e.flags.long_clickable { Ok(()) }
            else { Err(ActionError::NotActionable(*target)) }
        }
        Action::Swipe { target, .. } => {
            let e = element(*target)?;
            if e.role == Role::Slider { identified(*target)?; }
            if e.flags.scrollable || e.role == Role::Slider { Ok(()) }
            else { Err(ActionError::NotActionable(*target)) }
        }
        Action::Type { target, .. } => {
            if identified(*target)?.flags.editable {
                Ok(())
            } else {
                Err(ActionError::NotEditable(*target))
            }
        }
        Action::Scroll { target: Some(t), .. } => {
            if element(*t)?.flags.scrollable {
                Ok(())
            } else {
                Err(ActionError::NotScrollable(*t))
            }
        }
        Action::Read { targets, question, .. } => {
            if targets.is_empty() {
                return Err(ActionError::Empty("targets"));
            }
            if question.trim().is_empty() {
                return Err(ActionError::Empty("question"));
            }
            match targets.iter().find(|c| view.content(**c).is_none()) {
                Some(c) => Err(ActionError::NoSuchContent(*c)),
                None => Ok(()),
            }
        }
        _ => Ok(()),
    }
}

/// `b` clipped to the screen, so the centre of a half-visible element is a point on screen.
fn clip(b: Bounds, screen: &ScreenState) -> Bounds {
    if screen.width <= 0 || screen.height <= 0 {
        return b;
    }
    Bounds {
        x1: b.x1.clamp(0, screen.width),
        y1: b.y1.clamp(0, screen.height),
        x2: b.x2.clamp(0, screen.width),
        y2: b.y2.clamp(0, screen.height),
    }
}

/// Turn a validated action into what Hands executes. `None` for actions Hands never sees
/// (`read`, `answer`, `done`, `give_up`).
pub fn resolve(
    action: &Action,
    view: &FilteredScreen,
    screen: &ScreenState,
    apps: &[AppEntry],
) -> Result<Option<HandsOp>, ActionError> {
    validate(action, view)?;
    let bounds = |id: u32| -> Result<Bounds, ActionError> {
        Ok(clip(view.element(id).ok_or(ActionError::NoSuchElement(id))?.bounds, screen))
    };
    let whole_screen = Bounds { x1: 0, y1: 0, x2: screen.width, y2: screen.height };
    Ok(Some(match action {
        Action::OpenApp { app } => match match_app(app, apps) {
            Some(a) => HandsOp::OpenApp { package: a.package.clone(), activity: a.activity.clone() },
            None => return Err(ActionError::UnknownApp(app.clone())),
        },
        Action::Tap { target } => {
            let (x, y) = bounds(*target)?.centre();
            HandsOp::Tap { x, y }
        }
        Action::LongPress { target } => {
            let (x, y) = bounds(*target)?.centre();
            HandsOp::LongPress { x, y }
        }
        Action::Type { target, text, submit } => {
            let (x, y) = bounds(*target)?.centre();
            HandsOp::Type { x, y, text: text.clone(), submit: *submit }
        }
        Action::Scroll { target, direction } => HandsOp::Scroll {
            area: match target {
                Some(t) => bounds(*t)?,
                None => whole_screen,
            },
            direction: *direction,
        },
        Action::Swipe { target, direction } => HandsOp::Swipe { area: bounds(*target)?, direction: *direction },
        Action::Back => HandsOp::Back,
        Action::Home => HandsOp::Home,
        Action::Wait => HandsOp::Wait { millis: WAIT_MILLIS },
        Action::Read { .. } | Action::Answer { .. } | Action::Done { .. } | Action::GiveUp { .. } => {
            return Ok(None)
        }
    }))
}

fn app_words(s: &str) -> Vec<String> {
    s.split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(str::to_lowercase)
        .filter(|w| w != "app")
        .collect()
}

/// Find the installed app the planner means: exact label, then case-insensitive label or
/// package, then the label sharing the most words with the name (ties: a label that contains
/// the name as a substring, then the shortest label). `None` if nothing shares a word.
pub fn match_app<'a>(name: &str, apps: &'a [AppEntry]) -> Option<&'a AppEntry> {
    let name = name.trim();
    if let Some(a) = apps.iter().find(|a| a.label == name) {
        return Some(a);
    }
    let lower = name.to_lowercase();
    if let Some(a) = apps.iter().find(|a| a.label.to_lowercase() == lower || a.package.to_lowercase() == lower) {
        return Some(a);
    }
    let want = app_words(name);
    if want.is_empty() {
        return None;
    }
    apps.iter()
        .filter_map(|a| {
            let have = app_words(&a.label);
            let overlap = want.iter().filter(|w| have.contains(w)).count();
            let substring = a.label.to_lowercase().contains(&lower) || lower.contains(&a.label.to_lowercase());
            (overlap > 0 || substring).then_some((overlap, substring, std::cmp::Reverse(a.label.len()), a))
        })
        .max_by(|x, y| (x.0, x.1, x.2).cmp(&(y.0, y.1, y.2)))
        .map(|t| t.3)
}

/// Labels that make a tap a guarded action (§9). Matched as whole words against the target's
/// label and resource-id words, case-insensitive.
pub const CONFIRM_WORDS: &[&str] = &["send", "delete", "pay", "install", "remove", "post", "confirm purchase"];

/// Keys that remove one character from an unsaved input, recorded on the emulator image. Their
/// labels or ids say "delete", but they delete no stored data. Exact id, and the element must
/// belong to the package the id names.
const CHARACTER_DELETE_KEYS: &[&str] = &[
    "com.google.android.inputmethod.latin:id/key_pos_del",
    "com.google.android.deskclock:id/timer_setup_delete",
];

fn character_delete_key(e: &crate::screen_filter::Element) -> bool {
    e.resource_id.as_deref().is_some_and(|rid| CHARACTER_DELETE_KEYS.contains(&rid)
        && rid.split(":id/").next() == Some(e.package.as_str()))
}

/// Why this action needs the owner's yes before Hands runs it, or `None`.
pub fn confirmation_reason(action: &Action, view: &FilteredScreen) -> Option<String> {
    match action {
        Action::Type { submit: true, .. } => Some("type and submit".into()),
        Action::Tap { target } | Action::LongPress { target } => {
            let e = view.element(*target)?;
            if character_delete_key(e) {
                return None;
            }
            if view.reader_picks.contains(target) {
                // The reader chose this element from content core withheld. Code (no model)
                // checks that content for the guarded words; a hit needs the owner's yes.
                let text = e.content.and_then(|c| view.content(c)).map(|c| c.text.as_str()).unwrap_or("");
                let joined = format!(" {} ", app_words(text).join(" "));
                if let Some(w) = CONFIRM_WORDS.iter().find(|w| joined.contains(&format!(" {w} "))) {
                    return Some(format!("activate an element the reader chose whose content mentions {w:?}"));
                }
            }
            // The recorded keyboard action key can submit the same draft as Send. Its
            // caption may say Enter, Send, or something else; do not infer its effect.
            if matches!(e.resource_id.as_deref(),
                Some("com.google.android.inputmethod.latin:id/key_pos_ime_action")
                | Some("com.android.inputmethod.latin:id/key_pos_ime_action"))
                || e.label.as_deref().is_some_and(|l| l.eq_ignore_ascii_case("Enter")) {
                return Some("keyboard action may submit".into());
            }
            let mut words: Vec<String> = Vec::new();
            for s in [e.label.as_deref(), e.resource_id.as_deref().map(|r| r.rsplit(":id/").next().unwrap_or(r))]
                .into_iter()
                .flatten()
            {
                words.extend(app_words(s));
            }
            let joined = format!(" {} ", words.join(" "));
            CONFIRM_WORDS
                .iter()
                .find(|w| joined.contains(&format!(" {w} ")))
                .map(|w| format!("activate a control labelled {w:?}"))
        }
        _ => None,
    }
}

/// The taint rule (core/README.md §7 rule 2) for typed text: every number, phone number, amount
/// or link in it that is not in the owner's request or a control label on this screen, with
/// its source. `carried` values (in the request, but not the owner's: see `agent::Task`) keep
/// their tags even though the request contains them; reader `facts` keep theirs unless the
/// owner or a label also has the value; anything else is the planner's own text
/// (`model:planner`). Non-empty means a hard confirmation that names each value and source.
pub fn tainted_values(action: &Action, view: &FilteredScreen, request: &str, carried: &[Tagged], facts: &[Tagged]) -> Vec<Tagged> {
    let Action::Type { text, .. } = action else { return vec![] };
    let labels: Vec<&str> = view.elements.iter().filter_map(|e| e.label.as_deref()).collect();
    let provenance = crate::values::Provenance::new(&[request], &labels, facts);
    provenance.tag_with(text, carried, &Source::Model("planner".into())).into_iter()
        .filter(|(_, source)| !source.is_owner())
        .map(|(found, source)| Tagged { value: found.raw, source })
        .collect()
}

/// The confirmation wording for [`tainted_values`].
pub fn taint_reason(values: &[Tagged]) -> String {
    let named: Vec<String> = values.iter()
        .map(|v| format!("{:?} from {}", v.value, crate::reader::source_tag(&v.source))).collect();
    format!("type a value that is not in your request: {}", named.join(", "))
}

/// Short form for the history and logs: `tap e7`, `type e4 "Nora" submit`.
pub fn describe(action: &Action) -> String {
    let dir = |d: &Direction| format!("{d:?}").to_lowercase();
    match action {
        Action::OpenApp { app } => format!("open_app {app:?}"),
        Action::Tap { target } => format!("tap e{target}"),
        Action::LongPress { target } => format!("long_press e{target}"),
        Action::Type { target, text, submit } => {
            format!("type e{target} {text:?}{}", if *submit { " submit" } else { "" })
        }
        Action::Scroll { target: Some(t), direction } => format!("scroll e{t} {}", dir(direction)),
        Action::Scroll { target: None, direction } => format!("scroll {}", dir(direction)),
        Action::Swipe { target, direction } => format!("swipe e{target} {}", dir(direction)),
        Action::Back => "back".into(),
        Action::Home => "home".into(),
        Action::Wait => "wait".into(),
        Action::Read { targets, question, .. } => {
            let ts: Vec<String> = targets.iter().map(|c| format!("c{c}")).collect();
            format!("read {} {question:?}", ts.join(","))
        }
        Action::Answer { .. } => "answer".into(),
        Action::Done { .. } => "done".into(),
        Action::GiveUp { reason } => format!("give_up {reason:?}"),
    }
}

/// The action as the planner would write it (ids back as "e7"/"c4"), for logs.
pub fn to_json(action: &Action) -> Value {
    let mut v = serde_json::to_value(action).unwrap_or(Value::Null);
    if let Value::Object(m) = &mut v {
        if let Some(t) = m.get("target").and_then(Value::as_u64) {
            m.insert("target".into(), Value::from(format!("e{t}")));
        }
        if let Some(Value::Array(ts)) = m.get("targets").cloned() {
            let ids = ts.iter().filter_map(Value::as_u64).map(|c| Value::from(format!("c{c}"))).collect();
            m.insert("targets".into(), Value::Array(ids));
        }
        if m.get("target").is_some_and(Value::is_null) {
            m.remove("target");
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schema_lists_the_same_actions_as_the_parser() {
        let schema: Value = serde_json::from_str(ACTIONS_SCHEMA).unwrap();
        let variants = schema["oneOf"].as_array().unwrap();
        let names: Vec<&str> =
            variants.iter().map(|v| v["properties"]["action"]["const"].as_str().unwrap()).collect();
        assert_eq!(names, ACTION_NAMES);
    }

    #[test]
    fn schema_required_fields_parse() {
        // A minimal object for each variant, built from the schema's required fields, must parse.
        let schema: Value = serde_json::from_str(ACTIONS_SCHEMA).unwrap();
        for v in schema["oneOf"].as_array().unwrap() {
            let name = v["properties"]["action"]["const"].as_str().unwrap();
            let mut obj = serde_json::Map::new();
            for r in v["required"].as_array().unwrap() {
                let r = r.as_str().unwrap();
                let val = match r {
                    "action" => Value::from(name),
                    "target" => Value::from("e1"),
                    "targets" => serde_json::json!(["c1"]),
                    "direction" => Value::from("down"),
                    "shape" => Value::from("facts"),
                    "submit" => Value::from(false),
                    _ => Value::from("x"),
                };
                obj.insert(r.into(), val);
            }
            let text = Value::Object(obj).to_string();
            parse_reply(&text).unwrap_or_else(|e| panic!("{name}: {e} ({text})"));
        }
    }

    #[test]
    fn to_json_round_trips_through_the_parser() {
        let a = Action::Read { targets: vec![4], question: "who?".into(), shape: ReadShape::Element };
        let v = to_json(&a);
        assert_eq!(v["targets"], serde_json::json!(["c4"]));
        assert_eq!(parse_reply(&v.to_string()).unwrap().action, a);
    }

    #[test]
    fn braces_inside_strings_do_not_confuse_the_scanner() {
        let r = parse_reply(r#"{"action": "type", "target": "e2", "text": "a } b {", "why": "x"}"#).unwrap();
        assert_eq!(r.action, Action::Type { target: 2, text: "a } b {".into(), submit: false });
    }
}
