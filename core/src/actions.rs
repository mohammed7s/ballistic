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

/// [`ACTIONS_SCHEMA`] without whitespace, as the planner prompt carries it: the same JSON, about
/// half the bytes (a 4,096-token on-device context holds the prompt plus the reply).
pub fn schema_for_prompt() -> &'static str {
    static COMPACT: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    COMPACT.get_or_init(|| serde_json::from_str::<Value>(ACTIONS_SCHEMA).expect("compiled action schema").to_string())
}

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
    #[error("the JSON object has no \"action\" field{}", fits_hint(.fits))]
    NoAction { fits: Vec<&'static str> },
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
    #[error("answer value {0:?} is not in the owner's request, a control label or a reader fact; read the content with shape facts first, or answer without that value")]
    UnsourcedAnswer(String),
    #[error("answer text must be at most {MAX_ANSWER_CHARS} characters")]
    LongAnswer,
    #[error("the reply is about {tokens} tokens, over the {cap}-token limit for one action; nothing in it was used")]
    Overlong { tokens: u32, cap: u32 },
}

/// The planner's output budget (`max_tokens`). A reply whose text is longer than this is
/// invalid output, whatever it contains (Haiku 2 produced 2,360–2,623 text tokens of repetition).
pub const PLANNER_TOKENS: u32 = 800;

/// Check the reply's length before parsing it: text beyond the role cap is invalid output.
pub fn check_length(text: &str, cap: u32) -> Result<(), ActionError> {
    let tokens = crate::context::text_tokens(text);
    if tokens > cap { Err(ActionError::Overlong { tokens, cap }) } else { Ok(()) }
}

fn fits_hint(fits: &[&str]) -> String {
    match fits {
        [] => String::new(),
        [one] => format!("; its fields fit {one:?}, but core does not guess the action: name it"),
        many => format!("; its fields fit {}, but core does not guess the action: name it",
            many.iter().map(|a| format!("{a:?}")).collect::<Vec<_>>().join(" or ")),
    }
}

/// The correction message for a rejected reply. Output-shape errors (no JSON, too long, unknown
/// action, bad arguments) remind the model to return exactly one JSON object and show one correct
/// object of the action it attempted; target and value errors keep their fixed wording.
pub fn correction(error: &ActionError, reply: &str) -> String {
    match error {
        ActionError::NoJson | ActionError::NoAction { .. } | ActionError::UnknownAction(_) | ActionError::BadArguments { .. }
        | ActionError::Overlong { .. } => {
            let shown = attempted(reply, error).and_then(|a| example(a).map(|e| format!("A correct {a} action: {e}. "))).unwrap_or_default();
            format!("Invalid action: {error}. {shown}Return exactly one corrected JSON object and nothing else: no prose, tags or code fences.")
        }
        _ => format!("Invalid action: {error}. Return a corrected JSON action."),
    }
}

/// The planner's rejected reply as the correction echoes it: at most 400 characters, so a runaway
/// reply is not sent back whole.
pub fn echo_rejected(text: &str) -> String {
    const MAX: usize = 400;
    if text.chars().count() <= MAX { text.to_string() } else { format!("{}…", text.chars().take(MAX).collect::<String>()) }
}

/// Longest planner answer core accepts.
pub const MAX_ANSWER_CHARS: usize = 600;

/// A parsed reply: the action and the planner's short reason (logged, not executed), plus what
/// the parser forgave (`read "element" as "target"`, `ignored field "x" on back`), for the log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannerReply {
    pub action: Action,
    pub why: String,
    pub notes: Vec<String>,
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

fn schema() -> &'static Value {
    static PARSED: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    PARSED.get_or_init(|| serde_json::from_str(ACTIONS_SCHEMA).expect("compiled action schema"))
}

fn variants() -> &'static [Value] { schema()["oneOf"].as_array().unwrap() }

/// Actions whose required fields (besides `action`) are all present in `obj` and whose schema
/// has every field `obj` carries: what an object without `action` could have meant. Reported in
/// the correction; core never picks one.
fn fitting_actions(obj: &serde_json::Map<String, Value>) -> Vec<&'static str> {
    variants().iter().filter(|v| {
        let required = v["required"].as_array().unwrap();
        required.iter().filter_map(Value::as_str).filter(|r| *r != "action").all(|r| obj.contains_key(r))
            && obj.keys().all(|k| v["properties"].get(k).is_some())
            && required.len() > 1
    }).filter_map(|v| v["properties"]["action"]["const"].as_str()).collect()
}

/// Key aliases small models use, applied only when the action's schema lacks the alias, the
/// canonical key is absent, and exactly one alias is present: `(actions, alias, canonical)`.
const KEY_ALIASES: &[(&[&str], &str, &str)] = &[
    (&["tap", "long_press", "type", "swipe", "scroll"], "element", "target"),
    (&["tap", "long_press", "type", "swipe", "scroll"], "element_id", "target"),
    (&["tap", "long_press", "type", "swipe", "scroll"], "target_id", "target"),
    (&["type"], "value", "text"),
    (&["type"], "input", "text"),
    (&["open_app"], "app_name", "app"),
    (&["open_app"], "package", "app"),
    (&["open_app"], "name", "app"),
    (&["scroll", "swipe"], "dir", "direction"),
    (&["read"], "target", "targets"),
    (&["answer"], "answer", "text"),
    (&["open_app", "tap", "long_press", "type", "scroll", "swipe", "back", "home", "wait", "read", "answer", "done", "give_up"], "reasoning", "why"),
    (&["open_app", "tap", "long_press", "type", "scroll", "swipe", "back", "home", "wait", "read", "answer", "done"], "reason", "why"),
];

/// Actions with no argument: an extra field cannot change what they do, so it is ignored (and
/// noted) instead of rejecting the reply.
const ARGUMENTLESS: &[&str] = &["back", "home", "wait"];

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
    let Some(mut obj) = objects.iter().rev().find(|m| m.contains_key("action")).cloned() else {
        // Do not guess the action from the fields: say which ones they would fit.
        return Err(ActionError::NoAction { fits: fitting_actions(objects.last().unwrap()) });
    };
    let name = obj
        .get("action")
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or(ActionError::NoAction { fits: vec![] })?;
    if !ACTION_NAMES.contains(&name.as_str()) {
        return Err(ActionError::UnknownAction(name));
    }
    obj.insert("action".into(), Value::from(name.clone()));
    let bad = |detail: String| ActionError::BadArguments { action: name.clone(), detail };
    let variant = variants().iter().find(|v| v["properties"]["action"]["const"] == name).unwrap();
    let mut notes = Vec::new();
    for canonical in ["target", "text", "app", "direction", "targets", "why"] {
        let aliases: Vec<&str> = KEY_ALIASES.iter()
            .filter(|(names, alias, c)| *c == canonical && names.contains(&name.as_str()) && variant["properties"].get(*alias).is_none()
                && obj.contains_key(*alias))
            .map(|(_, alias, _)| *alias).collect();
        if let [alias] = aliases[..] {
            if !obj.contains_key(canonical) && variant["properties"].get(canonical).is_some() {
                let mut value = obj.remove(alias).unwrap();
                if canonical == "targets" && value.is_string() { value = Value::Array(vec![value]); }
                obj.insert(canonical.into(), value);
                notes.push(format!("read {alias:?} as {canonical:?}"));
            }
        }
    }
    let unknown: Vec<String> = obj.keys().filter(|f| variant["properties"].get(f.as_str()).is_none()).cloned().collect();
    if let Some(field) = unknown.first() {
        if !ARGUMENTLESS.contains(&name.as_str()) {
            return Err(bad(format!("unknown field {field:?}")));
        }
        for f in &unknown {
            obj.remove(f);
            notes.push(format!("ignored field {f:?} on {name}"));
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
    Ok(PlannerReply { action, why, notes })
}

/// The action a rejected reply tried, for the correction's example: its `action` name, an
/// obvious synonym of one, or the single action its fields fit.
pub fn attempted(text: &str, error: &ActionError) -> Option<&'static str> {
    let named = |n: &str| ACTION_NAMES.iter().copied().find(|a| *a == n).or(match n {
        "click" | "press" | "tap_element" => Some("tap"),
        "input" | "type_text" | "enter_text" | "input_text" | "fill" => Some("type"),
        "open" | "launch" | "launch_app" | "start_app" => Some("open_app"),
        "finish" | "complete" | "finished" => Some("done"),
        "go_back" | "navigate_back" => Some("back"),
        _ => None,
    });
    match error {
        ActionError::BadArguments { action, .. } | ActionError::UnknownAction(action) => named(action),
        ActionError::NoAction { fits } => fits.first().copied(),
        _ => json_objects(text).into_iter().rev().filter_map(|s| serde_json::from_str::<Value>(s).ok())
            .find_map(|v| v.get("action").and_then(Value::as_str).and_then(named)),
    }
}

/// One correct object for each action, for corrections. A test parses every one.
pub fn example(action: &str) -> Option<&'static str> {
    Some(match action {
        "open_app" => r#"{"action":"open_app","app":"Clock","why":"the owner asked for the clock"}"#,
        "tap" => r#"{"action":"tap","target":"e3","why":"open the menu"}"#,
        "long_press" => r#"{"action":"long_press","target":"e3","why":"select the row"}"#,
        "type" => r#"{"action":"type","target":"e2","text":"Hello","submit":false,"why":"fill the message field"}"#,
        "scroll" => r#"{"action":"scroll","target":"e5","direction":"down","why":"show more rows"}"#,
        "swipe" => r#"{"action":"swipe","target":"e1","direction":"right","why":"raise the slider"}"#,
        "back" => r#"{"action":"back","why":"return to the list"}"#,
        "home" => r#"{"action":"home","why":"go to the home screen"}"#,
        "wait" => r#"{"action":"wait","why":"the screen is loading"}"#,
        "read" => r#"{"action":"read","targets":["c4"],"question":"what is the new number?","shape":"facts","why":"the number is hidden"}"#,
        "answer" => r#"{"action":"answer","text":"You have two events today.","why":"the owner asked"}"#,
        "done" => r#"{"action":"done","evidence":"the saved event shows the requested title","why":"finished"}"#,
        "give_up" => r#"{"action":"give_up","reason":"this app has no such setting","why":"unsupported"}"#,
        _ => return None,
    })
}

/// A selectable chip the owner's request names: a clickable, checkable item or button with no
/// label whose own content is a match-only title (Pro Expense's category chips: "Transportation"
/// (matches request) [off]). Tapping it makes the choice the owner wrote; it is not a switch,
/// checkbox, field or slider, and Settings and SystemUI toggles never qualify. The tap keeps the
/// ordinary confirmation rules (§9).
fn named_chip(e: &crate::screen_filter::Element) -> bool {
    e.flags.checkable && e.flags.clickable && !e.flags.editable && e.label.is_none() && !e.shown_content.is_empty()
        && matches!(e.role, Role::Item | Role::Button)
        && !matches!(e.package.as_str(), "com.android.systemui" | "com.android.settings")
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
        Action::Tap { target } if element(*target).is_ok_and(named_chip) => Ok(()),
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
            // A row the owner named (a match-only title, no label of its own) may be long-pressed
            // when it is clickable even if the app does not flag it long-clickable: DocumentsUI
            // file rows select on a long press without the flag. Same confirmation as a tap.
            let named_row = e.label.is_none() && !e.shown_content.is_empty() && e.flags.clickable
                && matches!(e.role, Role::Item | Role::Button);
            if e.flags.long_clickable || named_row { Ok(()) }
            else { Err(ActionError::NotActionable(*target)) }
        }
        Action::Swipe { target, .. } => {
            let e = element(*target)?;
            if e.role == Role::Slider { identified(*target)?; }
            if e.flags.scrollable || e.role == Role::Slider { Ok(()) }
            else { Err(ActionError::NotActionable(*target)) }
        }
        Action::Type { target, text, submit } => {
            if identified(*target)?.flags.editable || (!*submit && keypad_keys(view, *target, text).is_some()) {
                Ok(())
            } else {
                Err(ActionError::NotEditable(*target))
            }
        }
        Action::Scroll { until: Some(u), .. } if u.trim().is_empty() || u.chars().count() > 80 => Err(ActionError::Empty("until")),
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

/// Longest digit string keypad entry accepts.
pub const KEYPAD_MAX_DIGITS: usize = 12;

/// Keypad entry (§6): `type` into a labeled display that is not a text field, with digits only,
/// on a screen showing a digit keypad in the display's own app. Each needed digit must be
/// exactly one enabled, clickable, non-toggle button labeled with exactly that digit ("1"), so
/// the keys are identified by their own labels. Returns the element ids to tap, in order.
pub fn keypad_keys(view: &FilteredScreen, target: u32, text: &str) -> Option<Vec<u32>> {
    let display = view.element(target)?;
    if display.flags.editable || display.label.is_none() || text.is_empty() || text.len() > KEYPAD_MAX_DIGITS
        || !text.bytes().all(|b| b.is_ascii_digit()) { return None; }
    text.chars().map(|d| {
        let keys: Vec<&crate::screen_filter::Element> = view.elements.iter().filter(|e| e.package == display.package
            && e.role == Role::Button && e.flags.clickable && e.flags.enabled && !e.flags.checkable && !e.flags.editable
            && e.label.as_deref().is_some_and(|l| l.chars().eq(std::iter::once(d)))).collect();
        match keys[..] { [k] => Some(k.id), _ => None }
    }).collect()
}

/// The taps for a keypad-entry `type` (see [`keypad_keys`]), or `None` for any other action.
pub fn keypad_taps(action: &Action, view: &FilteredScreen, screen: &ScreenState) -> Option<Vec<HandsOp>> {
    let Action::Type { target, text, submit: false } = action else { return None };
    let keys = keypad_keys(view, *target, text)?;
    keys.iter().map(|k| view.element(*k).map(|e| { let (x, y) = clip(e.bounds, screen).centre(); HandsOp::Tap { x, y } })).collect()
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
        Action::Type { target, text, submit } => match keypad_taps(action, view, screen) {
            // Keypad entry: the first key here; the agent dispatches every key in order.
            Some(taps) if !view.element(*target).is_some_and(|e| e.flags.editable) => taps[0].clone(),
            _ => {
                let (x, y) = bounds(*target)?.centre();
                HandsOp::Type { x, y, text: text.clone(), submit: *submit }
            }
        },
        Action::Scroll { target, direction, .. } => HandsOp::Scroll {
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
    // Case, spaces, underscores and other punctuation ignored ("simple_sms_messenger",
    // "SimpleSMSMessenger"), and a few names small models use for Settings. Only an app in the
    // advertised list can match; exactly one must.
    let squash = |s: &str| s.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect::<String>();
    let mut wanted = squash(name);
    if let Some(stem) = wanted.strip_suffix("app").filter(|s| !s.is_empty()) { wanted = stem.to_string(); }
    if matches!(wanted.as_str(), "systemsettings" | "androidsettings" | "phonesettings" | "devicesettings") {
        wanted = "settings".into();
    }
    let same: Vec<&AppEntry> = apps.iter().filter(|a| squash(&a.label) == wanted || squash(&a.package) == wanted).collect();
    if let [a] = same[..] {
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

/// Answer provenance (core/README.md §7): every number, phone number, amount or link in an
/// answer must come from the owner's request, a control label on this screen, a value carried
/// into the request with its source, or a reader fact. Returns every value with its source, or
/// the first value that has none.
pub fn answer_values(text: &str, view: &FilteredScreen, request: &str, carried: &[Tagged], facts: &[Tagged]) -> Result<Vec<Tagged>, ActionError> {
    if text.trim().is_empty() { return Err(ActionError::Empty("text")); }
    if text.chars().count() > MAX_ANSWER_CHARS { return Err(ActionError::LongAnswer); }
    let labels: Vec<&str> = view.elements.iter().filter_map(|e| e.label.as_deref()).collect();
    let provenance = crate::values::Provenance::new(&[request], &labels, facts);
    let unsourced = Source::Model("planner".into());
    let tagged = provenance.tag_with(text, carried, &unsourced);
    if let Some((found, _)) = tagged.iter().find(|(_, s)| *s == unsourced) {
        return Err(ActionError::UnsourcedAnswer(found.raw.clone()));
    }
    Ok(tagged.into_iter().map(|(found, source)| Tagged { value: found.raw, source }).collect())
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
        Action::Scroll { target: Some(t), direction, until } => format!("scroll e{t} {}{}", dir(direction), until.as_ref().map(|u| format!(" until {u:?}")).unwrap_or_default()),
        Action::Scroll { target: None, direction, until } => format!("scroll {}{}", dir(direction), until.as_ref().map(|u| format!(" until {u:?}")).unwrap_or_default()),
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
