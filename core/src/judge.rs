//! The deny-only judge (core/README.md §8). Before every state-changing action core makes one
//! short `judge` call with the owner's request, the action in plain words, the source of every
//! value in it and the last few history lines. It never sees content: the action words use
//! control labels and roles only, and history lines are core-computed outcomes.
//!
//! `allow` changes nothing: the code rules, the taint rule and the confirmation list still
//! apply. `block` halts the run with the reason. A reply that is not one of the two shapes is a
//! block, so a fooled or broken judge can only make the assistant more cautious.

use serde_json::Value;

use crate::screen_filter::FilteredScreen;
use crate::types::*;

pub const JUDGE_PROMPT: &str = include_str!("../prompts/judge.txt");
pub const JUDGE_TOKENS: u32 = 100;
const HISTORY_LINES: usize = 4;
const MAX_REASON_CHARS: usize = 200;
pub const UNREADABLE: &str = "the safety check's reply was unreadable";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Allow,
    Block(String),
}

/// Actions that can change state. `scroll`, `wait`, `back`, `read`, `answer`, `done` and
/// `give_up` do not reach the judge.
pub fn state_changing(action: &Action) -> bool {
    matches!(action, Action::OpenApp { .. } | Action::Tap { .. } | Action::LongPress { .. }
        | Action::Type { .. } | Action::Swipe { .. } | Action::Home)
}

/// What the target is, from code-owned facts only: its label, a reader pick, an owner match, or
/// its role. Never its withheld text.
fn target_words(view: &FilteredScreen, id: u32) -> String {
    let Some(e) = view.element(id) else { return "an element no longer on screen".into() };
    let role = crate::screen_filter::role_name(e.role);
    if let Some(l) = &e.label { return format!("the {role} labelled {l:?}"); }
    if view.reader_picks.contains(&id) { return format!("the {role} the reader chose from hidden content"); }
    if !e.shown_content.is_empty() { return format!("the {role} whose text matches the request"); }
    format!("an unlabelled {role}")
}

/// The action in plain words, as the judge sees it.
pub fn plain_words(action: &Action, view: &FilteredScreen, apps: &[AppEntry], package: &str) -> String {
    let dir = |d: &Direction| format!("{d:?}").to_lowercase();
    match action {
        Action::OpenApp { app } => match crate::actions::match_app(app, apps) {
            Some(a) => format!("open the app {:?} ({})", a.label, a.package),
            None => format!("open an app named {app:?}"),
        },
        Action::Tap { target } => format!("tap {} in {package}", target_words(view, *target)),
        Action::LongPress { target } => format!("long-press {} in {package}", target_words(view, *target)),
        Action::Type { target, text, submit } => format!("type {text:?} into {} in {package}{}",
            target_words(view, *target), if *submit { " and submit it" } else { "" }),
        Action::Swipe { target, direction } => format!("swipe {} on {} in {package}", dir(direction), target_words(view, *target)),
        Action::Home => "go to the home screen".into(),
        other => crate::actions::describe(other),
    }
}

pub fn messages(request: &str, action_words: &str, values: &[Tagged], history: &[String]) -> Vec<ChatMessage> {
    let sources = if values.is_empty() { "none".to_string() } else {
        values.iter().map(|v| format!("{:?} from {}", v.value, crate::reader::source_tag(&v.source))).collect::<Vec<_>>().join("; ")
    };
    let recent = &history[history.len().saturating_sub(HISTORY_LINES)..];
    let recent = if recent.is_empty() { "(none)".to_string() } else { recent.join("\n") };
    vec![
        ChatMessage { role: "system".into(), content: JUDGE_PROMPT.into() },
        ChatMessage { role: "user".into(), content: format!(
            "Owner request: {request}\nProposed action: {action_words}\nValue sources: {sources}\nLast steps:\n{recent}") },
    ]
}

/// `{"verdict":"allow"}` or `{"verdict":"block","reason":"..."}` (a ```json fence tolerated).
/// Anything else blocks.
pub fn parse(text: &str) -> Verdict {
    let t = text.trim();
    let t = t.strip_prefix("```json").or_else(|| t.strip_prefix("```"))
        .map_or(t, |r| r.trim_end().strip_suffix("```").unwrap_or(r)).trim();
    let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(t) else { return Verdict::Block(UNREADABLE.into()) };
    if obj.keys().any(|k| k != "verdict" && k != "reason") {
        return Verdict::Block(UNREADABLE.into());
    }
    match obj.get("verdict").and_then(Value::as_str) {
        Some("allow") => Verdict::Allow,
        Some("block") => {
            let reason: String = obj.get("reason").and_then(Value::as_str).unwrap_or("")
                .chars().filter(|c| !c.is_control()).take(MAX_REASON_CHARS).collect();
            let reason = reason.trim();
            Verdict::Block(if reason.is_empty() { "no reason given".into() } else { reason.into() })
        }
        _ => Verdict::Block(UNREADABLE.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_allow_allows() {
        assert_eq!(parse(r#"{"verdict":"allow"}"#), Verdict::Allow);
        assert_eq!(parse("```json\n{\"verdict\":\"allow\",\"reason\":\"fits\"}\n```"), Verdict::Allow);
        assert_eq!(parse(r#"{"verdict":"block","reason":"Not asked for."}"#), Verdict::Block("Not asked for.".into()));
        assert_eq!(parse(r#"{"verdict":"block"}"#), Verdict::Block("no reason given".into()));
        for bad in ["allow", "", r#"{"verdict":"ALLOW"}"#, r#"{"verdict":"allow","action":"tap"}"#, r#"[{"verdict":"allow"}]"#] {
            assert_eq!(parse(bad), Verdict::Block(UNREADABLE.into()), "{bad}");
        }
    }

    #[test]
    fn read_only_actions_do_not_reach_the_judge() {
        for a in [Action::Back, Action::Wait, Action::Scroll { target: None, direction: Direction::Down },
            Action::Done { evidence: String::new() }, Action::Answer { text: String::new() }] {
            assert!(!state_changing(&a));
        }
        assert!(state_changing(&Action::Tap { target: 1 }) && state_changing(&Action::Home));
    }
}
