//! Values that carry facts in free text: numbers, phone numbers, amounts and links. Core finds
//! them in code, never with a model, so it can say where each one came from (core/README.md §7,
//! provenance). Used on chat replies and task requests (`chat.rs`), planner answers and typed
//! text (`agent.rs`, the taint rule).
//!
//!   extract     every value in a text, in order, with a comparison key
//!   Provenance  where a value may legitimately come from: the owner's words, control labels,
//!               tagged values (reader facts, task results); anything else is unsourced
//!
//! Two values are the same when their keys are equal: phone numbers by their digits, plain
//! numbers and amounts by their decimal value, everything else by its text without spaces.

use serde::{Deserialize, Serialize};

use crate::types::{Source, Tagged};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ValueKind {
    Number,
    Phone,
    Amount,
    Url,
}

/// One value as it appears in a text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    pub kind: ValueKind,
    /// Exactly as written, including a currency symbol or code next to an amount.
    pub raw: String,
    key: String,
}

impl Found {
    pub fn key(&self) -> &str {
        &self.key
    }
}

const CURRENCY_SYMBOLS: &[char] = &['$', '€', '£', '¥', '₹'];
const CURRENCY_CODES: &[&str] = &["usd", "eur", "gbp", "jpy", "inr", "chf", "cad", "aud"];

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// `40`, `40.00` and `0040` are the same amount; `1,234.5` is `1234.5`.
fn decimal_key(digits: &str) -> String {
    let plain: String = digits.chars().filter(|c| *c != ',').collect();
    let (int, frac) = match plain.split_once('.') {
        Some((i, f)) => (i, f.trim_end_matches('0')),
        None => (plain.as_str(), ""),
    };
    let int = int.trim_start_matches('0');
    let int = if int.is_empty() { "0" } else { int };
    if frac.is_empty() { format!("n:{int}") } else { format!("n:{int}.{frac}") }
}

/// A plain decimal: digits with optional thousands commas and at most one point.
fn is_plain_decimal(s: &str) -> bool {
    let mut points = 0;
    for c in s.chars() {
        match c {
            '0'..='9' | ',' => {}
            '.' => points += 1,
            _ => return false,
        }
    }
    points <= 1
}

/// `2023-10-27`, `27/10/2023`: digits in three groups shaped like a date, not a phone number.
fn is_date(s: &str) -> bool {
    let groups: Vec<usize> = s.split(['-', '/', '.']).map(str::len).collect();
    groups.len() == 3 && s.chars().all(|c| c.is_ascii_digit() || matches!(c, '-' | '/' | '.'))
        && ((groups[0] == 4 && groups[1] <= 2 && groups[2] <= 2) || (groups[0] <= 2 && groups[1] <= 2 && (groups[2] == 2 || groups[2] == 4)))
}

fn url_end(chars: &[(usize, char)], start: usize) -> usize {
    let mut j = start;
    while j < chars.len() && !chars[j].1.is_whitespace() && !matches!(chars[j].1, '<' | '>' | '"' | '\'' | '`') {
        j += 1;
    }
    while j > start && matches!(chars[j - 1].1, '.' | ',' | ';' | ':' | '!' | '?' | ')' | ']' | '}') {
        j -= 1;
    }
    j
}

fn starts_with_ci(chars: &[(usize, char)], at: usize, word: &str) -> bool {
    let w: Vec<char> = word.chars().collect();
    at + w.len() <= chars.len() && (0..w.len()).all(|k| chars[at + k].1.to_ascii_lowercase() == w[k])
}

/// Every value in `text`, in order. A run of digits glued to letters (`e7`, `abc123`) is an
/// identifier, not a value.
pub fn extract(text: &str) -> Vec<Found> {
    let chars: Vec<(usize, char)> = text.char_indices().collect();
    let slice = |a: usize, b: usize| -> &str {
        let start = chars.get(a).map_or(text.len(), |c| c.0);
        let end = chars.get(b).map_or(text.len(), |c| c.0);
        &text[start..end]
    };
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i].1;
        let after_word = i > 0 && is_word_char(chars[i - 1].1);
        if !after_word && (starts_with_ci(&chars, i, "https://") || starts_with_ci(&chars, i, "http://")) {
            let end = url_end(&chars, i);
            let raw = slice(i, end).to_string();
            let key = format!("u:{}", raw.to_lowercase().trim_end_matches('/'));
            out.push(Found { kind: ValueKind::Url, raw, key });
            i = end.max(i + 1);
            continue;
        }
        let digit_at = |k: usize| chars.get(k).is_some_and(|c| c.1.is_ascii_digit());
        let starts_number = !after_word && (c.is_ascii_digit() || ((c == '+' || c == '(') && digit_at(i + 1)));
        if !starts_number {
            i += 1;
            continue;
        }
        // Extend over digits and separators that sit between digits. A single space continues
        // a phone-like number (`+1 555 0100`, `555 0100`) but not two small numbers (`8 9`).
        let leading_plus = c == '+';
        let mut open_paren = c == '(';
        let mut j = i + 1;
        let mut digits_so_far = usize::from(c.is_ascii_digit());
        let mut group_digits = digits_so_far;
        loop {
            match chars.get(j).map(|c| c.1) {
                Some(d) if d.is_ascii_digit() => {
                    digits_so_far += 1;
                    group_digits += 1;
                    j += 1;
                }
                Some('.' | ',' | ':' | '/' | '-') if digit_at(j + 1) && group_digits > 0 => {
                    group_digits = 0;
                    j += 1;
                }
                Some(')') if open_paren && group_digits > 0 => {
                    open_paren = false;
                    j += 1;
                    if chars.get(j).is_some_and(|c| c.1 == ' ') && digit_at(j + 1) {
                        j += 1;
                    }
                    group_digits = 0;
                }
                Some(' ') if digit_at(j + 1) => {
                    let next_group = (j + 1..chars.len()).take_while(|k| chars[*k].1.is_ascii_digit()).count();
                    let next_glued = chars.get(j + 1 + next_group).is_some_and(|c| is_word_char(c.1));
                    if !next_glued && (leading_plus || (digits_so_far >= 3 && group_digits >= 3 && next_group >= 3)) {
                        group_digits = 0;
                        j += 1;
                    } else {
                        break;
                    }
                }
                _ => break,
            }
        }
        // `12abc` is an identifier: drop the whole run.
        if chars.get(j).is_some_and(|c| c.1.is_alphabetic()) {
            let word: String = chars[j..].iter().map(|c| c.1).take_while(|c| c.is_alphabetic()).collect();
            let lower = word.to_lowercase();
            let unit = ["am", "pm", "st", "nd", "rd", "th", "h", "m", "min", "s", "k", "kg", "km", "g", "mb", "gb", "x"];
            if !unit.contains(&lower.as_str()) && !CURRENCY_CODES.contains(&lower.as_str()) {
                i = j + word.chars().count();
                continue;
            }
        }
        let start = if c == '(' && open_paren { i + 1 } else { i };
        let body = slice(start, j).to_string();
        let digits: String = body.chars().filter(char::is_ascii_digit).collect();
        // Currency next to the number makes it an amount: `$40`, `€ 12,50`, `40 USD`, `USD 40`.
        let before = (0..start).rev().map(|k| chars[k].1).find(|c| *c != ' ');
        let symbol_before = before.is_some_and(|b| CURRENCY_SYMBOLS.contains(&b))
            && (start == 0 || {
                let k = (0..start).rev().find(|k| chars[*k].1 != ' ').unwrap();
                start - k <= 2
            });
        let word_after: String = chars[j..].iter().map(|c| c.1).skip_while(|c| *c == ' ').take_while(|c| c.is_alphabetic()).collect();
        let word_before: String = {
            let mut k = start;
            while k > 0 && chars[k - 1].1 == ' ' { k -= 1; }
            let mut w: Vec<char> = Vec::new();
            while k > 0 && chars[k - 1].1.is_alphabetic() { k -= 1; w.push(chars[k].1); }
            w.into_iter().rev().collect()
        };
        let code_after = CURRENCY_CODES.contains(&word_after.to_lowercase().as_str());
        let code_before = CURRENCY_CODES.contains(&word_before.to_lowercase().as_str());
        let phone_chars = body.chars().all(|c| c.is_ascii_digit() || matches!(c, '+' | ' ' | '-' | '.' | '(' | ')'));
        let (kind, raw, key) = if symbol_before || code_after || code_before {
            let normal: String = body.chars().filter(|c| *c != ' ').collect();
            let key = if is_plain_decimal(&normal) { decimal_key(&normal) } else { format!("t:{normal}") };
            let raw_start = if symbol_before { (0..start).rev().find(|k| CURRENCY_SYMBOLS.contains(&chars[*k].1)).unwrap() } else { start };
            let mut raw = slice(raw_start, j).to_string();
            if code_after {
                raw = format!("{raw} {word_after}");
            }
            (ValueKind::Amount, raw, key)
        } else if phone_chars && digits.len() >= 7 && !(is_plain_decimal(&body) && body.contains(['.', ','])) && !is_date(&body) {
            let raw = slice(i, j).to_string();
            (ValueKind::Phone, raw, format!("p:{}{digits}", if leading_plus { "+" } else { "" }))
        } else {
            let normal: String = body.chars().filter(|c| *c != ' ').collect();
            let key = if is_plain_decimal(&normal) { decimal_key(&normal) } else { format!("t:{normal}") };
            (ValueKind::Number, body.clone(), key)
        };
        out.push(Found { kind, raw, key });
        i = j.max(i + 1);
    }
    out
}

/// The text is exactly one value of an accepted kind, nothing around it.
pub fn single(text: &str, kinds: &[ValueKind]) -> Option<Found> {
    let mut found = extract(text);
    if found.len() != 1 || !kinds.contains(&found[0].kind) || found[0].raw != text.trim() {
        return None;
    }
    found.pop()
}

/// Where values may come from in one context. Order of precedence: the owner's own words, then
/// control labels (what a control IS, written by the OS or app as structure), then tagged values
/// (reader facts, task results), each with its source. Anything else is model text.
pub struct Provenance<'a> {
    owner: Vec<String>,
    labels: Vec<String>,
    tagged: Vec<(String, &'a Source)>,
}

impl<'a> Provenance<'a> {
    pub fn new(owner_texts: &[&str], label_texts: &[&str], tagged: &'a [Tagged]) -> Self {
        let keys = |texts: &[&str]| -> Vec<String> { texts.iter().flat_map(|t| extract(t)).map(|f| f.key).collect() };
        let tagged = tagged.iter()
            .flat_map(|t| extract(&t.value).into_iter().map(move |f| (f.key, &t.source)))
            .collect();
        Provenance { owner: keys(owner_texts), labels: keys(label_texts), tagged }
    }

    /// The source of one value; `unsourced` names the model whose text it is.
    pub fn source_of(&self, value: &Found, unsourced: &Source) -> Source {
        if self.owner.iter().any(|k| *k == value.key) {
            return Source::OwnerRequest;
        }
        if self.labels.iter().any(|k| *k == value.key) {
            return Source::ScreenLabel;
        }
        if let Some((_, s)) = self.tagged.iter().find(|(k, _)| *k == value.key) {
            return (*s).clone();
        }
        unsourced.clone()
    }

    /// Every value in `text` with its source, in order, duplicates removed.
    pub fn tag(&self, text: &str, unsourced: &Source) -> Vec<(Found, Source)> {
        self.tag_with(text, &[], unsourced)
    }

    /// [`tag`](Self::tag), where `carried` values win over everything else. A request the chat
    /// model resolved is treated as the owner's words, but the values in it that the owner never
    /// said are carried with their sources and must not become owner values by appearing in it.
    pub fn tag_with(&self, text: &str, carried: &[Tagged], unsourced: &Source) -> Vec<(Found, Source)> {
        let carried: Vec<(String, &Source)> = carried.iter()
            .flat_map(|t| extract(&t.value).into_iter().map(move |f| (f.key, &t.source))).collect();
        let mut out: Vec<(Found, Source)> = Vec::new();
        for f in extract(text) {
            if out.iter().any(|(g, _)| g.key == f.key) {
                continue;
            }
            let s = match carried.iter().find(|(k, _)| *k == f.key) {
                Some((_, s)) => (*s).clone(),
                None => self.source_of(&f, unsourced),
            };
            out.push((f, s));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raws(text: &str) -> Vec<(ValueKind, String)> {
        extract(text).into_iter().map(|f| (f.kind, f.raw)).collect()
    }

    #[test]
    fn finds_each_kind_once() {
        use ValueKind::*;
        assert_eq!(raws("Call +1 555 0100 or (555) 123-4567 today"), vec![(Phone, "+1 555 0100".into()), (Phone, "(555) 123-4567".into())]);
        assert_eq!(raws("It costs $40.00, or 12,50 EUR"), vec![(Amount, "$40.00".into()), (Amount, "12,50 EUR".into())]);
        assert_eq!(raws("see https://example.com/a?b=1."), vec![(Url, "https://example.com/a?b=1".into())]);
        assert_eq!(raws("set it to 8 at 9:30 on 2023-10-27"), vec![(Number, "8".into()), (Number, "9:30".into()), (Number, "2023-10-27".into())]);
        assert_eq!(raws("tap e7 then c4, code abc123"), vec![]);
        assert_eq!(raws("8 9"), vec![(Number, "8".into()), (Number, "9".into())]);
        assert_eq!(raws("3pm"), vec![(Number, "3".into())]);
    }

    #[test]
    fn keys_compare_values_not_formatting() {
        let key = |t: &str| extract(t)[0].key.clone();
        assert_eq!(key("+1 555 0100"), key("+15550100"));
        assert_ne!(key("+1 555 0100"), key("555 0100"));
        assert_eq!(key("$40.00"), key("40"));
        assert_eq!(key("1,234.50"), key("1234.5"));
        assert_ne!(key("8"), key("80"));
    }

    #[test]
    fn provenance_prefers_the_owner_then_labels_then_tags() {
        let tagged = vec![Tagged { value: "+1 555 0100".into(), source: Source::App("example.sms".into()) }];
        let p = Provenance::new(&["set a timer for 8 minutes"], &["Battery 85%"], &tagged);
        let model = Source::Model("chat".into());
        let tags: Vec<Source> = p.tag("8 minutes, 85%, +15550100 and 42", &model).into_iter().map(|t| t.1).collect();
        assert_eq!(tags, vec![Source::OwnerRequest, Source::ScreenLabel, Source::App("example.sms".into()), model]);
    }

    #[test]
    fn single_requires_the_whole_text() {
        assert!(single("+1 555 0100", &[ValueKind::Phone]).is_some());
        assert!(single("call +1 555 0100", &[ValueKind::Phone]).is_none());
        assert!(single("$40", &[ValueKind::Amount]).is_some());
        assert!(single("40", &[ValueKind::Amount]).is_none());
    }
}
