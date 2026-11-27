//! Typed postconditions (core/README.md §10, "Typed postconditions"). A few request forms have a
//! result core can check in code on a fresh observation, with no model involved:
//!
//!   Launch       "open Settings" (launch_completion.rs), optionally followed by the exact
//!                conditional pop-up sentence; the host's foreground readback names the app and,
//!                with the clause, no permission prompt or other window shows over it
//!   Brightness   "turn brightness to the max value": Settings > Display (a page proven by the
//!                complete signature in screen_filter/settings.rs) shows Brightness level 100%
//!   MessageSent  "send a text message to +1555… with message: …" / "reply to the most recent
//!                text message with message: …": after a Send the owner confirmed in this run,
//!                whose readback emptied the composer that held exactly that body, the thread
//!                shows one more outgoing row with exactly that body, in the requested thread
//!   Photo        "take one photo": exactly one shutter press in this run, and a last-photo
//!                thumbnail that was not on the screen the press was planned on is now shown
//!   RowsDeleted  "delete the following expenses from …: A, B": each named row was seen, an
//!                owner-confirmed input followed, and the same page lists its other rows without them
//!   NoteSaved    "create a new note in Markor named n.txt with the following text: …": the
//!                editor held the text with Save disabled, then the file list shows n.txt
//!
//! Recognition is a full-string grammar over the owner's words; anything else (other wording,
//! compound requests) is not recognized and keeps the ordinary verifier. Checks read the raw
//! tree in code; nothing here adds a string to any prompt. Every structural identity below was
//! seen in a recorded screen (`core/tests/screens/postconditions/`). A passed check says what it
//! saw: it is screen evidence for the requested postcondition, not a delivery receipt, and it
//! does not establish the absence of other side effects.

use crate::{launch_completion::{self, LaunchGoal}, readback::{Readback, ReadbackOutcome}, screen_filter, types::*,
    verification::Assessment};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Goal {
    Launch { goal: LaunchGoal, popups: bool, launch_request: String },
    Brightness { percent: u8 },
    MessageSent { to: Recipient, body: String },
    Photo,
    /// "delete the following expenses from <app>: A, B" / "delete the note in <app> named A":
    /// every named row was seen on a list, an owner-confirmed input followed, and the same list
    /// now shows the other rows it showed then, in order, without the named ones.
    RowsDeleted { names: Vec<String> },
    /// "create a new note in Markor named <name> with the following text: <text>": the editor
    /// held exactly the owner's text with Save disabled (saved), then Markor's file list shows a
    /// file row titled exactly <name> that no list showed before the text was typed.
    NoteSaved { name: String, text: String },
    /// "add the following <things> into <app>:" with one record of owner field values (fields.rs):
    /// every value entered in this run, then a Save-like tap that closed the form, and a screen
    /// of the same app showing the record's first value that no screen before the entry showed.
    EntrySaved { fields: Vec<crate::fields::Field> },
    /// "pause the stopwatch" / "run the stopwatch": the app's selected tab or page is named
    /// <noun>, and its own controls offer only the opposite action (Start shown and no Pause
    /// means it is not running). A state that already held counts: it is shown on the screen.
    RunState { noun: String, running: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Recipient {
    /// Digits of the number, with a leading `+` when the owner wrote one.
    Number(String),
    /// The conversation at the top of the app's conversation list.
    MostRecent,
}

/// One input Hands acknowledged in this run, as core planned it and read it back.
#[derive(Debug, Clone)]
pub struct Effect {
    pub action: Action,
    /// The screen the input was planned on.
    pub before: ScreenState,
    /// The node the input went to on `before`, if it had a target.
    pub target: Option<NodeId>,
    /// The owner confirmed it (a guarded input, §9).
    pub confirmed: bool,
    pub readback: Option<Readback>,
}

impl Goal {
    /// A plain launch keeps its stricter contract: a failed check on `done` stops the run.
    pub fn stops_on_failed_done(&self) -> bool {
        matches!(self, Goal::Launch { popups: false, .. })
    }
    /// Launch checks need the host's foreground readback, and no screen shows the brightness
    /// slider's value (a verifier citing the labeled slider would be guessing): a model cannot
    /// stand in for either. Message and photo goals fall back to the ordinary verifier on `done`
    /// when the typed check does not pass, as before this module existed.
    pub fn verifier_fallback(&self) -> bool {
        // A saved file is not visible in an editor: a verifier citing typed text was the
        // MarkorCreateNote rejection path, and accepting it would be a guess.
        !matches!(self, Goal::Launch { .. } | Goal::Brightness { .. } | Goal::NoteSaved { .. })
    }
}

// ---- recognition --------------------------------------------------------------------------------

/// Lower-case ASCII, single spaces, no trailing period. Byte offsets of ASCII text are kept.
fn words(s: &str) -> String {
    let s = s.split_whitespace().collect::<Vec<_>>().join(" ").to_ascii_lowercase();
    s.strip_suffix('.').unwrap_or(&s).to_string()
}

fn strip_any<'a>(s: &'a str, prefixes: &[&str]) -> Option<&'a str> {
    prefixes.iter().find_map(|p| s.strip_prefix(p))
}

pub fn recognize(request: &str, apps: &[AppEntry]) -> Option<Goal> {
    // Line breaks belong only to a list of field values; every other form is one line.
    if request.chars().any(|c| c.is_control() && c != '\n') { return None; }
    if request.trim().contains('\n') {
        let w = words(request);
        return entry_saved(w.strip_prefix("please ").unwrap_or(&w), request);
    }
    if let Some((goal, popups, launch_request)) = launch_completion::recognize_with_popups(request, apps) {
        return Some(Goal::Launch { goal, popups, launch_request });
    }
    let w = words(request);
    let w = w.strip_prefix("please ").unwrap_or(&w);
    brightness(w).or_else(|| photo(w)).or_else(|| run_state(w)).or_else(|| message(request, apps)).or_else(|| rows_deleted(request))
        .or_else(|| note_saved(request, apps)).or_else(|| entry_saved(w, request))
}

/// "add|create the following …:" whose remaining lines are one record of field values.
fn entry_saved(w: &str, request: &str) -> Option<Goal> {
    let first = request.trim().lines().next()?.trim();
    if strip_any(w, &["add ", "create "]).is_none() || !first.ends_with(':') { return None; }
    let fields = crate::fields::owner_fields(request);
    crate::fields::single_record(&fields).then_some(Goal::EntrySaved { fields })
}

/// A name the owner gave for a row: short, one line, not a sentence break or a list.
fn owner_name(s: &str) -> Option<String> {
    let s = s.trim();
    let s = s.strip_suffix('.').unwrap_or(s).trim();
    let ok = !s.is_empty() && s.chars().count() <= 80 && !s.contains(". ") && !s.contains(':')
        && !s.chars().any(char::is_control);
    ok.then(|| s.to_string())
}

/// "delete|remove the following <noun words> [from|in <app words>]: A, B and C" and
/// "delete|remove the <noun> in|from <app words> named|called|titled <name>". The names are the
/// owner's exact words; the rest must be plain words (no second clause, no other verb).
fn rows_deleted(request: &str) -> Option<Goal> {
    let t = request.trim();
    let lower = t.to_ascii_lowercase();
    let start = lower.len() - lower.strip_prefix("please ").unwrap_or(&lower).len();
    let rest = &lower[start..];
    let verb = strip_any(rest, &["delete ", "remove "])?;
    let plain = |s: &str| !s.is_empty() && s.split(' ').count() <= 8
        && s.chars().all(|c| c.is_ascii_alphabetic() || c == ' ')
        && !s.split(' ').any(|w| matches!(w, "and" | "then" | "send" | "add" | "create" | "open" | "reply" | "also"));
    let names: Vec<String> = if let Some(head) = verb.strip_prefix("the following ") {
        let (words_part, _) = head.split_once(": ")?;
        if !plain(words_part) { return None; }
        let list = &t[t.len() - (head.len() - words_part.len() - 2)..];
        let list = list.trim().strip_suffix('.').unwrap_or(list.trim());
        list.split(", ").flat_map(|p| p.split(" and ")).map(owner_name).collect::<Option<Vec<_>>>()?
    } else {
        let head = verb.strip_prefix("the ")?;
        let (words_part, name) = [" named ", " called ", " titled "].iter().find_map(|k| head.split_once(k))?;
        if !plain(words_part) || !words_part.split(' ').any(|w| matches!(w, "in" | "from")) { return None; }
        vec![owner_name(&t[t.len() - name.len()..])?]
    };
    if names.is_empty() || names.len() > 10 { return None; }
    Some(Goal::RowsDeleted { names })
}

const MARKOR: &str = "net.gsantner.markor";

/// "create a [new] note|file in|using <Markor label> [app] named|called <name> with the
/// following text: <text>". Only Markor's recorded editor and file list are known.
fn note_saved(request: &str, apps: &[AppEntry]) -> Option<Goal> {
    let t = request.trim();
    let lower = t.to_ascii_lowercase();
    let rest = lower.strip_prefix("please ").unwrap_or(&lower);
    let rest = strip_any(rest, &["create a new note ", "create a note ", "create a new file ", "create a new text file ", "create a file "])?;
    let rest = strip_any(rest, &["in ", "using ", "with "])?;
    let rest = apps.iter().filter(|a| a.package == MARKOR && !a.label.is_empty()).find_map(|a| {
        let label = a.label.to_ascii_lowercase();
        rest.strip_prefix(&format!("{label} app ")).or_else(|| rest.strip_prefix(&format!("{label} ")))
    })?;
    let rest = strip_any(rest, &["named ", "called ", "titled "])?;
    let (name, after) = rest.split_once(' ')?;
    let text = strip_any(after, &["with the following text: ", "with the text: ", "with text: ", "with the following content: "])?;
    let name = &t[t.len() - rest.len()..][..name.len()];
    let text = t[t.len() - text.len()..].trim();
    let name_ok = !name.is_empty() && name.chars().count() <= 100 && !name.contains('/') && !name.chars().any(char::is_control);
    if !name_ok || text.is_empty() || text.chars().count() > 2000 { return None; }
    Some(Goal::NoteSaved { name: name.to_string(), text: text.to_string() })
}

fn brightness(w: &str) -> Option<Goal> {
    let rest = strip_any(w, &["turn ", "set ", "change ", "put ", "make "])?;
    let rest = rest.strip_prefix("the ").unwrap_or(rest);
    let rest = strip_any(rest, &["screen ", "display "]).unwrap_or(rest);
    let rest = rest.strip_prefix("brightness ")?;
    let rest = strip_any(rest, &["to ", "at "])?;
    let rest = rest.strip_prefix("the ").unwrap_or(rest);
    let (percent, tail) = if let Some(t) = strip_any(rest, &["maximum", "max", "highest", "full"]) { (100, t) }
        else { (0, strip_any(rest, &["minimum", "min", "lowest"])?) };
    matches!(tail, "" | " value" | " level" | " setting").then_some(Goal::Brightness { percent })
}

/// "pause|stop the <noun>" and "start|run|resume the <noun>", the noun one or two plain words.
fn run_state(w: &str) -> Option<Goal> {
    let (rest, running) = if let Some(r) = strip_any(w, &["pause the ", "stop the "]) { (r, false) }
        else { (strip_any(w, &["start the ", "run the ", "resume the "])?, true) };
    let ok = !rest.is_empty() && rest.split(' ').count() <= 2 && rest.chars().all(|c| c.is_ascii_alphabetic() || c == ' ');
    ok.then(|| Goal::RunState { noun: rest.to_string(), running })
}

fn photo(w: &str) -> Option<Goal> {
    let rest = strip_any(w, &["take one ", "take a single ", "take a ", "take 1 "])?;
    let rest = strip_any(rest, &["photo", "picture"])?;
    matches!(rest, "" | " with the camera" | " using the camera" | " with the camera app" | " using the camera app")
        .then_some(Goal::Photo)
}

/// The SMS app whose thread layout the checks below know (recorded screens only).
const SMS: &str = "com.simplemobiletools.smsmessenger";

/// "send a text message [using <SMS app>] to <number> with message: <body>" and
/// "reply to the most recent text message [using <SMS app>] with message: <body>". The body is
/// the owner's exact remaining text.
fn message(request: &str, apps: &[AppEntry]) -> Option<Goal> {
    let t = request.trim();
    let l = t.to_ascii_lowercase();
    let mut rest = l.strip_prefix("please ").unwrap_or(&l);
    let reply = if let Some(r) = strip_any(rest, &["send a text message ", "send a message ", "send an sms ", "send a text ", "send an sms message "]) {
        rest = r; false
    } else if let Some(r) = strip_any(rest, &["reply to the most recent text message ", "reply to the most recent message ",
        "reply to the latest text message ", "reply to the newest text message ", "reply to the most recent sms "]) {
        rest = r; true
    } else if let Some(r) = rest.strip_prefix("reply to ").filter(|r| r.starts_with(|c: char| c == '+' || c.is_ascii_digit())) {
        // "reply to <number> with message: …": the thread with that number (loop 3).
        rest = r; false
    } else { return None; };
    let replying_to_number = !matches!(l.strip_prefix("please ").unwrap_or(&l).split_whitespace().next(), Some("send"));
    // Optional app clause naming an installed app with the known thread layout.
    let app_clause = |rest: &'_ str| -> Option<usize> {
        let prep = ["using ", "with ", "in ", "via ", "on "].into_iter().find(|p| rest.starts_with(p))?;
        let after = &rest[prep.len()..];
        apps.iter().filter(|a| a.package == SMS && !a.label.is_empty()).find_map(|a| {
            let label = a.label.to_ascii_lowercase();
            after.strip_prefix(&format!("{label} app ")).or_else(|| after.strip_prefix(&format!("the {label} app ")))
                .or_else(|| after.strip_prefix(&format!("{label} ")))
        }).map(|r| rest.len() - r.len())
    };
    if let Some(n) = app_clause(rest) { rest = &rest[n..]; }
    let to = if reply { Recipient::MostRecent } else {
        let after = if replying_to_number { rest } else { rest.strip_prefix("to ")? };
        let (number, after) = after.split_once(' ')?;
        let digits: String = number.chars().filter(|c| !"()-".contains(*c)).collect();
        let plain = digits.strip_prefix('+').unwrap_or(&digits);
        if !(6..=15).contains(&plain.len()) || !plain.bytes().all(|b| b.is_ascii_digit()) { return None; }
        rest = after;
        if replying_to_number { if let Some(n) = app_clause(rest) { rest = &rest[n..]; } }
        Recipient::Number(digits)
    };
    let rest_body = strip_any(rest, &["with message: ", "with the message: ", "with message ", "with the message ", "saying: ", "saying "])?;
    // Same byte offset in the original text: the prefix consumed only ASCII.
    let mut body = t[t.len() - rest_body.len()..].trim();
    // An app clause after the body ("… with message: Hi. in Simple SMS Messenger") names the
    // installed SMS app exactly; it is not part of the message.
    for a in apps.iter().filter(|a| a.package == SMS && !a.label.is_empty()) {
        for prep in [" in ", " using ", " with ", " via ", " on "] {
            for suffix in ["", " app"] {
                let clause = format!("{prep}{}{suffix}", a.label.to_ascii_lowercase());
                let lower = body.to_ascii_lowercase();
                let lower = lower.strip_suffix('.').unwrap_or(&lower);
                if lower.ends_with(&clause) { body = body[..lower.len() - clause.len()].trim(); }
            }
        }
    }
    if body.is_empty() || body.chars().count() > 400 { return None; }
    Some(Goal::MessageSent { to, body: body.to_string() })
}

// ---- checks ---------------------------------------------------------------------------------

fn unknown(feedback: &'static str) -> Assessment {
    Assessment { verification: Verification::default(), feedback }
}

fn verified(basis: &str, fresh: &ScreenState, feedback: &'static str) -> Assessment {
    Assessment {
        verification: Verification { status: VerificationStatus::Verified, evidence_basis: basis.into(), nodes: vec![],
            foreground: fresh.foreground.clone() },
        feedback,
    }
}

/// The observation is about `package`: its header says so, and an explicit foreground readback,
/// when the host sent one, agrees.
fn about(fresh: &ScreenState, package: &str) -> bool {
    fresh.package == package && fresh.foreground.as_ref().is_none_or(|f| f.package == package && f.activity == fresh.activity)
}

fn shown(n: &Node) -> bool { n.flags.visible && n.bounds.area() > 0 }
fn rid(n: &Node) -> Option<&str> { n.resource_id.as_deref() }
fn children<'a>(s: &'a ScreenState, parent: NodeId) -> impl Iterator<Item = &'a Node> + 'a {
    s.nodes.iter().filter(move |n| n.parent == Some(parent))
}
fn node(s: &ScreenState, id: NodeId) -> Option<&Node> { s.nodes.iter().find(|n| n.id == id) }

/// Check `goal` on `fresh`, a new observation taken after the last input. `before` is the
/// screen of the step that checks; `effects` are this run's acknowledged inputs, oldest first.
pub fn assess(goal: &Goal, effects: &[Effect], before: &ScreenState, fresh: &ScreenState, high_water: u64,
    apps: &[AppEntry]) -> Assessment {
    match goal {
        Goal::Launch { goal, popups, launch_request } =>
            launch_completion::assess_with_popups(goal, *popups, launch_request, before, fresh, high_water, apps),
        Goal::Brightness { percent } => brightness_level(fresh, *percent),
        Goal::MessageSent { to, body } => message_sent(effects, fresh, to, body),
        Goal::Photo => photo_taken(effects, fresh),
        Goal::RunState { noun, running } => run_state_shown(fresh, noun, *running),
        Goal::RowsDeleted { names } => rows_gone(effects, fresh, names),
        Goal::NoteSaved { name, text } => note_in_list(effects, fresh, name, text),
        Goal::EntrySaved { fields } => entry_in_app(effects, fresh, fields),
    }
}

// ---- named rows deleted -----------------------------------------------------------------------

/// Every label and text in a node's subtree, in tree order: a row's identity, compared in code.
fn subtree_strings(s: &ScreenState, id: NodeId) -> String {
    let mut out = Vec::new();
    let mut stack = vec![id];
    while let Some(i) = stack.pop() {
        if let Some(n) = node(s, i) {
            out.extend(n.label.iter().chain(n.text.iter()).cloned());
        }
        let kids: Vec<NodeId> = children(s, i).map(|n| n.id).collect();
        stack.extend(kids.into_iter().rev());
    }
    out.join("\n")
}

/// The row elements on `s` that show `name` as a match-only title (case-insensitive equality),
/// each as (row node, its resource id). Only what the planner's own view showed counts.
fn rows_named(s: &ScreenState, name: &str) -> Vec<(NodeId, String)> {
    let view = screen_filter::filter(s, &screen_filter::FilterContext { request: name, typed: &[] });
    view.elements.iter().filter(|e| e.package == s.package && (e.flags.clickable || e.flags.long_clickable) && !e.flags.editable
        && e.shown_content.iter().any(|c| c.trim().eq_ignore_ascii_case(name)))
        .filter_map(|e| Some((e.node, e.resource_id.clone()?))).collect()
}

/// The labeled controls of the app's own page (labels are code- or OS-owned, never content),
/// rows of `rid_` excluded: two screens with the same set show the same page around the list.
fn page_labels(s: &ScreenState, rid_: &str) -> Vec<(String, String)> {
    let view = screen_filter::filter(s, &screen_filter::FilterContext { request: "", typed: &[] });
    let mut labels: Vec<(String, String)> = view.elements.iter()
        .filter(|e| e.package == s.package && e.resource_id.as_deref() != Some(rid_))
        .filter_map(|e| Some((screen_filter::role_name(e.role).to_string(), e.label.clone()?))).collect();
    labels.sort();
    labels
}

/// Shown rows with this resource id in the screen's own package, top to bottom, as identities.
fn row_identities(s: &ScreenState, rid_: &str) -> Vec<String> {
    let mut rows: Vec<&Node> = s.nodes.iter().filter(|n| rid(n) == Some(rid_) && shown(n)
        && n.package.as_deref() == Some(s.package.as_str())).collect();
    rows.sort_by_key(|n| (n.bounds.y1, n.bounds.x1));
    rows.into_iter().map(|n| subtree_strings(s, n.id)).collect()
}

fn rows_gone(effects: &[Effect], fresh: &ScreenState, names: &[String]) -> Assessment {
    const NOT_YET: &str = "core checks a deletion on the list where each named row was seen: the same list, scrolled as it was, must show its other rows without the named ones";
    // Per name: the latest screen an input was planned on that showed it as a row.
    let mut latest: Option<(usize, String)> = None;
    let mut deleted: Vec<String> = Vec::new();
    for name in names {
        let Some((at, row, rid_)) = effects.iter().enumerate().rev().find_map(|(i, e)| {
            rows_named(&e.before, name).into_iter().next().map(|(row, rid_)| (i, row, rid_))
        }) else { return unknown(NOT_YET); };
        let before = &effects[at].before;
        // An owner-confirmed input in that app after the row was seen (the delete itself).
        if !effects[at..].iter().any(|e| e.confirmed && e.before.package == before.package) { return unknown(NOT_YET); }
        if !rows_named(fresh, name).is_empty() { return unknown(NOT_YET); }
        if let Some((_, r)) = &latest { if *r != rid_ { return unknown(NOT_YET); } }
        if latest.as_ref().is_none_or(|(i, _)| at >= *i) { latest = Some((at, rid_.clone())); }
        deleted.push(subtree_strings(before, row));
    }
    let Some((at, rid_)) = latest else { return unknown(NOT_YET); };
    let before = &effects[at].before;
    if !about(fresh, &before.package) || fresh.activity != before.activity
        || page_labels(fresh, &rid_) != page_labels(before, &rid_) { return unknown(NOT_YET); }
    let after = row_identities(fresh, &rid_);
    if after.iter().any(|r| deleted.contains(r)) { return unknown(NOT_YET); }
    let kept: Vec<String> = row_identities(before, &rid_).into_iter().filter(|r| !deleted.contains(r)).collect();
    // The rows that stayed keep their order at the top of the same list; rows the deletion
    // brought up from below may follow. With no other row seen, the list must now be empty.
    let same_list = if kept.is_empty() { after.is_empty() } else { after.starts_with(&kept) };
    if !same_list { return unknown(NOT_YET); }
    verified("code_validated_rows_removed", fresh, "after a confirmed input, the list where the named rows were seen shows its other rows without them")
}

// ---- note saved (Markor) ------------------------------------------------------------------------

fn markor_id(name: &str) -> String { format!("{MARKOR}:id/{name}") }
const MARKOR_EDITOR: &str = "net.gsantner.markor.activity.DocumentActivity";
const MARKOR_FILES: &str = "net.gsantner.markor.activity.MainActivity";

/// A file row titled exactly `name` in Markor's file list (the recorded caption form).
fn file_row_titled(s: &ScreenState, name: &str) -> bool {
    s.package == MARKOR && s.activity == MARKOR_FILES && s.nodes.iter().any(|n| rid(n) == Some(&markor_id("opoc_filesystem_item__root"))
        && shown(n) && !n.flags.selected && n.label.as_deref() == Some(format!("File {name} ").as_str()))
}

/// The editor shows exactly `text`, its title is the file's name (Markor shows it without the
/// extension) and Save is disabled: nothing unsaved.
fn saved_editor(s: &ScreenState, name: &str, text: &str) -> bool {
    if s.package != MARKOR || s.activity != MARKOR_EDITOR { return false; }
    let one = |id: &str| { let v: Vec<&Node> = s.nodes.iter().filter(|n| rid(n) == Some(&markor_id(id)) && shown(n)).collect(); if v.len() == 1 { Some(v[0]) } else { None } };
    let (Some(editor), Some(save), Some(title)) = (one("document__fragment__edit__highlighting_editor"), one("action_save"), one("note__activity__text_note_title")) else { return false; };
    let stem = name.rsplit_once('.').map_or(name, |(s, _)| s);
    editor.flags.editable && editor.text.as_deref().map(str::trim) == Some(text.trim()) && !save.flags.enabled
        && title.text.as_deref().is_some_and(|t| t == name || t == stem)
}

fn note_in_list(effects: &[Effect], fresh: &ScreenState, name: &str, text: &str) -> Assessment {
    const NOT_YET: &str = "core checks a saved note: the editor shows exactly the requested text with Save disabled (tap Save), then Markor's file list shows the new file's full name";
    let Some(typed) = effects.iter().position(|e| matches!(&e.action, Action::Type { text: t, .. } if t == text)
        && e.before.package == MARKOR && e.before.activity == MARKOR_EDITOR
        && e.target.and_then(|t| node(&e.before, t)).is_some_and(|n| rid(n) == Some(&markor_id("document__fragment__edit__highlighting_editor")))) else {
        return unknown(NOT_YET);
    };
    if effects[..typed].iter().any(|e| file_row_titled(&e.before, name)) { return unknown(NOT_YET); }
    if !effects[typed + 1..].iter().any(|e| saved_editor(&e.before, name, text)) { return unknown(NOT_YET); }
    if !about(fresh, MARKOR) || !file_row_titled(fresh, name) { return unknown(NOT_YET); }
    verified("code_validated_saved_file", fresh, "the editor held the requested text with nothing unsaved, and Markor's file list shows the new file")
}

// ---- entry saved (owner field values) ----------------------------------------------------------

/// Words of a field key or label, lower case: `preparationTime` → preparation, time.
fn key_words(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    for c in s.chars() {
        if c.is_ascii_uppercase() && !cur.is_empty() && cur.chars().last().is_some_and(|p| p.is_ascii_lowercase()) {
            out.push(std::mem::take(&mut cur));
        }
        if c.is_alphabetic() { cur.push(c.to_ascii_lowercase()); } else if !cur.is_empty() { out.push(std::mem::take(&mut cur)); }
    }
    if !cur.is_empty() { out.push(cur); }
    out.retain(|w| w.chars().count() >= 3);
    out
}

fn words_meet(a: &[String], b: &[String]) -> bool {
    a.iter().any(|x| b.iter().any(|y| x == y || (x.len().min(y.len()) >= 4 && (x.starts_with(y.as_str()) || y.starts_with(x.as_str())))))
}

/// The label of the field an effect typed into, from the planner's own view of that screen.
fn field_label(e: &Effect) -> Option<String> {
    let view = screen_filter::filter(&e.before, &screen_filter::FilterContext { request: "", typed: &[] });
    let t = e.target?;
    view.elements.iter().find(|x| x.node == t)?.label.clone()
}

const SAVE_WORDS: &[&str] = &["save", "done", "add", "create", "submit", "confirm", "ok"];

fn save_control(n: &Node) -> bool {
    !n.flags.editable && [n.label.as_deref(), n.text.as_deref()].into_iter().flatten().any(|t| {
        let t = t.trim().to_ascii_lowercase();
        SAVE_WORDS.contains(&t.as_str()) || t.strip_prefix("save ").is_some_and(|r| r.split_whitespace().count() <= 2)
    })
}

fn shows_display(s: &ScreenState, value: &str) -> bool {
    s.nodes.iter().any(|n| shown(n) && !n.flags.editable && [n.label.as_deref(), n.text.as_deref()].into_iter().flatten()
        .any(|t| crate::fields::norm(t) == value))
}

fn entry_in_app(effects: &[Effect], fresh: &ScreenState, fields: &[crate::fields::Field]) -> Assessment {
    use crate::fields::{entered, Entered};
    const NOT_YET: &str = "core checks a new entry: every value in the request entered exactly, a Save control tapped, and the app then showing the new entry's name";
    let mut first = usize::MAX;
    let mut last = 0;
    for f in fields {
        let Some(how) = entered(&f.value, &[], effects) else { return unknown(NOT_YET); };
        let Some(at) = how.at() else { return unknown(NOT_YET); };
        // A value typed into a field whose label names another key went to the wrong field.
        if let Entered::Typed(Some(i)) = how {
            if let Some(label) = field_label(&effects[i]) {
                let lw = key_words(&label);
                let own = words_meet(&key_words(&f.key), &lw);
                let other = fields.iter().any(|g| g.key != f.key && words_meet(&key_words(&g.key), &lw));
                if !own && other { return unknown(NOT_YET); }
            }
        }
        first = first.min(at);
        last = last.max(at);
    }
    let Some(save) = effects[last + 1..].iter().rev().find(|e| matches!(e.action, Action::Tap { .. })
        && e.target.and_then(|t| node(&e.before, t)).is_some_and(save_control)) else { return unknown(NOT_YET); };
    if !about(fresh, &save.before.package) { return unknown(NOT_YET); }
    // The form closed: its Save control is gone and no field still holds an entered value.
    let save_node = save.target.and_then(|t| node(&save.before, t)).expect("save target");
    let same_control = |n: &Node| n.resource_id == save_node.resource_id && n.label == save_node.label && n.text == save_node.text && shown(n);
    if fresh.activity == save.before.activity && fresh.nodes.iter().any(same_control) { return unknown(NOT_YET); }
    if fresh.nodes.iter().any(|n| n.flags.editable && shown(n) && n.text.as_deref()
        .is_some_and(|t| fields.iter().any(|f| crate::fields::norm(t) == f.value))) { return unknown(NOT_YET); }
    let name = &fields[0].value;
    if !shows_display(fresh, name) || effects[..=first].iter().any(|e| shows_display(&e.before, name)) { return unknown(NOT_YET); }
    verified("code_validated_saved_entry", fresh, "every value in the request was entered exactly, a Save control was tapped, the form closed and the app shows the new entry's name")
}

const SETTINGS: &str = "com.android.settings";

fn brightness_level(fresh: &ScreenState, percent: u8) -> Assessment {
    // Loop 2 Haiku (SystemBrightnessMax, state passed): three swipes on the dialog's slider read
    // back "screen unchanged" and the planner gave up there, never returning to the Display page.
    if !about(fresh, SETTINGS) && fresh.nodes.iter().any(|n| n.role == Role::Slider && shown(n)) {
        return unknown("this slider does not show its value, so an unchanged screen does not mean a swipe failed; after swiping, press back: Settings > Display shows the level in its Brightness level row");
    }
    if !about(fresh, SETTINGS) {
        return unknown("the brightness level is read on Settings > Display (Brightness level row); the slider does not show its value");
    }
    let labels = screen_filter::proven_settings_labels(fresh);
    let rows: Vec<&Node> = fresh.nodes.iter().filter(|n| labels.get(&n.id) == Some(&"Brightness level") && shown(n)).collect();
    let [row] = rows[..] else {
        return unknown("the brightness level is read on Settings > Display (Brightness level row); the slider does not show its value");
    };
    let summaries: Vec<&Node> = children(fresh, row.id).filter(|n| rid(n) == Some("android:id/summary") && shown(n)).collect();
    let [summary] = summaries[..] else { return unknown("Brightness level row shows no value"); };
    if summary.text.as_deref() == Some(format!("{percent}%").as_str()) {
        verified("code_validated_settings_value", fresh, "Settings > Display shows the requested Brightness level")
    } else {
        unknown("Brightness level does not show the requested value yet")
    }
}

const THREAD: &str = "com.simplemobiletools.smsmessenger.activities.ThreadActivity";
const CONVERSATIONS: &str = "com.simplemobiletools.smsmessenger.activities.MainActivity";

fn sms_id(name: &str) -> String { format!("{SMS}:id/{name}") }

/// `+1 (660) 487-6475` → `+16604876475`.
fn phone_digits(s: &str) -> Option<String> {
    let d: String = s.trim().chars().filter(|c| !" ()-".contains(*c)).collect();
    let plain = d.strip_prefix('+').unwrap_or(&d);
    (!plain.is_empty() && plain.bytes().all(|b| b.is_ascii_digit())).then_some(d)
}

fn same_number(a: &str, b: &str) -> bool {
    match (phone_digits(a), phone_digits(b)) {
        (Some(a), Some(b)) => a == b,
        _ => false,
    }
}

/// The thread title: the one untagged TextView directly in the thread coordinator, above the
/// message list (recorded ThreadActivity layout).
fn thread_title(s: &ScreenState) -> Option<&str> {
    let coordinator = s.nodes.iter().find(|n| rid(n) == Some(&sms_id("thread_coordinator")))?;
    let list = children(s, coordinator.id).find(|n| rid(n) == Some(&sms_id("thread_messages_list")))?;
    let titles: Vec<&Node> = children(s, coordinator.id).filter(|n| n.resource_id.is_none() && shown(n)
        && n.class_name.as_deref() == Some("android.widget.TextView") && n.bounds.y2 <= list.bounds.y1).collect();
    let [title] = titles[..] else { return None; };
    title.text.as_deref()
}

/// Message rows the owner sent: a message holder with exactly that body and no sender photo
/// (incoming rows carry `thread_message_sender_photo`).
fn outgoing_rows(s: &ScreenState, body: &str) -> usize {
    s.nodes.iter().filter(|h| rid(h) == Some(&sms_id("thread_message_holder")) && shown(h)).filter(|h| {
        let kids: Vec<&Node> = children(s, h.id).collect();
        !kids.iter().any(|k| rid(k) == Some(&sms_id("thread_message_sender_photo")))
            && kids.iter().filter(|k| rid(k) == Some(&sms_id("thread_message_body"))).count() == 1
            && kids.iter().any(|k| rid(k) == Some(&sms_id("thread_message_body")) && k.text.as_deref().map(str::trim) == Some(body))
    }).count()
}

fn composer_holds(s: &ScreenState, body: &str) -> bool {
    s.nodes.iter().any(|n| rid(n) == Some(&sms_id("thread_type_message")) && n.flags.editable
        && n.text.as_deref().map(str::trim) == Some(body) && n.label.as_deref().map(str::trim) != Some(body))
}

fn message_sent(effects: &[Effect], fresh: &ScreenState, to: &Recipient, body: &str) -> Assessment {
    const NOT_YET: &str = "no confirmed Send in this run has produced the requested message row on this screen";
    if !about(fresh, SMS) || fresh.activity != THREAD { return unknown(NOT_YET); }
    // The latest confirmed tap on the thread's Send control.
    let Some((at, send)) = effects.iter().enumerate().rev().find(|(_, e)| matches!(e.action, Action::Tap { .. }) && e.confirmed
        && e.target.and_then(|t| node(&e.before, t)).is_some_and(|n| rid(n) == Some(&sms_id("thread_send_message")))) else {
        return unknown(NOT_YET);
    };
    let emptied = send.readback.as_ref().is_some_and(|r| r.outcome == ReadbackOutcome::Changed && r.signals.contains(&"field_emptied"));
    if !emptied || send.before.activity != THREAD || !composer_holds(&send.before, body) { return unknown(NOT_YET); }
    if outgoing_rows(fresh, body) <= outgoing_rows(&send.before, body) { return unknown(NOT_YET); }
    let (Some(title), Some(title_before)) = (thread_title(fresh), thread_title(&send.before)) else { return unknown(NOT_YET); };
    if title != title_before { return unknown(NOT_YET); }
    match to {
        Recipient::Number(number) => {
            if !same_number(title, number) { return unknown("the thread title does not show the requested number"); }
        }
        Recipient::MostRecent => {
            if !entered_from_top_conversation(&effects[..at], title) {
                return unknown("this thread was not opened from the top of the conversation list in this run");
            }
        }
    }
    verified("code_validated_sent_row", fresh, "a new outgoing row with the requested text appeared in the requested thread after the confirmed Send")
}

/// Pixels the top conversation row may sit below the list's top edge (settling).
const TOP_ROW_SLACK: i32 = 16;

/// The thread was opened in this run by tapping the first conversation row of the conversation
/// list (newest first in the recorded app), with the list unscrolled in this run, the row in its
/// recorded shape (address, preview, date; a pinned or otherwise different row does not count)
/// and its address equal to the thread title.
fn entered_from_top_conversation(effects: &[Effect], title: &str) -> bool {
    let Some((i, open)) = effects.iter().enumerate().rev().find(|(_, e)| e.before.package == SMS && e.before.activity == CONVERSATIONS
        && matches!(e.action, Action::Tap { .. })) else { return false; };
    if effects[..i].iter().any(|e| e.before.package == SMS && e.before.activity == CONVERSATIONS
        && matches!(e.action, Action::Scroll { .. } | Action::Swipe { .. })) { return false; }
    let s = &open.before;
    let Some(row) = open.target.and_then(|t| node(s, t)) else { return false; };
    let Some(list) = s.nodes.iter().find(|n| rid(n) == Some(&sms_id("conversations_list")) && shown(n)) else { return false; };
    let rows: Vec<&Node> = children(s, list.id).filter(|n| rid(n) == Some(&sms_id("conversation_frame")) && shown(n)).collect();
    let top = rows.iter().min_by_key(|n| n.bounds.y1);
    if row.parent != Some(list.id) || rid(row) != Some(&sms_id("conversation_frame")) || top.map(|t| t.id) != Some(row.id)
        // At the list's top, allowing the few pixels of settling recorded in the Opus run (299
        // against 296). The scroll condition above is what rules out a moved list.
        || row.bounds.y1 < list.bounds.y1 || row.bounds.y1 - list.bounds.y1 > TOP_ROW_SLACK { return false; }
    let kids: Vec<&Node> = children(s, row.id).collect();
    let mut ids: Vec<&str> = kids.iter().filter_map(|k| rid(k)).collect();
    ids.sort_unstable();
    let expected = [sms_id("conversation_address"), sms_id("conversation_body_short"), sms_id("conversation_date")];
    if kids.len() != 3 || ids != expected.iter().map(String::as_str).collect::<Vec<_>>() { return false; }
    kids.iter().find(|k| rid(k) == Some(&sms_id("conversation_address")))
        .and_then(|k| k.text.as_deref()).is_some_and(|address| address.trim() == title.trim())
}

// ---- run state (pause / start) --------------------------------------------------------------------

const STOPPED_CONTROLS: &[&str] = &["start", "resume", "play"];
const RUNNING_CONTROLS: &[&str] = &["pause", "stop"];

fn run_state_shown(fresh: &ScreenState, noun: &str, running: bool) -> Assessment {
    const NOT_YET: &str = "core checks the state on the app's own page for it: a selected tab or page with that name, and only the opposite control (Start without Pause when paused, Pause without Start when running)";
    let own = |n: &&Node| shown(n) && n.package.as_deref() == Some(fresh.package.as_str());
    let named = |n: &Node| [n.label.as_deref(), n.text.as_deref()].into_iter().flatten().any(|t| t.trim().eq_ignore_ascii_case(noun));
    if fresh.package == "com.android.systemui" || !fresh.nodes.iter().filter(own).any(|n| n.flags.selected && named(n)) {
        return unknown(NOT_YET);
    }
    let controls: Vec<String> = fresh.nodes.iter().filter(own).filter(|n| n.role == Role::Button && n.flags.clickable && n.flags.enabled)
        .filter_map(|n| n.label.as_deref().or(n.text.as_deref())).map(|t| t.trim().to_ascii_lowercase()).collect();
    let has = |set: &[&str]| controls.iter().any(|c| set.contains(&c.as_str()));
    let (want, not) = if running { (RUNNING_CONTROLS, STOPPED_CONTROLS) } else { (STOPPED_CONTROLS, RUNNING_CONTROLS) };
    if has(want) && !has(not) {
        verified("code_validated_run_state", fresh, if running { "the page shows a Pause control and no Start control: it is running" }
            else { "the page shows a Start control and no Pause control: it is not running" })
    } else {
        unknown(NOT_YET)
    }
}

const CAMERA: &str = "com.android.camera2";

fn photo_taken(effects: &[Effect], fresh: &ScreenState) -> Assessment {
    const NOT_YET: &str = "no single shutter press in this run has produced a new photo thumbnail on this screen";
    let thumbnail = |s: &ScreenState| s.nodes.iter().any(|n| rid(n) == Some("com.android.camera2:id/rounded_thumbnail_view") && shown(n)
        && n.package.as_deref() == Some(CAMERA));
    let presses: Vec<&Effect> = effects.iter().filter(|e| matches!(e.action, Action::Tap { .. } | Action::LongPress { .. })
        && e.target.and_then(|t| node(&e.before, t)).is_some_and(|n| rid(n) == Some("com.android.camera2:id/shutter_button")
            && n.package.as_deref() == Some(CAMERA))).collect();
    let [press] = presses[..] else { return unknown(NOT_YET); };
    if !about(fresh, CAMERA) || !thumbnail(fresh) || thumbnail(&press.before) { return unknown(NOT_YET); }
    verified("code_validated_new_photo_thumbnail", fresh, "one shutter press, and a new last-photo thumbnail is shown")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sms_app() -> Vec<AppEntry> {
        vec![AppEntry { label: "Simple SMS Messenger".into(), package: SMS.into(), activity: None }]
    }

    #[test]
    fn grammar_recognizes_only_the_full_forms() {
        assert_eq!(recognize("Turn brightness to the max value.", &[]), Some(Goal::Brightness { percent: 100 }));
        assert_eq!(recognize("set the screen brightness to minimum", &[]), Some(Goal::Brightness { percent: 0 }));
        assert_eq!(recognize("Take one photo.", &[]), Some(Goal::Photo));
        assert_eq!(recognize("take a picture with the camera", &[]), Some(Goal::Photo));
        assert_eq!(recognize("Send a text message using Simple SMS Messenger to +16604876475 with message: The squeaky wheel gets the grease.", &sms_app()),
            Some(Goal::MessageSent { to: Recipient::Number("+16604876475".into()), body: "The squeaky wheel gets the grease.".into() }));
        assert_eq!(recognize("Reply to the most recent text message using Simple SMS Messenger with message: Hi Ana", &sms_app()),
            Some(Goal::MessageSent { to: Recipient::MostRecent, body: "Hi Ana".into() }));
        for other in ["turn brightness to the max value and open camera", "take two photos", "take one photo and send it",
            "brightness max", "send a text message to Ana with message: hi", "send a text message to +16604876475",
            "take one photo\nthen delete it", "reply to Ana with message: hi"] {
            assert_eq!(recognize(other, &sms_app()), None, "{other}");
        }
    }

    #[test]
    fn reply_to_a_number_with_the_app_named_after_the_body() {
        assert_eq!(recognize("Reply to +16604876475 with message: The squeaky wheel gets the grease. in Simple SMS Messenger", &sms_app()),
            Some(Goal::MessageSent { to: Recipient::Number("+16604876475".into()), body: "The squeaky wheel gets the grease.".into() }));
        assert_eq!(recognize("Reply to +16604876475 using Simple SMS Messenger with message: Hi", &sms_app()),
            Some(Goal::MessageSent { to: Recipient::Number("+16604876475".into()), body: "Hi".into() }));
        // Another app's name stays part of the body.
        assert_eq!(recognize("Reply to +16604876475 with message: see you in Messages", &sms_app()),
            Some(Goal::MessageSent { to: Recipient::Number("+16604876475".into()), body: "see you in Messages".into() }));
    }

    #[test]
    fn body_keeps_the_owners_case_and_punctuation() {
        let goal = recognize("SEND A TEXT MESSAGE to +15550100 with message: Ünïcode OK.", &[]);
        assert_eq!(goal, Some(Goal::MessageSent { to: Recipient::Number("+15550100".into()), body: "Ünïcode OK.".into() }));
        // A number written with spaces is not one token: not recognized, ordinary verifier.
        assert_eq!(recognize("send a text message to +1 555 0100 with message: hi", &[]), None);
    }
}
