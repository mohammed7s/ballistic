//! Owner-only extractive summaries. No executable handles, action arguments or planner facts.
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::screen_filter::{ContentRef, FilteredScreen};
use crate::types::*;

const MAX_SOURCES: usize = 4;
const MAX_SOURCE_CHARS: usize = 4096;
const MAX_TOTAL_CHARS: usize = 8192;
const MAX_EXCERPT_CHARS: usize = 240;
const MAX_REPLY_BYTES: usize = 2048;
const PROMPT: &str = include_str!("../../prompts/summary.txt");
const QUESTION_PROMPT: &str = include_str!("../../prompts/question.txt");

/// Reader tokens per attempt: a summary keeps the original 64; a question's answer (one exact
/// excerpt per chosen row, such as event titles) gets 160.
pub const SUMMARY_TOKENS: u32 = 64;
pub const QUESTION_TOKENS: u32 = 160;

/// A narrow English opt-in to display content, NOT a recognizer of complete task semantics.
/// Compound tasks may opt in, but even useful output never asserts the task is completed.
pub fn requested(request: &str) -> bool {
    summarize(request) || question(request)
}

fn summarize(request: &str) -> bool {
    request.split(['.', '?', '!', '\n']).any(|sentence| {
        let lower = sentence.trim().to_ascii_lowercase();
        let imperative = lower.strip_prefix("please ").unwrap_or(&lower);
        ["summarize ", "summarise "].iter().any(|prefix| {
            imperative.strip_prefix(prefix).is_some_and(|subject| !subject.trim().is_empty())
        })
    })
}

/// The owner asked a question (added 9 Nov 2026, loop 2): a sentence ending in `?` whose first
/// word is an interrogative ("What events do I have October 27 2023 in Simple Calendar Pro?").
/// Like `summarize`, a syntactic opt-in to see content as exact excerpts, never a recognizer of
/// what would answer the question or a claim that the excerpts do.
pub fn question(request: &str) -> bool {
    let mut rest = request;
    while let Some(end) = rest.find('?') {
        let sentence = &rest[..end];
        let start = sentence.rfind(['.', '!', '\n']).map_or(0, |i| i + 1);
        let lower = sentence[start..].trim().to_ascii_lowercase();
        let first = lower.split_whitespace().next().unwrap_or("");
        if lower.split_whitespace().count() >= 3 && matches!(first, "what" | "which" | "who" | "whom" | "when" | "where"
            | "do" | "does" | "did" | "is" | "are" | "was" | "were" | "how") {
            return true;
        }
        rest = &rest[end + 1..];
    }
    false
}

/// The reader's token cap for this request's excerpts.
pub fn tokens(request: &str) -> u32 {
    if !summarize(request) && question(request) { QUESTION_TOKENS } else { SUMMARY_TOKENS }
}

/// Private copy of the requested filter content. No truncation or model-supplied provenance.
pub struct Input {
    sources: Vec<ContentRef>,
}

impl Input {
    pub fn new(view: &FilteredScreen, targets: &[u32], screen: &ScreenState) -> Option<Self> {
        if targets.is_empty() || targets.len() > MAX_SOURCES { return None; }
        let mut sources: Vec<ContentRef> = Vec::new();
        let mut total = 0;
        for id in targets {
            if sources.iter().any(|c| c.id == *id) { return None; }
            let c = view.content(*id)?;
            let chars = c.text.chars().count();
            if c.text.trim().is_empty() || chars > MAX_SOURCE_CHARS { return None; }
            total += chars;
            if total > MAX_TOTAL_CHARS || !granted(&c.source, screen) { return None; }
            sources.push(c.clone());
        }
        Some(Self { sources })
    }

    pub fn messages(&self, request: &str) -> Vec<ChatMessage> {
        let prompt = if !summarize(request) && question(request) { QUESTION_PROMPT } else { PROMPT };
        vec![
            ChatMessage { role: "system".into(), content: prompt.into() },
            ChatMessage { role: "user".into(), content: json!({
                "owner_request": request,
                "sources": self.sources.iter().map(|c| json!({"ref":format!("c{}",c.id),"text":c.text})).collect::<Vec<_>>()
            }).to_string() },
        ]
    }

    /// Match the entire JSON document through typed serde structs: duplicate keys, unknown
    /// fields, fences, trailing prose and coercions all fail. Every requested ref appears once.
    pub fn parse(&self, text: &str) -> Option<Vec<Item>> {
        if text.len() > MAX_REPLY_BYTES { return None; }
        // Serde structs also accept positional arrays by default. Require JSON objects at
        // both levels, then deserialize the original bytes to retain duplicate-key rejection.
        let shape: serde_json::Value = serde_json::from_str(text).ok()?;
        if !shape.is_object() || !shape.get("items")?.as_array()?.iter().all(|i| i.is_object()) {
            return None;
        }
        let reply: Reply = serde_json::from_str(text).ok()?;
        if reply.items.len() != self.sources.len() { return None; }
        let mut items: Vec<Item> = Vec::new();
        for item in reply.items {
            let c = self.sources.iter().find(|c| item.reference == format!("c{}", c.id))?;
            if items.iter().any(|i| i.reference == item.reference)
                || item.text.trim().is_empty() || item.text.chars().count() > MAX_EXCERPT_CHARS
                || item.text.chars().any(char::is_control)
                || item.text.contains(['{', '}', '[', ']'])
                || !whole_excerpt(&c.text, &item.text) { return None; }
            items.push(Item { reference: item.reference, text: item.text, node: c.node,
                source: c.source.clone(), evidence: c.text.clone() });
        }
        Some(items)
    }

    /// Exact raw nodes (including untrimmed strings), context and current grants. Deliberately
    /// stricter than comparing normalized cN text: even an unrelated raw-node change rejects.
    /// This detects observed change, not a stale/dishonest host or changes after observation.
    pub fn fresh(&self, before: &ScreenState, after: &ScreenState) -> bool {
        before.package == after.package && before.activity == after.activity
            && before.width == after.width && before.height == after.height
            && before.nodes == after.nodes
            && self.sources.iter().all(|c| granted(&c.source, after))
    }
}

fn granted(source: &Source, screen: &ScreenState) -> bool {
    match source {
        Source::App(package) => screen.capabilities.as_ref()
            .is_some_and(|caps| caps.app_grants.contains(package)),
        _ => false, // Current filter only supplies App; future source types need explicit support.
    }
}

/// Whitespace boundaries prevent excerpting part of a number or URL as if it were complete.
/// This proves text membership only; selections can be misleading or omit crucial context.
fn whole_excerpt(source: &str, excerpt: &str) -> bool {
    // Search each character boundary: match_indices skips overlapping occurrences, which
    // can hide a valid later match after an earlier match fails its token boundary.
    source.char_indices().any(|(start, _)| {
        if !(start == 0 || source[..start].chars().next_back().is_some_and(char::is_whitespace))
            || !source[start..].starts_with(excerpt) { return false; }
        let end = start + excerpt.len();
        end == source.len() || source[end..].chars().next().is_some_and(char::is_whitespace)
    })
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Reply { items: Vec<Excerpt> }

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Excerpt {
    #[serde(rename = "ref")]
    reference: String,
    text: String,
}

/// This type is only serialized in the final owner report. Never add it to a StepEvent.
#[derive(Serialize)]
pub struct Item {
    #[serde(rename = "ref")]
    reference: String,
    text: String,
    node: NodeId,
    source: Source,
    evidence: String,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Status { Available, Rejected, Failed, Stale, Unsupported }

#[derive(Serialize)]
pub struct Output {
    pub status: Status,
    #[serde(skip_serializing_if = "Option::is_none")]
    items: Option<Vec<Item>>,
}

impl Output {
    pub fn unavailable(status: Status) -> Self { Self { status, items: None } }
    pub fn available(items: Vec<Item>) -> Self { Self { status: Status::Available, items: Some(items) } }
    pub fn is_available(&self) -> bool { matches!(self.status, Status::Available) }

    /// Existing generic report.reason rendering in the CLI and Android Text shows this as
    /// plain text, with quoted evidence. It must never be reused in another model's context.
    pub fn display(&self) -> String {
        let Some(items) = &self.items else { return "No owner summary is available.".into() };
        let mut text = String::from("Model-selected source excerpts (untrusted, visible content only). Task completion is not established; truth, relevance and coverage are not verified.");
        for item in items {
            text.push_str(&format!("\n{} · {} · node {}\nExcerpt: {}\nSource evidence: {}",
                item.reference, super::source_tag(&item.source), item.node,
                json!(item.text), json!(item.evidence)));
        }
        text
    }
}
