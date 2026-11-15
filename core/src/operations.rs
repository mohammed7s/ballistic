//! Typed operations (intents): structured routes the host advertises (core/README.md, "Typed
//! operations (intents)"; SPEC.md §5.2 and §5.5).
//!
//! The planner may emit `{"action":"intent","operation":"set_timer","args":{"seconds":300}}`.
//! Core, not the planner and not the host, decides whether that becomes a [`HandsOp::Intent`]:
//!
//!   parse            the arguments against the fixed table [`SPECS`]: types, ranges, formats;
//!                    unknown operations and arguments are refused
//!   resolve          to one package the host advertised for that operation now
//!                    ([`IntentGrant`]) and that also has an app grant; an unadvertised operation
//!                    or app is refused with a correction, never routed elsewhere
//!   check_request    requested-app preservation: an app the owner named that can take the
//!                    operation must be the one used
//!   tainted          every argument value with its source; a value not from the owner's request
//!                    (or owner data, or a control label) needs a hard confirmation naming it
//!   confirmation     operations that create state without a separate save step (timer, alarm)
//!                    always need the owner's yes
//!
//! No operation submits anything. Hosts map each [`IntentOp`] to a public, documented platform
//! intent with no "skip UI" or auto-send extra; an editor or compose screen it opens is then
//! observed like any screen, and its Save or Send tap goes through the ordinary confirmation path.
//! Hosts receive only typed, validated values; nothing here is a shell string.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::actions::ActionError;
use crate::screen_filter::matches_request;
use crate::types::*;
use crate::values::{Provenance, ValueKind};

const PROMPT: &str = include_str!("../prompts/operations.txt");

/// One handler the host resolved for `operation` on this device now. Host authority, never
/// model text: a grant without a matching app grant authorizes nothing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct IntentGrant {
    pub operation: String,
    pub package: String,
}

/// Fixed Settings pages; each host maps them to documented Settings actions or panels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SettingsPage { Wifi, InternetPanel, Bluetooth, Display, Sound, Notifications, Apps, Date }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CameraMode { Photo, Video }

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Weekday { Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, Sunday }

/// What a host executes for an intent: validated, normalized values only. `begin`/`end` are
/// local wall-clock times `YYYY-MM-DDTHH:MM`; the host converts them with the device time zone.
/// Phone numbers are digits with an optional leading `+`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case", deny_unknown_fields)]
pub enum IntentOp {
    SetTimer { seconds: u32, #[serde(default)] label: Option<String> },
    SetAlarm { hour: u8, minute: u8, #[serde(default)] label: Option<String>, #[serde(default)] days: Vec<Weekday> },
    CreateEvent { title: String, begin: String, end: String, #[serde(default)] location: Option<String>,
        #[serde(default)] description: Option<String> },
    ComposeSms { to: String, body: String },
    AddContact { name: String, #[serde(default)] phone: Option<String>, #[serde(default)] email: Option<String> },
    OpenSettings { page: SettingsPage },
    OpenCamera { mode: CameraMode },
    Dial { number: String },
    ViewUri { uri: String },
}

impl IntentOp {
    pub fn id(&self) -> &'static str {
        match self {
            IntentOp::SetTimer { .. } => "set_timer", IntentOp::SetAlarm { .. } => "set_alarm",
            IntentOp::CreateEvent { .. } => "create_event", IntentOp::ComposeSms { .. } => "compose_sms",
            IntentOp::AddContact { .. } => "add_contact", IntentOp::OpenSettings { .. } => "open_settings",
            IntentOp::OpenCamera { .. } => "open_camera", IntentOp::Dial { .. } => "dial",
            IntentOp::ViewUri { .. } => "view_uri",
        }
    }
}

/// What starting the operation does by itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Effect {
    /// Opens a screen; changes nothing.
    Navigation,
    /// Opens an editor or compose screen pre-filled; nothing is saved or sent without a tap.
    Editor,
    /// The handling app creates the item when it accepts the intent (no separate save step).
    CreatesState,
}

/// How core checks and tags one argument.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind { Int, Text, LongText, Phone, Email, DateTime, Uri, Choice, Days }

pub struct Spec {
    pub id: &'static str,
    /// `(name, kind, required)`.
    pub args: &'static [(&'static str, Kind, bool)],
    pub effect: Effect,
    /// One-line semantics for the planner.
    pub line: &'static str,
    /// What the host acknowledgment means, for the history line.
    pub did: &'static str,
}

/// Every operation core knows. A host advertises a subset; only that subset reaches the planner.
pub const SPECS: &[Spec] = &[
    Spec { id: "set_timer", args: &[("seconds", Kind::Int, true), ("label", Kind::Text, false)], effect: Effect::CreatesState,
        line: "set_timer{seconds:int 1-86400, label?}: creates AND starts a countdown timer; the owner confirms first. Not for a timer that must not start.",
        did: "the clock app was asked to create and start the timer" },
    Spec { id: "set_alarm", args: &[("hour", Kind::Int, true), ("minute", Kind::Int, true), ("label", Kind::Text, false), ("days", Kind::Days, false)],
        effect: Effect::CreatesState,
        line: "set_alarm{hour:int 0-23, minute:int 0-59, label?, days?:[\"monday\"..\"sunday\"]}: creates an enabled alarm (24-hour time; days repeat it weekly); the owner confirms first.",
        did: "the clock app was asked to create the enabled alarm" },
    Spec { id: "create_event", args: &[("title", Kind::Text, true), ("begin", Kind::DateTime, true), ("end", Kind::DateTime, false),
        ("duration_minutes", Kind::Int, false), ("location", Kind::Text, false), ("description", Kind::LongText, false)],
        effect: Effect::Editor,
        line: "create_event{title, begin:\"YYYY-MM-DDTHH:MM\", end:\"YYYY-MM-DDTHH:MM\" or duration_minutes:int, location?, description?}: opens a new-event editor pre-filled (local time); nothing is saved until you tap its Save control.",
        did: "a new-event editor was opened with these values; check them, then tap its Save control" },
    Spec { id: "compose_sms", args: &[("to", Kind::Phone, true), ("body", Kind::LongText, true)], effect: Effect::Editor,
        line: "compose_sms{to:phone number, body}: opens a compose screen for that number with the body filled in; nothing is sent until you tap its Send control.",
        did: "a compose screen was opened with this number and body; nothing is sent until its Send control is tapped" },
    Spec { id: "add_contact", args: &[("name", Kind::Text, true), ("phone", Kind::Phone, false), ("email", Kind::Email, false)],
        effect: Effect::Editor,
        line: "add_contact{name, phone?, email?}: opens a new-contact editor pre-filled; nothing is saved until you tap its Save control.",
        did: "a new-contact editor was opened with these values; check them, then tap its Save control" },
    Spec { id: "open_settings", args: &[("page", Kind::Choice, true)], effect: Effect::Navigation,
        line: "open_settings{page: wifi|internet_panel|bluetooth|display|sound|notifications|apps|date}: opens that Settings page; changes nothing.",
        did: "the Settings page was opened" },
    Spec { id: "open_camera", args: &[("mode", Kind::Choice, true)], effect: Effect::Navigation,
        line: "open_camera{mode: photo|video}: opens the camera in that mode; nothing is captured until you tap the shutter.",
        did: "the camera was opened" },
    Spec { id: "dial", args: &[("number", Kind::Phone, true)], effect: Effect::Navigation,
        line: "dial{number}: opens the dialer with the number filled in; it does not call.",
        did: "the dialer was opened with the number; no call was placed" },
    Spec { id: "view_uri", args: &[("uri", Kind::Uri, true)], effect: Effect::Navigation,
        line: "view_uri{uri: https://... or geo:latitude,longitude}: opens the link or the location.",
        did: "the link was opened" },
];

pub fn spec(id: &str) -> Option<&'static Spec> {
    SPECS.iter().find(|s| s.id == id)
}

fn bad(detail: impl Into<String>) -> ActionError {
    ActionError::BadArguments { action: "intent".into(), detail: detail.into() }
}

// ---- argument checks ----------------------------------------------------------------------------

const MAX_TEXT: usize = 200;
const MAX_LONG_TEXT: usize = 1000;
const MAX_URI: usize = 2048;
/// Longest event core will pre-fill: 31 days.
const MAX_EVENT_MINUTES: i64 = 31 * 24 * 60;

/// Absent and `null` are the same for an optional argument.
fn arg<'a>(args: &'a Map<String, Value>, name: &str) -> Option<&'a Value> {
    args.get(name).filter(|v| !v.is_null())
}

fn int(args: &Map<String, Value>, name: &str, lo: i64, hi: i64) -> Result<Option<i64>, ActionError> {
    let Some(v) = arg(args, name) else { return Ok(None) };
    let n = v.as_i64().filter(|_| v.is_i64() || v.is_u64())
        .ok_or_else(|| bad(format!("{name} must be a whole number, not {v}")))?;
    if !(lo..=hi).contains(&n) { return Err(bad(format!("{name} must be between {lo} and {hi}"))); }
    Ok(Some(n))
}

/// Characters that make a confirmation display lie about its text: controls, and bidirectional
/// overrides and isolates.
fn deceptive(c: char, multiline: bool) -> bool {
    (c.is_control() && !(multiline && c == '\n')) || matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}' | '\u{200E}' | '\u{200F}')
}

fn text_value(name: &str, v: &Value, max: usize, multiline: bool) -> Result<String, ActionError> {
    let s = v.as_str().ok_or_else(|| bad(format!("{name} must be a string")))?.trim();
    if s.is_empty() { return Err(bad(format!("{name} must not be empty"))); }
    if s.chars().count() > max { return Err(bad(format!("{name} must be at most {max} characters"))); }
    if s.chars().any(|c| deceptive(c, multiline)) { return Err(bad(format!("{name} contains control characters"))); }
    Ok(s.to_string())
}

fn text(args: &Map<String, Value>, name: &str, kind: Kind) -> Result<Option<String>, ActionError> {
    let (max, multiline) = if kind == Kind::LongText { (MAX_LONG_TEXT, true) } else { (MAX_TEXT, false) };
    arg(args, name).map(|v| text_value(name, v, max, multiline)).transpose()
}

/// `+1 (555) 010-0100` → `+15550100100`. Digits only, 3 to 15 of them, an optional leading `+`;
/// `*` and `#` (service codes) are refused.
pub fn phone(name: &str, raw: &str) -> Result<String, ActionError> {
    let s = raw.trim();
    let (plus, rest) = match s.strip_prefix('+') { Some(r) => (true, r), None => (false, s) };
    if !rest.chars().all(|c| c.is_ascii_digit() || matches!(c, ' ' | '-' | '(' | ')' | '.')) {
        return Err(bad(format!("{name} must be a phone number: digits, an optional leading +, spaces, dashes or brackets")));
    }
    let digits: String = rest.chars().filter(char::is_ascii_digit).collect();
    if !(3..=15).contains(&digits.len()) { return Err(bad(format!("{name} must have 3 to 15 digits"))); }
    Ok(if plus { format!("+{digits}") } else { digits })
}

fn email(name: &str, raw: &str) -> Result<String, ActionError> {
    let s = raw.trim();
    let err = || bad(format!("{name} must be one plain email address like ana@example.com"));
    let (local, domain) = s.split_once('@').ok_or_else(err)?;
    let local_ok = !local.is_empty() && local.len() <= 64 && local.chars().all(|c| c.is_ascii_alphanumeric() || "._%+-".contains(c));
    let domain_ok = domain.len() <= 253 && domain.contains('.') && !domain.starts_with(['.', '-']) && !domain.ends_with(['.', '-'])
        && !domain.contains("..") && domain.chars().all(|c| c.is_ascii_alphanumeric() || ".-".contains(c));
    if !local_ok || !domain_ok || s.len() > 254 { return Err(err()); }
    Ok(s.to_string())
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let doy = (153 * ((m + 9) % 12) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { yoe + era * 400 + 1 } else { yoe + era * 400 }, m, d)
}

/// `YYYY-MM-DDTHH:MM` (years 2000 to 2100) → minutes since 1970-01-01T00:00 of the same clock.
pub fn local_minutes(name: &str, raw: &str) -> Result<i64, ActionError> {
    let err = || bad(format!("{name} must be a local date and time \"YYYY-MM-DDTHH:MM\", like \"2023-10-23T14:30\""));
    let s = raw.trim().as_bytes();
    if s.len() != 16 || s[4] != b'-' || s[7] != b'-' || s[10] != b'T' || s[13] != b':' { return Err(err()); }
    let num = |a: usize, b: usize| -> Result<i64, ActionError> {
        let part = &s[a..b];
        if !part.iter().all(u8::is_ascii_digit) { return Err(err()); }
        Ok(part.iter().fold(0i64, |n, d| n * 10 + i64::from(d - b'0')))
    };
    let (y, mo, d, h, mi) = (num(0, 4)?, num(5, 7)?, num(8, 10)?, num(11, 13)?, num(14, 16)?);
    let leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    let month_days = [31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    if !(2000..=2100).contains(&y) || !(1..=12).contains(&mo) || d < 1 || d > month_days[(mo - 1) as usize] || h > 23 || mi > 59 {
        return Err(err());
    }
    Ok(days_from_civil(y, mo, d) * 1440 + h * 60 + mi)
}

pub fn format_minutes(m: i64) -> String {
    let (y, mo, d) = civil_from_days(m.div_euclid(1440));
    let r = m.rem_euclid(1440);
    format!("{y:04}-{mo:02}-{d:02}T{:02}:{:02}", r / 60, r % 60)
}

/// `https://` links with a host and no user part, or `geo:latitude,longitude`. Plain ASCII, no
/// spaces or quotes.
pub fn uri(name: &str, raw: &str) -> Result<String, ActionError> {
    let s = raw.trim();
    let err = || bad(format!("{name} must be an https:// link or geo:latitude,longitude"));
    if s.len() > MAX_URI || !s.chars().all(|c| c.is_ascii_graphic()) || s.contains(['"', '\'', '<', '>', '\\', '^', '`', '{', '|', '}']) {
        return Err(err());
    }
    if s.len() > 8 && s[..8].eq_ignore_ascii_case("https://") {
        let rest = &s[8..];
        let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
        let host = authority.split(':').next().unwrap_or("");
        let port_ok = authority.split_once(':').is_none_or(|(_, p)| !p.is_empty() && p.len() <= 5 && p.bytes().all(|b| b.is_ascii_digit()));
        if host.is_empty() || authority.contains('@') || !port_ok || host.starts_with(['.', '-'])
            || !host.chars().all(|c| c.is_ascii_alphanumeric() || ".-".contains(c)) {
            return Err(err());
        }
        return Ok(format!("https://{rest}"));
    }
    if let Some(rest) = s.strip_prefix("geo:") {
        let (lat, lng) = rest.split_once(',').ok_or_else(err)?;
        let coordinate = |t: &str, limit: f64| t.chars().all(|c| c.is_ascii_digit() || ".-".contains(c))
            && t.parse::<f64>().is_ok_and(|v| v.is_finite() && v.abs() <= limit);
        if !coordinate(lat, 90.0) || !coordinate(lng, 180.0) { return Err(err()); }
        return Ok(s.to_string());
    }
    Err(err())
}

fn choice<T: serde::de::DeserializeOwned>(args: &Map<String, Value>, name: &str, options: &str) -> Result<T, ActionError> {
    let v = arg(args, name).ok_or_else(|| bad(format!("{name} is required")))?;
    serde_json::from_value(v.clone()).map_err(|_| bad(format!("{name} must be one of {options}")))
}

fn required<T>(v: Option<T>, name: &str) -> Result<T, ActionError> {
    v.ok_or_else(|| bad(format!("{name} is required")))
}

/// The planner's arguments → a typed operation, or the first problem in words for a correction.
pub fn parse(operation: &str, args: &Map<String, Value>) -> Result<IntentOp, ActionError> {
    let spec = spec(operation).ok_or_else(|| bad(format!("unknown operation {operation:?}")))?;
    let names = || spec.args.iter().map(|(n, ..)| *n).collect::<Vec<_>>().join(", ");
    if let Some(k) = args.keys().find(|k| !spec.args.iter().any(|(n, ..)| n == k)) {
        return Err(bad(format!("{operation} has no argument {k:?}; it takes {}", names())));
    }
    if let Some((n, ..)) = spec.args.iter().find(|(n, _, req)| *req && arg(args, n).is_none()) {
        return Err(bad(format!("{operation} needs {n:?}")));
    }
    let s = |name: &str| arg(args, name).and_then(Value::as_str).ok_or_else(|| bad(format!("{name} must be a string")));
    let opt_phone = |name: &str| arg(args, name).map(|_| s(name).and_then(|v| phone(name, v))).transpose();
    Ok(match operation {
        "set_timer" => IntentOp::SetTimer { seconds: required(int(args, "seconds", 1, 86_400)?, "seconds")? as u32,
            label: text(args, "label", Kind::Text)? },
        "set_alarm" => {
            let mut days: Vec<Weekday> = match arg(args, "days") {
                None => vec![],
                Some(v) => serde_json::from_value(v.clone()).map_err(|_| bad("days must be a list like [\"monday\",\"friday\"]"))?,
            };
            let n = days.len();
            days.sort(); days.dedup();
            if days.len() != n { return Err(bad("days must not repeat a day")); }
            IntentOp::SetAlarm { hour: required(int(args, "hour", 0, 23)?, "hour")? as u8,
                minute: required(int(args, "minute", 0, 59)?, "minute")? as u8, label: text(args, "label", Kind::Text)?, days }
        }
        "create_event" => {
            let begin = local_minutes("begin", s("begin")?)?;
            let end = match (arg(args, "end"), int(args, "duration_minutes", 1, MAX_EVENT_MINUTES)?) {
                (Some(_), Some(_)) => return Err(bad("give end or duration_minutes, not both")),
                (None, None) => return Err(bad("create_event needs end or duration_minutes")),
                (Some(_), None) => local_minutes("end", s("end")?)?,
                (None, Some(d)) => begin + d,
            };
            if end <= begin { return Err(bad("end must be after begin")); }
            if end - begin > MAX_EVENT_MINUTES { return Err(bad("an event may last at most 31 days")); }
            IntentOp::CreateEvent { title: required(text(args, "title", Kind::Text)?, "title")?, begin: format_minutes(begin),
                end: format_minutes(end), location: text(args, "location", Kind::Text)?,
                description: text(args, "description", Kind::LongText)? }
        }
        "compose_sms" => IntentOp::ComposeSms { to: phone("to", s("to")?)?, body: required(text(args, "body", Kind::LongText)?, "body")? },
        "add_contact" => IntentOp::AddContact { name: required(text(args, "name", Kind::Text)?, "name")?, phone: opt_phone("phone")?,
            email: arg(args, "email").map(|_| s("email").and_then(|v| email("email", v))).transpose()? },
        "open_settings" => IntentOp::OpenSettings { page: choice(args, "page", "wifi, internet_panel, bluetooth, display, sound, notifications, apps, date")? },
        "open_camera" => IntentOp::OpenCamera { mode: choice(args, "mode", "photo, video")? },
        "dial" => IntentOp::Dial { number: phone("number", s("number")?)? },
        "view_uri" => IntentOp::ViewUri { uri: uri("uri", s("uri")?)? },
        _ => unreachable!("every spec has a parser"),
    })
}

// ---- routing ------------------------------------------------------------------------------------

/// Packages the host advertises for `operation` that also hold an app grant, in host order.
pub fn handlers<'a>(host: Option<&'a HostCapabilities>, operation: &str) -> Vec<&'a str> {
    host.map(|h| h.intents.iter().filter(|g| g.operation == operation && h.app_grants.iter().any(|a| *a == g.package))
        .map(|g| g.package.as_str()).collect()).unwrap_or_default()
}

fn label<'a>(apps: &'a [AppEntry], package: &'a str) -> &'a str {
    apps.iter().find(|a| a.package == package).map_or(package, |a| a.label.as_str())
}

/// `name` is this package or its installed label (case, spaces and punctuation ignored). No
/// partial or word-overlap matching: routing never guesses.
fn names_package(name: &str, package: &str, apps: &[AppEntry]) -> bool {
    let squash = |s: &str| s.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect::<String>();
    let n = squash(name);
    !n.is_empty() && (n == squash(package) || apps.iter().any(|a| a.package == package && squash(&a.label) == n))
}

/// A validated intent action → the exact operation Hands would run, bound to one advertised
/// package. Used by `actions::resolve`, so a refusal is a planner correction.
pub fn resolve(operation: &str, app: Option<&str>, args: &Map<String, Value>, screen: &ScreenState, apps: &[AppEntry])
    -> Result<HandsOp, ActionError> {
    let host = screen.capabilities.as_ref();
    let available: Vec<&str> = SPECS.iter().map(|s| s.id).filter(|id| !handlers(host, id).is_empty()).collect();
    if spec(operation).is_none() || handlers(host, operation).is_empty() {
        return Err(bad(if available.is_empty() {
            format!("operation {operation:?} is not available: this host advertises no typed operations; use the screen")
        } else {
            format!("operation {operation:?} is not available on this host now; available: {}", available.join(", "))
        }));
    }
    let intent = parse(operation, args)?;
    let found = handlers(host, operation);
    let list = || found.iter().map(|p| label(apps, p)).collect::<Vec<_>>().join(", ");
    let package = match app.map(str::trim).filter(|a| !a.is_empty()) {
        Some(name) => *found.iter().find(|p| names_package(name, p, apps))
            .ok_or_else(|| bad(format!("{name:?} has no advertised {operation} here; apps that do: {}", list())))?,
        None if found.len() == 1 => found[0],
        None => return Err(bad(format!("several apps handle {operation}: {}; name one in \"app\"", list()))),
    };
    Ok(HandsOp::Intent { package: package.to_string(), intent })
}

/// Requested-app preservation (SPEC.md §5.2). Installed apps the owner named (whole words in the
/// request) bind the route: if one of them handles the operation, the operation must go there;
/// if the owner named apps and none handles it, the operation is refused so the planner uses
/// that app's own screens. Opening a Settings page is exempt from the second rule only: Settings
/// pages are system screens, and a compound request ("turn on Wi-Fi and open Contacts") names
/// the other app for another step.
pub fn check_request(action: &Action, request: &str, screen: &ScreenState, apps: &[AppEntry]) -> Result<(), ActionError> {
    let Action::Intent { operation, app, args } = action else { return Ok(()) };
    let Ok(HandsOp::Intent { package, .. }) = resolve(operation, app.as_deref(), args, screen, apps) else { return Ok(()) };
    let named: Vec<&AppEntry> = apps.iter().filter(|a| a.label.trim().chars().count() >= 3 && matches_request(request, a.label.trim())).collect();
    if named.is_empty() || named.iter().any(|a| a.package == package) { return Ok(()); }
    let found = handlers(screen.capabilities.as_ref(), operation);
    let labels = |list: &[&AppEntry]| list.iter().map(|a| a.label.as_str()).collect::<Vec<_>>().join(", ");
    let named_handlers: Vec<&AppEntry> = named.iter().copied().filter(|a| found.contains(&a.package.as_str())).collect();
    if !named_handlers.is_empty() {
        return Err(bad(format!("the request names {}; send {operation} to that app with \"app\"", labels(&named_handlers))));
    }
    if operation == "open_settings" { return Ok(()); }
    Err(bad(format!("the request names {}, which has no advertised {operation} here; use that app's own screens", labels(&named))))
}

// ---- sources and confirmation -------------------------------------------------------------------

/// The time written in the request for an owner-sourced `YYYY-MM-DDTHH:MM`: the exact value, or
/// its date with its `HH:MM` (or `H:MM`). Anything else (a weekday, "6h", "tomorrow") is the
/// planner's computation.
fn datetime_in_request(request: &str, value: &str) -> bool {
    if matches_request(request, value) { return true; }
    let Some((date, time)) = value.split_once('T') else { return false };
    let short = time.strip_prefix('0').unwrap_or(time);
    matches_request(request, date) && (matches_request(request, time) || matches_request(request, short))
}

/// Where a phone number came from, compared by its digits: a carried value, the request, a
/// control label, a reader fact, else the planner.
fn phone_source(raw: &str, request: &str, labels: &[&str], carried: &[Tagged], facts: &[Tagged]) -> Source {
    let digits = |s: &str| s.chars().filter(char::is_ascii_digit).collect::<String>();
    let want = digits(raw);
    let has = |text: &str| crate::values::extract(text).iter()
        .any(|f| matches!(f.kind, ValueKind::Phone | ValueKind::Number) && digits(&f.raw) == want);
    if let Some(c) = carried.iter().find(|c| has(&c.value)) { return c.source.clone(); }
    if has(request) { return Source::OwnerRequest; }
    if labels.iter().any(|l| has(l)) { return Source::ScreenLabel; }
    facts.iter().find(|f| has(&f.value)).map_or(Source::Model("planner".into()), |f| f.source.clone())
}

/// Every argument value whose source is not the owner's (request, owner data or a control
/// label), with its source. Non-empty means a hard confirmation naming each one. `carried`
/// values keep their tags even inside the request (`agent::Task`); reader `facts` keep theirs.
/// Fixed choices (page, mode, days) are code-owned and carry no value.
pub fn tainted(action: &Action, request: &str, labels: &[&str], carried: &[Tagged], facts: &[Tagged]) -> Vec<Tagged> {
    let Action::Intent { operation, args, .. } = action else { return vec![] };
    let Some(spec) = spec(operation) else { return vec![] };
    let provenance = Provenance::new(&[request], labels, facts);
    let planner = Source::Model("planner".into());
    let mut out: Vec<Tagged> = Vec::new();
    let mut push = |t: Tagged| if !t.source.is_owner() && !out.contains(&t) { out.push(t) };
    let fact_or_planner = |s: &str| facts.iter().chain(carried).find(|f| f.value.trim().eq_ignore_ascii_case(s.trim()))
        .map_or(planner.clone(), |f| f.source.clone());
    for (name, kind, _) in spec.args {
        let Some(v) = arg(args, name) else { continue };
        let raw = v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string());
        let embedded = provenance.tag_with(&raw, carried, &planner);
        match kind {
            Kind::Choice | Kind::Days => {}
            Kind::Phone => push(Tagged { value: raw.clone(), source: phone_source(&raw, request, labels, carried, facts) }),
            Kind::Int => match &embedded[..] {
                [(found, source)] => push(Tagged { value: found.raw.clone(), source: source.clone() }),
                _ => push(Tagged { value: raw.clone(), source: planner.clone() }),
            },
            Kind::Uri if raw.starts_with("https://") || raw.starts_with("HTTPS://") => match &embedded[..] {
                [(found, source)] if found.raw == raw => push(Tagged { value: raw.clone(), source: source.clone() }),
                _ => push(Tagged { value: raw.clone(), source: fact_or_planner(&raw) }),
            },
            Kind::DateTime => if !datetime_in_request(request, &raw) {
                push(Tagged { value: raw.clone(), source: fact_or_planner(&raw) });
            },
            Kind::Text | Kind::LongText | Kind::Email | Kind::Uri => {
                for (found, source) in &embedded { push(Tagged { value: found.raw.clone(), source: source.clone() }); }
                for c in carried.iter().filter(|c| !c.value.trim().is_empty() && matches_request(&raw, c.value.trim())) {
                    push(c.clone());
                }
                if !matches_request(request, &raw) { push(Tagged { value: raw.clone(), source: fact_or_planner(&raw) }); }
            }
        }
    }
    out
}

/// Why this intent needs the owner's yes even with owner-sourced values, or `None`.
pub fn confirmation_reason(action: &Action) -> Option<String> {
    let Action::Intent { operation, .. } = action else { return None };
    match spec(operation)?.effect {
        Effect::CreatesState => Some(format!("{operation}: the clock app creates it as soon as it accepts the request, with no separate save step")),
        _ => None,
    }
}

/// The confirmation wording for [`tainted`].
pub fn taint_reason(values: &[Tagged]) -> String {
    let named: Vec<String> = values.iter().map(|v| format!("{:?} from {}", v.value, crate::reader::source_tag(&v.source))).collect();
    format!("start an operation with a value that is not in your request: {}", named.join(", "))
}

/// Task status and history outcome after Hands acknowledged `op`. An acknowledgment is the host
/// starting the activity, not the item existing: a pre-filled editor is `opened_ui`, a timer or
/// alarm is `pending` until something reads it back.
pub fn acknowledged(op: &HandsOp) -> (&'static str, String) {
    match op {
        HandsOp::OpenApp { .. } => ("opened_ui", "opened_ui".into()),
        HandsOp::Intent { package, intent } => {
            let Some(spec) = spec(intent.id()) else { return ("pending", "host acknowledged input".into()) };
            let status = if spec.effect == Effect::CreatesState { "pending" } else { "opened_ui" };
            (status, format!("intent {} accepted for {package}: {}; not verified", spec.id, spec.did))
        }
        _ => ("pending", "host acknowledged input".into()),
    }
}

/// Strings an executed intent put into fields: core's own output, shown as `(typed)` when a field
/// shows one exactly (`FilterContext::typed`).
pub fn prefilled(op: &HandsOp) -> Vec<String> {
    let HandsOp::Intent { intent, .. } = op else { return vec![] };
    let list: Vec<&Option<String>> = match intent {
        IntentOp::SetTimer { label, .. } | IntentOp::SetAlarm { label, .. } => vec![label],
        IntentOp::CreateEvent { title, location, description, .. } => return [Some(title.clone()), location.clone(), description.clone()]
            .into_iter().flatten().collect(),
        IntentOp::ComposeSms { to, body } => return vec![to.clone(), body.clone()],
        IntentOp::AddContact { name, phone, email } => return [Some(name.clone()), phone.clone(), email.clone()].into_iter().flatten().collect(),
        IntentOp::Dial { number } => return vec![number.clone()],
        IntentOp::OpenSettings { .. } | IntentOp::OpenCamera { .. } | IntentOp::ViewUri { .. } => vec![],
    };
    list.into_iter().flatten().cloned().collect()
}

/// The planner's system-prompt block: the ordinary host operations line, then, only when the
/// host advertises any, the typed operations it can start now with the apps that answer each.
pub fn planner_instructions(host: Option<&HostCapabilities>, apps: &[AppEntry]) -> String {
    let base = crate::capabilities::planner_instructions(host);
    let lines: Vec<String> = SPECS.iter().filter_map(|s| {
        let found = handlers(host, s.id);
        (!found.is_empty()).then(|| format!("- {} Apps: {}.", s.line, found.iter().map(|p| label(apps, p)).collect::<Vec<_>>().join(", ")))
    }).collect();
    if lines.is_empty() { return base; }
    format!("{base}\n{}\n{}", PROMPT.trim(), lines.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_dates_round_trip() {
        for s in ["2000-01-01T00:00", "2023-10-23T06:00", "2024-02-29T23:59", "2100-12-31T12:05"] {
            assert_eq!(format_minutes(local_minutes("begin", s).unwrap()), s);
        }
        assert_eq!(local_minutes("b", "1970-01-01T00:00").map_err(|_| ()), Err(()), "years before 2000 are refused");
        assert_eq!(format_minutes(local_minutes("b", "2023-12-31T23:30").unwrap() + 60), "2024-01-01T00:30");
    }

    #[test]
    fn every_spec_parses_and_ids_agree() {
        let samples = [
            ("set_timer", serde_json::json!({"seconds":300})),
            ("set_alarm", serde_json::json!({"hour":7,"minute":30})),
            ("create_event", serde_json::json!({"title":"t","begin":"2023-10-23T06:00","duration_minutes":60})),
            ("compose_sms", serde_json::json!({"to":"+15550100","body":"hi"})),
            ("add_contact", serde_json::json!({"name":"Ana"})),
            ("open_settings", serde_json::json!({"page":"wifi"})),
            ("open_camera", serde_json::json!({"mode":"photo"})),
            ("dial", serde_json::json!({"number":"5550100"})),
            ("view_uri", serde_json::json!({"uri":"https://example.com"})),
        ];
        assert_eq!(samples.len(), SPECS.len());
        for (id, args) in samples {
            let op = parse(id, args.as_object().unwrap()).unwrap_or_else(|e| panic!("{id}: {e}"));
            assert_eq!(op.id(), id);
        }
    }
}
