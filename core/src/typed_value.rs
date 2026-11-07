//! Bounded observations from dedicated value slots, never labels or action authority.
//! Grammar/envelopes: Simple Calendar 6.22.2 source + recorded host trees; see
//! docs/TYPED_VALUES_20261107.md. No owner request, clock, model or benchmark input.
use crate::types::{Node, NodeId, Role, ScreenState, Source};

const PACKAGE: &str = "com.simplemobiletools.calendar.pro";
const PREFIX: &str = "com.simplemobiletools.calendar.pro:id/";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClockTime {
    pub hour: u8,
    pub minute: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ObservedValue {
    Date { month: u8, day: u8, year: Option<u16>, weekday: u8 },
    Time(ClockTime),
    /// Two displayed clock readings, not a duration or a date association.
    TimePair { start: ClockTime, end: ClockTime },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValueObservation {
    /// Raw source node, retained even when its text folds into a list item.
    pub node: NodeId,
    /// Only this exact raw control/item may carry the observation after folding.
    pub(crate) owner_node: NodeId,
    pub source: Source,
    pub value: ObservedValue,
}

impl ValueObservation {
    pub(crate) fn render(&self) -> String {
        let value = match &self.value {
            ObservedValue::Date { month, day, year, weekday } => format!(
                "date month={month} day={day} year={} weekday={weekday}",
                year.map(|y| y.to_string()).unwrap_or_else(|| "unknown".into())),
            ObservedValue::Time(t) => format!("time hour={} minute={}", t.hour, t.minute),
            ObservedValue::TimePair { start, end } => format!(
                "time_pair start_hour={} start_minute={} end_hour={} end_minute={}",
                start.hour, start.minute, end.hour, end.minute),
        };
        format!(" [observed {value}; source={}; node={}; untrusted data]",
            crate::reader::source_tag(&self.source), self.node)
    }
}

/// These raw slots always remain content, even with a rejected envelope or grammar.
/// In particular numeric matches / typed history must not mint list-item identity.
pub(crate) fn is_value_slot(n: &Node) -> bool {
    slot(n).is_some()
}

/// Exact event-row content stays content even when a host calls a non-scrollable
/// list row a Button. This only tightens trust; it never recognizes a value or identity.
pub(crate) fn is_item_content(n: &Node) -> bool {
    matches!(n.resource_id.as_deref().and_then(|r| r.strip_prefix(PREFIX)),
        Some("event_item_holder" | "event_item_title" | "event_item_description"))
}

fn slot(n: &Node) -> Option<&str> {
    let s = n.resource_id.as_deref()?.strip_prefix(PREFIX)?;
    matches!(s, "event_start_date" | "event_start_time" | "event_end_date" |
        "event_end_time" | "event_item_time").then_some(s)
}

fn visible(s: &ScreenState, n: &Node) -> bool {
    n.flags.visible && n.bounds.area() > 0 && s.width > 0 && s.height > 0
        && n.bounds.x2 > 0 && n.bounds.y2 > 0 && n.bounds.x1 < s.width && n.bounds.y1 < s.height
}

fn envelope(s: &ScreenState, n: &Node, rid: &str, role: Role, class: &str) -> bool {
    n.package.as_deref() == Some(PACKAGE) && n.resource_id.as_deref()
        .and_then(|r| r.strip_prefix(PREFIX)) == Some(rid)
        && n.role == role && n.class_name.as_deref() == Some(class)
        && !n.flags.editable && !n.flags.checkable && visible(s, n)
}

fn parent(s: &ScreenState, i: usize) -> Option<usize> {
    let id = s.nodes[i].parent?;
    s.nodes[..i].iter().position(|n| n.id == id)
}

pub(crate) fn observe(s: &ScreenState, i: usize) -> Option<ValueObservation> {
    let n = &s.nodes[i];
    let field = slot(n)?;
    if s.package != PACKAGE || n.package.as_deref() != Some(PACKAGE)
        || n.role != Role::Text || n.class_name.as_deref() != Some("android.widget.TextView")
        || n.flags.editable || n.flags.checkable || n.flags.scrollable || n.flags.long_clickable
        || n.label.is_some() || !visible(s, n) {
        return None;
    }
    // Fail closed on ambiguous raw node identity, parents out of order, missing/invisible
    // ancestors, and duplicate slots in the same form/row. Never guess which sibling owns it.
    let mut ids = std::collections::HashSet::new();
    if !s.nodes.iter().all(|n| ids.insert(n.id)) { return None; }
    let mut ancestor = i;
    while s.nodes[ancestor].parent.is_some() {
        ancestor = parent(s, ancestor)?;
        if !visible(s, &s.nodes[ancestor]) || s.nodes[ancestor].package.as_deref() != Some(PACKAGE) {
            return None;
        }
    }
    let p = parent(s, i)?;
    let gp = parent(s, p)?;
    if s.nodes.iter().filter(|other| other.parent == n.parent && other.resource_id == n.resource_id).count() != 1 {
        return None;
    }
    let raw = n.text.as_deref()?; // No trimming, splitting off descriptions or suffixes.
    let value = if field == "event_item_time" {
        let row = &s.nodes[p];
        if s.activity != "com.simplemobiletools.calendar.pro.activities.MainActivity"
            || n.flags.clickable || row.role != Role::Item
            || !envelope(s, row, "event_item_holder", row.role, "android.view.ViewGroup")
            || !row.flags.clickable || row.flags.scrollable || row.label.is_some() || row.text.is_some()
            || !envelope(s, &s.nodes[gp], "calendar_events_list", Role::List,
                "androidx.recyclerview.widget.RecyclerView") {
            return None;
        }
        parse_list_time(raw)?
    } else {
        if s.activity != "com.simplemobiletools.calendar.pro.activities.EventActivity"
            || !n.flags.clickable
            || !envelope(s, &s.nodes[p], "event_nested_scrollview", Role::List, "android.widget.ScrollView")
            || !envelope(s, &s.nodes[gp], "event_coordinator", Role::Container, "android.view.ViewGroup") {
            return None;
        }
        if matches!(field, "event_start_date" | "event_end_date") {
            parse_date(raw)?
        } else {
            ObservedValue::Time(parse_time(raw)?.0)
        }
    };
    Some(ValueObservation { node: n.id, owner_node: if field == "event_item_time" { s.nodes[p].id } else { n.id }, source: Source::App(PACKAGE.into()), value })
}

/// Whole ASCII decimal, bounded before parsing. No signs, Unicode digits or whitespace.
fn decimal(s: &str, min: usize, max: usize) -> Option<u16> {
    (s.len() >= min && s.len() <= max && s.bytes().all(|b| b.is_ascii_digit()))
        .then(|| s.parse().ok()).flatten()
}

/// bool distinguishes the two supported lexical formats, so mixed intervals are rejected.
fn parse_time(raw: &str) -> Option<(ClockTime, bool)> {
    if !raw.is_ascii() { return None; }
    let (s, meridiem) = match raw.len() {
        5 => (raw, None),
        8 if raw.ends_with(" AM") => (&raw[..5], Some(false)),
        8 if raw.ends_with(" PM") => (&raw[..5], Some(true)),
        _ => return None,
    };
    if &s[2..3] != ":" { return None; }
    let mut hour = decimal(&s[..2], 2, 2)? as u8;
    let minute = decimal(&s[3..], 2, 2)? as u8;
    if minute > 59 { return None; }
    if let Some(pm) = meridiem {
        if !(1..=12).contains(&hour) { return None; }
        hour = hour % 12 + if pm { 12 } else { 0 };
    } else if hour > 23 { return None; }
    Some((ClockTime { hour, minute }, meridiem.is_some()))
}

fn parse_list_time(raw: &str) -> Option<ObservedValue> {
    if raw.len() > 19 { return None; }
    if let Some((start, end)) = raw.split_once(" - ") {
        let (start, a) = parse_time(start)?;
        let (end, b) = parse_time(end)?;
        (a == b).then_some(ObservedValue::TimePair { start, end })
    } else {
        Some(ObservedValue::Time(parse_time(raw)?.0))
    }
}

fn parse_date(raw: &str) -> Option<ObservedValue> {
    if raw.len() > 27 || !raw.is_ascii() { return None; }
    let (date, dow) = raw.strip_suffix(')')?.split_once(" (")?;
    let weekday = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].iter().position(|s| *s == dow)? as u8 + 1;
    let parts: Vec<_> = date.split(' ').collect();
    if !(2..=3).contains(&parts.len()) { return None; }
    let month = ["January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December"].iter().position(|s| *s == parts[0])? as u8 + 1;
    let day = decimal(parts[1], 1, 2)? as u8;
    if parts[1].starts_with('0') { return None; }
    let year = if parts.len() == 3 {
        let y = decimal(parts[2], 4, 4)?;
        if y == 0 { return None; }
        Some(y)
    } else { None };
    // February 29 is possible with an absent year; it does not establish a leap year.
    let leap = year.is_none_or(|y| y % 4 == 0 && (y % 100 != 0 || y % 400 == 0));
    let days = match month { 2 => if leap { 29 } else { 28 }, 4 | 6 | 9 | 11 => 30, _ => 31 };
    if day == 0 || day > days { return None; }
    if let Some(year) = year {
        // Gregorian weekday, Monday=1. Only check when the year was actually displayed.
        let y = i32::from(year) - i32::from(month < 3);
        let offsets = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
        let sunday_zero = (y + y / 4 - y / 100 + y / 400 + offsets[month as usize - 1] + i32::from(day)) % 7;
        if ((sunday_zero + 6) % 7 + 1) as u8 != weekday { return None; }
    }
    Some(ObservedValue::Date { month, day, year, weekday })
}
