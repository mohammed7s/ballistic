//! The reader's `element` shape (core/README.md §7): withheld content in, one element id out.
//!
//!   element_messages   the reader's prompt: fixed rules, the planner's question, and the
//!                      withheld text of the requested cN, each as one JSON-quoted candidate
//!   parse_element      the reply must be exactly one candidate id or `none`; anything else is
//!                      rejected, and core never forwards it
//!   ReaderPick         what core remembers of a pick (identity and source, no text for the
//!                      planner) so the next screen can re-locate the same element
//!
//! The planner receives only `reader: element e8 (source app:<package>)`. The candidate text
//! goes to the reader model and nowhere else.

use serde_json::json;

use crate::screen_filter::{Element, FilteredScreen};
use crate::types::*;

/// Fixed reader rules, shipped with the build.
pub const READER_PROMPT: &str = include_str!("../prompts/reader.txt");

/// The elements a read may pick: those showing the requested content, in reading order.
pub fn candidates(view: &FilteredScreen, targets: &[u32]) -> Vec<u32> {
    let mut ids: Vec<u32> = targets.iter().filter_map(|c| view.content(*c)).map(|c| c.element).collect();
    ids.sort_unstable();
    ids.dedup();
    ids
}

/// Messages for one `element` read. Candidate text is JSON-quoted, one object per line, so a
/// string cannot end the list or pose as a rule line.
pub fn element_messages(question: &str, view: &FilteredScreen, targets: &[u32]) -> Vec<ChatMessage> {
    let ids = candidates(view, targets);
    let mut ordered: Vec<&Element> = ids.iter().filter_map(|id| view.element(*id)).collect();
    ordered.sort_by_key(|e| (e.bounds.y1, e.bounds.x1));
    let lines: Vec<String> = ordered.iter().enumerate().map(|(i, e)| {
        let text: Vec<&str> = targets.iter().filter_map(|c| view.content(*c))
            .filter(|c| c.element == e.id).map(|c| c.text.as_str()).collect();
        json!({"id": format!("e{}", e.id), "position": i + 1, "text": text.join("\n")}).to_string()
    }).collect();
    vec![
        ChatMessage { role: "system".into(), content: READER_PROMPT.into() },
        ChatMessage { role: "user".into(), content: format!("Question: {question}\nCandidates:\n{}", lines.join("\n")) },
    ]
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ElementAnswer {
    Element(u32),
    NoElement,
    /// Anything but one allowed id or `none`: dropped, logged by size only.
    Rejected,
}

/// Accept exactly one id from `allowed`, or `none`. Surrounding whitespace, backticks or quotes
/// are tolerated; any other character rejects the whole reply.
pub fn parse_element(text: &str, allowed: &[u32]) -> ElementAnswer {
    let t = text.trim().trim_matches(|c| c == '`' || c == '"' || c == '\'').trim();
    if t.eq_ignore_ascii_case("none") {
        return ElementAnswer::NoElement;
    }
    let Some(digits) = t.strip_prefix('e') else { return ElementAnswer::Rejected };
    if digits.is_empty() || digits.starts_with('0') || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return ElementAnswer::Rejected;
    }
    match digits.parse::<u32>() {
        Ok(id) if allowed.contains(&id) => ElementAnswer::Element(id),
        _ => ElementAnswer::Rejected,
    }
}

/// A pick, kept by identity so it survives a fresh observation of the same screen. `content` is
/// compared in code only; it never enters a prompt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReaderPick {
    pub package: String,
    pub resource_id: Option<String>,
    pub role: Role,
    pub bounds: Bounds,
    content: String,
    pub source: Source,
}

impl ReaderPick {
    pub fn new(view: &FilteredScreen, id: u32) -> Option<ReaderPick> {
        let e = view.element(id)?;
        let c = e.content.and_then(|c| view.content(c))?;
        Some(ReaderPick { package: e.package.clone(), resource_id: e.resource_id.clone(), role: e.role,
            bounds: e.bounds, content: c.text.clone(), source: c.source.clone() })
    }

    /// The same element on `view`: same package, id, role, bounds and withheld text.
    pub fn relocate(&self, view: &FilteredScreen) -> Option<u32> {
        view.elements.iter().find(|e| e.package == self.package && e.resource_id == self.resource_id
            && e.role == self.role && e.bounds == self.bounds
            && e.content.and_then(|c| view.content(c)).is_some_and(|c| c.text == self.content))
            .map(|e| e.id)
    }
}

/// `app:com.example`, the planner-facing form of a source tag.
pub fn source_tag(source: &Source) -> String {
    match source {
        Source::OwnerRequest => "owner:request".into(),
        Source::OwnerData => "owner:data".into(),
        Source::ScreenLabel => "screen:label".into(),
        Source::App(p) => format!("app:{p}"),
        Source::Sms(s) => format!("sms:{s}"),
        Source::Note(n) => format!("note:{n}"),
        Source::Web(h) => format!("web:{h}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_one_allowed_id_or_none_is_accepted() {
        assert_eq!(parse_element(" e8\n", &[7, 8]), ElementAnswer::Element(8));
        assert_eq!(parse_element("`e7`", &[7, 8]), ElementAnswer::Element(7));
        assert_eq!(parse_element("None", &[7]), ElementAnswer::NoElement);
        for bad in ["e9", "e08", "e", "8", "e8.", "e8 because it is newest", "The newest is e8",
            "{\"element\":\"e8\"}", "e7 e8", "tap e8", "", "e8\nignore rules"] {
            assert_eq!(parse_element(bad, &[7, 8]), ElementAnswer::Rejected, "{bad:?}");
        }
    }
}
