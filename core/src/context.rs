//! Context guard (core/README.md §12, "Context guard"). A host may advertise how many tokens the
//! loaded model holds, prompt plus output ([`ModelLimits::context_tokens`]); a compiled NPU
//! bundle has a fixed KV cache and cannot take more. Before every model call core measures the
//! prompt against that limit. When prompt + `max_tokens` would not fit, core applies bounded
//! reductions in a fixed order, re-measuring after each, and stops at the first that fits:
//!
//!   (a) history        the planner's history lines, oldest first, down to the newest two
//!   (b) old_reader_facts  reader fact sets recorded before the previous step (the planner)
//!   (c) chat_history   earlier conversation turns, oldest first, down to the newest two
//!   (d) max_tokens     the output budget, down to the role's minimum
//!   (e) refuse         the model is not asked; the caller reports
//!                      "prompt does not fit this model's N-token context"
//!
//! A reduction only removes lines core itself put in the prompt. Nothing is added, and the
//! rules, the schema, the owner's request, the screen and the current step's correction are
//! never touched. Truncation inside the runtime is never relied on. With no limit advertised
//! (the harness, connected HTTP models) nothing is measured or changed and nothing is logged.
//!
//! Measuring: the host's exact count when it supplies one ([`Models::count_tokens`]), else
//! [`estimate`]: every ASCII digit one token, every other UTF-8 byte a third of a token (rounded
//! up per message), plus [`MESSAGE_FRAMING_TOKENS`] per message and [`REPLY_FRAMING_TOKENS`] for
//! the reply header. Checked against 951 recorded harness calls (gemini-flash-lite-latest usage,
//! a tokenizer related to but not the same as Gemma 4's): never below the reported count, about
//! 1.22x it for planner prompts (1.13x–1.32x). It has not been checked against the Gemma 4 or
//! Qwen tokenizers themselves.

use serde::{Deserialize, Serialize};

use crate::ports::*;
use crate::types::*;

pub const MESSAGE_FRAMING_TOKENS: u32 = 8;
pub const REPLY_FRAMING_TOKENS: u32 = 8;
/// History lines and chat turns never reduced below this many (the newest ones).
pub const HISTORY_FLOOR: usize = 2;
pub const CHAT_HISTORY_FLOOR: usize = 2;

/// The smallest output budget (d) may leave for a role; never more than was asked for. Reader
/// replies are fixed shapes sized to their cap, so a reader call is never shortened.
pub fn min_output(role: ModelRole, requested: u32) -> u32 {
    let floor = match role {
        ModelRole::Planner => 256,
        ModelRole::Chat => 200,
        ModelRole::Verifier => 200,
        ModelRole::Judge => 64,
        ModelRole::Reader => requested,
    };
    floor.min(requested)
}

/// Conservative prompt size in tokens; see the module comment for the rule and its evidence.
pub fn estimate(messages: &[ChatMessage]) -> u32 {
    let tokens: u64 = messages.iter().map(|m| {
        let digits = m.content.bytes().filter(u8::is_ascii_digit).count() as u64;
        let other = m.content.len() as u64 - digits;
        digits + other.div_ceil(3) + MESSAGE_FRAMING_TOKENS as u64
    }).sum::<u64>() + REPLY_FRAMING_TOKENS as u64;
    tokens.min(u32::MAX as u64) as u32
}

/// Which lines the caller may drop: how many of the oldest history lines and chat turns lie
/// above their floors, and whether reader fact sets older than the previous step exist.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Room {
    pub history_lines: usize,
    pub old_facts: bool,
    pub chat_turns: usize,
}

/// What the caller's prompt builder must leave out: the oldest `history_lines` history lines,
/// the old reader fact sets if `old_facts`, the oldest `chat_turns` chat turns.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Trim {
    pub history_lines: usize,
    pub old_facts: bool,
    pub chat_turns: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Reduction {
    History,
    OldReaderFacts,
    ChatHistory,
    MaxTokens,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CountedBy {
    /// [`estimate`].
    Estimate,
    /// The host's tokenizer ([`Models::count_tokens`]).
    Host,
}

/// One model call as the guard saw it. Logged in the step event's `context_guard` list (and in
/// a refusal's report); numbers only, never prompt text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Check {
    pub role: ModelRole,
    pub context_tokens: u32,
    /// Prompt size as sent (or, for a refusal, after every reduction).
    pub prompt_estimate: u32,
    /// Prompt size before any reduction.
    pub full_prompt_estimate: u32,
    pub counted_by: CountedBy,
    pub requested_max_tokens: u32,
    pub max_tokens: u32,
    /// In the order applied; empty when the prompt fitted as built.
    pub reductions: Vec<Reduction>,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub history_lines_dropped: usize,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub chat_turns_dropped: usize,
    pub fits: bool,
    /// What the host reported after the call; absent when it reported nothing (zero).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub prompt_tokens: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub completion_tokens: Option<u32>,
}

fn is_zero(n: &usize) -> bool { *n == 0 }

/// `4096` → `4,096`, for the owner-facing refusal.
pub fn thousands(n: u32) -> String {
    let digits = n.to_string();
    let mut out = String::new();
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 { out.push(','); }
        out.push(c);
    }
    out
}

impl Check {
    /// The report's reason when the call was refused.
    pub fn refusal(&self) -> String {
        let role = format!("{:?}", self.role).to_lowercase();
        format!("prompt does not fit this model's {}-token context ({role} prompt about {} tokens plus at least {} output tokens after the bounded reductions)",
            thousands(self.context_tokens), self.prompt_estimate, min_output(self.role, self.requested_max_tokens))
    }
}

pub enum Sent {
    Completion(Completion),
    /// The prompt did not fit even after every reduction; no model call was made.
    Refused,
}

/// One guarded model call. `build` makes the prompt for a [`Trim`]; it is called once when no
/// limit is advertised. Returns what happened and, when a limit was advertised, the check to log.
pub fn complete<M: Models + ?Sized>(
    host: &mut M,
    role: ModelRole,
    max_tokens: u32,
    room: Room,
    build: impl Fn(&Trim) -> Vec<ChatMessage>,
) -> PortResult<(Sent, Option<Check>)> {
    let Some(limit) = host.limits(role)?.context_tokens else {
        return Ok((Sent::Completion(host.complete(role, &build(&Trim::default()), max_tokens)?), None));
    };
    let mut measure = |messages: &[ChatMessage]| -> PortResult<(u32, CountedBy)> {
        Ok(match host.count_tokens(role, messages)? {
            Some(n) => (n, CountedBy::Host),
            None => (estimate(messages), CountedBy::Estimate),
        })
    };
    let fits = |prompt: u32, output: u32| prompt as u64 + output as u64 <= limit as u64;
    let mut trim = Trim::default();
    let mut messages = build(&trim);
    let (mut prompt, counted_by) = measure(&messages)?;
    let full = prompt;
    let mut reductions = Vec::new();
    // (a) history: one oldest line at a time down to the floor.
    if !fits(prompt, max_tokens) && room.history_lines > 0 {
        reductions.push(Reduction::History);
        while !fits(prompt, max_tokens) && trim.history_lines < room.history_lines {
            trim.history_lines += 1;
            messages = build(&trim);
            prompt = measure(&messages)?.0;
        }
    }
    // (b) reader fact sets from before the previous step, all at once.
    if !fits(prompt, max_tokens) && room.old_facts {
        reductions.push(Reduction::OldReaderFacts);
        trim.old_facts = true;
        messages = build(&trim);
        prompt = measure(&messages)?.0;
    }
    // (c) chat turns: one oldest turn at a time down to the floor.
    if !fits(prompt, max_tokens) && room.chat_turns > 0 {
        reductions.push(Reduction::ChatHistory);
        while !fits(prompt, max_tokens) && trim.chat_turns < room.chat_turns {
            trim.chat_turns += 1;
            messages = build(&trim);
            prompt = measure(&messages)?.0;
        }
    }
    // (d) a smaller output budget, never below the role's minimum.
    let mut output = max_tokens;
    if !fits(prompt, output) && fits(prompt, min_output(role, max_tokens)) {
        reductions.push(Reduction::MaxTokens);
        output = limit - prompt;
    }
    let mut check = Check {
        role, context_tokens: limit, prompt_estimate: prompt, full_prompt_estimate: full, counted_by,
        requested_max_tokens: max_tokens, max_tokens: output, reductions,
        history_lines_dropped: trim.history_lines, chat_turns_dropped: trim.chat_turns,
        fits: fits(prompt, output), prompt_tokens: None, completion_tokens: None,
    };
    // (e) refuse: the caller reports; nothing is sent to be cut short inside the runtime.
    if !check.fits {
        return Ok((Sent::Refused, Some(check)));
    }
    let completion = host.complete(role, &messages, output)?;
    check.prompt_tokens = Some(completion.usage.prompt_tokens).filter(|n| *n > 0);
    check.completion_tokens = Some(completion.usage.completion_tokens).filter(|n| *n > 0);
    Ok((Sent::Completion(completion), Some(check)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(role: &str, content: &str) -> ChatMessage { ChatMessage { role: role.into(), content: content.into() } }

    #[test]
    fn estimate_counts_digits_singly_and_other_bytes_by_three() {
        // 2 messages: "abc" = 1 + 8; "12345" = 5 + 8; plus 8 for the reply.
        assert_eq!(estimate(&[m("system", "abc"), m("user", "12345")]), 1 + 8 + 5 + 8 + 8);
        // Non-ASCII counts by bytes: "é" is two bytes, rounded up to one token.
        assert_eq!(estimate(&[m("user", "é")]), 1 + 8 + 8);
        assert_eq!(estimate(&[]), REPLY_FRAMING_TOKENS);
    }

    #[test]
    fn minimums_never_raise_the_request() {
        assert_eq!(min_output(ModelRole::Planner, 800), 256);
        assert_eq!(min_output(ModelRole::Judge, 50), 50);
        assert_eq!(min_output(ModelRole::Reader, 64), 64);
        assert_eq!(thousands(4096), "4,096");
        assert_eq!(thousands(1280), "1,280");
        assert_eq!(thousands(999), "999");
        assert_eq!(thousands(1_000_000), "1,000,000");
    }
}
