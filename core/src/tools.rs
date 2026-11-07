//! The Tools port's policy and evidence (core/README.md, "Tools port"). A tool is a typed,
//! read-only query the host answers from outside the screen; the only one shipped is
//! `wallet.balance`.
//!
//!   check     planner `tool` action → typed `ToolRequest`, against the code allowlist below
//!   call      owner grant first (no grant: no call), then Tools, then the result validated
//!             field by field into a `BalanceCard` whose values are tagged `Source::Rpc`
//!   answer    the owner sees a sentence and card rendered from those fields; the model's
//!             wording is kept only if every number in it is one of those tagged values
//!
//! Nothing the model writes reaches the card: not the address, not the endpoint, not the amount.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::ports::{PortResult, Tools};
use crate::types::{HostCapabilities, Source, Tagged};

/// One allowlisted tool: its exact argument names and the values code accepts.
pub struct ToolSpec {
    pub id: &'static str,
    pub args: &'static [&'static str],
    pub chain_ids: &'static [u64],
    pub assets: &'static [&'static str],
}

/// The only tools a planner may name. Hosts can grant fewer, never more.
pub const ALLOWLIST: &[ToolSpec] = &[ToolSpec {
    id: "wallet.balance",
    args: &["wallet_id", "chain_id", "asset"],
    chain_ids: &[1],
    assets: &["native"],
}];

/// Display names for allowlisted chains. Code-owned; the endpoint's word is not used.
pub fn network_name(chain_id: u64) -> Option<&'static str> {
    match chain_id {
        1 => Some("Ethereum mainnet"),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Asset {
    Native,
}

/// What core asks the host for. Serializes flat, as in docs/WALLET_BALANCE_SPEC.md:
/// `{"tool":"wallet.balance","wallet_id":"main","chain_id":1,"asset":"native"}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "tool")]
pub enum ToolRequest {
    #[serde(rename = "wallet.balance")]
    WalletBalance { wallet_id: String, chain_id: u64, asset: Asset },
}

impl ToolRequest {
    pub fn tool_id(&self) -> &'static str {
        match self {
            ToolRequest::WalletBalance { .. } => "wallet.balance",
        }
    }
    /// The granted resource this request touches (a wallet id).
    pub fn scope(&self) -> &str {
        match self {
            ToolRequest::WalletBalance { wallet_id, .. } => wallet_id,
        }
    }
}

/// The owner's grant for one tool, advertised by the host in `HostCapabilities::tool_grants`.
/// `scope` lists the resources (wallet ids) it covers; an empty list covers nothing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ToolGrant {
    pub tool: String,
    #[serde(default)]
    pub scope: Vec<GrantScope>,
}

/// One granted resource. `label` is the owner's alias ("Main wallet"): owner data, shown to the
/// planner so it can pick the id, and used in the rendered answer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GrantScope {
    pub id: String,
    #[serde(default)]
    pub label: Option<String>,
}

/// What the host's adapter reports. Validated by [`card`] before anything uses it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WalletBalance {
    pub wallet_id: String,
    pub address: String,
    pub chain_id: u64,
    pub balance_wei: String,
    /// Optional; core recomputes it and rejects a disagreeing value.
    #[serde(default)]
    pub balance_eth: Option<String>,
    pub block_number: u64,
    pub block_hash: String,
    pub observed_at: String,
    pub source: String,
    pub transport: String,
    pub trust: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "result", rename_all = "snake_case")]
pub enum ToolResult {
    Balance(WalletBalance),
    /// The adapter could not answer (`code` like `timeout`, `wrong_chain`, `chain_changed`).
    Failed { code: String, #[serde(default)] message: String },
}

/// The balance card: every field from validated adapter data or code tables, none from a model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BalanceCard {
    pub wallet_id: String,
    pub label: String,
    pub address: String,
    pub chain_id: u64,
    pub network: String,
    pub balance_wei: String,
    pub balance_eth: String,
    pub block_number: u64,
    pub block_hash: String,
    /// The block was the endpoint's latest when read; it is not finalized.
    pub block_tag: String,
    pub observed_at: String,
    pub source: String,
    pub transport: String,
    pub trust: String,
}

/// Results gathered during one run, plus the final answer if there is one.
#[derive(Debug, Default)]
pub struct ToolSession {
    pub cards: Vec<BalanceCard>,
    /// Card values as typed facts with their source (core/README.md §7).
    pub values: Vec<Tagged>,
    pub answer: Option<Value>,
}

impl ToolSession {
    pub fn has_results(&self) -> bool {
        !self.cards.is_empty()
    }
    /// Adds `tool_results` and `answer` to a finished report, only when a tool was used, so
    /// runs without tools report exactly what they did before.
    pub fn attach(&self, mut report: Value) -> Value {
        if self.has_results() {
            report["tool_results"] = json!(self.cards);
            if let Some(a) = &self.answer {
                report["answer"] = a.clone();
            }
        }
        report
    }
}

/// What one tool or answer step did, in the agent loop's terms.
pub struct Step {
    pub outcome: String,
    pub task_status: &'static str,
    pub failure_category: Option<&'static str>,
    pub terminal: bool,
    pub done: bool,
}

fn step(outcome: impl Into<String>, task_status: &'static str, failure: Option<&'static str>, terminal: bool) -> Step {
    Step { outcome: outcome.into(), task_status, failure_category: failure, terminal, done: false }
}

fn valid_wallet_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 32
        && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// Planner `tool` arguments → typed request. Exactly the allowlisted names, nothing extra: the
/// model cannot pass an address, endpoint, method or any other parameter.
pub fn check(tool: &str, args: &Value) -> Result<ToolRequest, String> {
    let spec = ALLOWLIST
        .iter()
        .find(|s| s.id == tool)
        .ok_or_else(|| format!("unknown tool {tool:?}; allowed: {}", ALLOWLIST.iter().map(|s| s.id).collect::<Vec<_>>().join(", ")))?;
    let obj = args.as_object().ok_or("args must be an object")?;
    if let Some(k) = obj.keys().find(|k| !spec.args.contains(&k.as_str())) {
        return Err(format!("unknown argument {k:?}; {tool} takes exactly {}", spec.args.join(", ")));
    }
    if let Some(k) = spec.args.iter().find(|k| !obj.contains_key(**k)) {
        return Err(format!("missing argument {k:?}"));
    }
    let wallet_id = obj["wallet_id"].as_str().filter(|s| valid_wallet_id(s))
        .ok_or("wallet_id must be a configured wallet id such as \"main\"")?;
    let chain_id = obj["chain_id"].as_u64().filter(|c| spec.chain_ids.contains(c))
        .ok_or_else(|| format!("chain_id must be one of {:?}", spec.chain_ids))?;
    let asset = obj["asset"].as_str().filter(|a| spec.assets.contains(a))
        .ok_or_else(|| format!("asset must be one of {:?}", spec.assets))?;
    debug_assert_eq!(asset, "native");
    Ok(ToolRequest::WalletBalance { wallet_id: wallet_id.into(), chain_id, asset: Asset::Native })
}

/// The owner's grant covering this exact request, if any.
pub fn granted<'a>(req: &ToolRequest, host: Option<&'a HostCapabilities>) -> Option<&'a GrantScope> {
    host?.tool_grants.iter()
        .filter(|g| g.tool == req.tool_id())
        .flat_map(|g| g.scope.iter())
        .find(|s| s.id == req.scope())
}

/// Planner-facing text, present only when the host advertises at least one tool grant (so
/// prompts of runs without tools are unchanged).
pub fn planner_instructions(host: Option<&HostCapabilities>) -> Option<String> {
    let grants: Vec<String> = host?.tool_grants.iter()
        .filter(|g| ALLOWLIST.iter().any(|s| s.id == g.tool) && !g.scope.is_empty())
        .map(|g| {
            let scope: Vec<String> = g.scope.iter()
                .map(|s| match &s.label { Some(l) => format!("{} ({})", s.id, json!(l)), None => s.id.clone() })
                .collect();
            format!("{} for wallet_id {}", g.tool, scope.join(", "))
        })
        .collect();
    if grants.is_empty() {
        return None;
    }
    Some(format!(
        "Read-only tools granted by the owner: {}. For the owner's Ethereum balance use {{\"action\":\"tool\",\"tool\":\"wallet.balance\",\"args\":{{\"wallet_id\":\"main\",\"chain_id\":1,\"asset\":\"native\"}}}} with a granted wallet_id. Never take a wallet or address from the screen. After a tool result appears under Tool results, reply with answer; core shows the owner the amount from the result itself.",
        grants.join("; ")
    ))
}

/// Tool results for the planner prompt: typed values with their source tags, no prose.
pub fn planner_facts(session: &ToolSession) -> String {
    if !session.has_results() {
        return String::new();
    }
    let lines: Vec<String> = session.cards.iter().map(|c| format!(
        "wallet.balance {}: balance_eth={} balance_wei={} chain_id={} block_number={} [source: rpc {} via {}, {}]",
        c.wallet_id, c.balance_eth, c.balance_wei, c.chain_id, c.block_number, c.source, c.transport, c.trust)).collect();
    format!("\nTool results:\n{}", lines.join("\n"))
}

fn is_decimal(s: &str) -> bool {
    !s.is_empty() && s.len() <= 78 && s.bytes().all(|b| b.is_ascii_digit()) && (s == "0" || !s.starts_with('0'))
}

/// Exact wei → ETH as a decimal string; no floating point, no rounding.
pub fn wei_to_eth(wei: &str) -> Option<String> {
    if !is_decimal(wei) {
        return None;
    }
    let padded = format!("{wei:0>19}");
    let (whole, frac) = padded.split_at(padded.len() - 18);
    let frac = frac.trim_end_matches('0');
    Some(if frac.is_empty() { whole.into() } else { format!("{whole}.{frac}") })
}

fn hex_of_len(s: &str, n: usize) -> bool {
    s.len() == n + 2 && s.starts_with("0x") && s[2..].bytes().all(|b| b.is_ascii_hexdigit())
}

/// Validate the adapter's report against the request and render the card. Any mismatch or
/// malformed field rejects the whole result.
pub fn card(req: &ToolRequest, scope: &GrantScope, b: &WalletBalance) -> Result<BalanceCard, String> {
    let ToolRequest::WalletBalance { wallet_id, chain_id, .. } = req;
    if &b.wallet_id != wallet_id { return Err("result is for a different wallet".into()); }
    if &b.chain_id != chain_id { return Err("result is for a different chain".into()); }
    let network = network_name(b.chain_id).ok_or("chain not in the allowlist")?;
    if !hex_of_len(&b.address, 40) { return Err("address is not 0x and 40 hex digits".into()); }
    let balance_eth = wei_to_eth(&b.balance_wei).ok_or("balance_wei is not a decimal integer")?;
    if b.balance_eth.as_ref().is_some_and(|e| *e != balance_eth) {
        return Err("balance_eth disagrees with balance_wei".into());
    }
    if b.block_number == 0 { return Err("block number missing".into()); }
    if !hex_of_len(&b.block_hash, 64) { return Err("block hash is not 0x and 64 hex digits".into()); }
    let time_ok = b.observed_at.len() >= 20 && b.observed_at.len() <= 35 && b.observed_at.as_bytes()[10] == b'T'
        && b.observed_at.bytes().all(|c| c.is_ascii_digit() || b"-:.TZ+".contains(&c));
    if !time_ok { return Err("observed_at is not an RFC 3339 time".into()); }
    let source_ok = !b.source.is_empty() && b.source.len() <= 260
        && b.source.bytes().all(|c| c.is_ascii_alphanumeric() || b".-:[]".contains(&c));
    if !source_ok { return Err("source is not a host name".into()); }
    if !matches!(b.transport.as_str(), "tor" | "https") { return Err("transport must be tor or https".into()); }
    if b.trust != "rpc_reported" { return Err("trust must be rpc_reported".into()); }
    Ok(BalanceCard {
        wallet_id: b.wallet_id.clone(),
        label: scope.label.clone().unwrap_or_else(|| b.wallet_id.clone()),
        address: b.address.clone(),
        chain_id: b.chain_id,
        network: network.into(),
        balance_wei: b.balance_wei.clone(),
        balance_eth,
        block_number: b.block_number,
        block_hash: b.block_hash.to_ascii_lowercase(),
        block_tag: "latest".into(),
        observed_at: b.observed_at.clone(),
        source: b.source.clone(),
        transport: b.transport.clone(),
        trust: b.trust.clone(),
    })
}

/// Grant, call, validate. With no grant the host's Tools port is never called. A port error or
/// an adapter failure stops the run: a balance is never assumed, zeroed or retried elsewhere.
pub fn call<T: Tools>(host: &mut T, tool: &str, args: &Value, caps: Option<&HostCapabilities>,
    session: &mut ToolSession) -> Step {
    let req = match check(tool, args) {
        Ok(r) => r,
        Err(e) => return step(format!("invalid tool request: {e}"), "failed", Some("planning"), true),
    };
    let Some(scope) = granted(&req, caps) else {
        return step("not granted", "failed", Some("permission"), true);
    };
    let result: PortResult<ToolResult> = host.call(&req);
    match result {
        Ok(ToolResult::Balance(b)) => match card(&req, scope, &b) {
            Ok(c) => {
                let src = Source::Rpc { source: c.source.clone(), transport: c.transport.clone() };
                for v in [c.balance_eth.clone(), c.balance_wei.clone(), c.chain_id.to_string(),
                    c.block_number.to_string(), c.block_hash.clone(), c.address.clone()] {
                    session.values.push(Tagged { value: v, source: src.clone() });
                }
                session.cards.push(c);
                step("ok: wallet balance retrieved", "pending", None, false)
            }
            Err(e) => step(format!("tool result rejected: {e}"), "failed", Some("execution"), true),
        },
        Ok(ToolResult::Failed { code, .. }) => {
            // The code is reported; the adapter's message stays out of history and prompts.
            let code: String = code.chars().filter(|c| c.is_ascii_lowercase() || *c == '_').take(40).collect();
            step(format!("balance could not be refreshed: {code}"), "failed", Some("execution"), true)
        }
        Err(_) => step("balance could not be refreshed: tool port error", "failed", Some("execution"), true),
    }
}

/// Number-like tokens in model text: `0x…` words whole, otherwise digit runs with `.` and `,`
/// (thousands separators removed).
fn numbers(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for word in text.split(|c: char| c.is_whitespace() || "()[]{}\"'`;:!?<>=/".contains(c)) {
        let w = word.trim_matches(|c: char| c == '.' || c == ',');
        if w.get(..2).is_some_and(|prefix| prefix.eq_ignore_ascii_case("0x")) {
            out.push(w.to_ascii_lowercase());
            continue;
        }
        for run in w.split(|c: char| !(c.is_ascii_digit() || c == '.' || c == ',')) {
            let n = run.trim_matches(|c: char| c == '.' || c == ',').replace(',', "");
            if n.bytes().any(|b| b.is_ascii_digit()) {
                out.push(n);
            }
        }
    }
    out
}

/// The owner-facing answer after a tool result. The displayed sentence and card come from the
/// last validated card. The taint rule (§7, rule 2) applies to the model's `answer` text: every
/// number in it is an untrusted claim unless it equals a value core tagged `Source::Rpc`; one
/// that does not (an altered amount, block or chain) causes the model text to be dropped, and
/// the owner sees only the code-rendered answer with its source. An answer has no side effect,
/// so showing the value with its source stands in for the hard confirmation an action needs.
pub fn answer(text: &str, session: &mut ToolSession) -> Step {
    let c = session.cards.last().expect("answer with tool results").clone();
    let rendered = format!("{} has {} ETH on {}.", c.label, c.balance_eth, c.network);
    let untagged: Vec<String> = numbers(text).into_iter()
        .filter(|n| !session.values.iter().any(|v| v.value.eq_ignore_ascii_case(n)))
        .collect();
    let (model_text, check) = if untagged.is_empty() {
        (Some(text.to_string()), "consistent".to_string())
    } else {
        (None, format!("rejected: {} not in the tool result", untagged.join(", ")))
    };
    let values: Vec<&Tagged> = session.values.iter()
        .filter(|v| v.value == c.balance_eth || v.value == c.balance_wei).collect();
    session.answer = Some(json!({
        "text": rendered,
        "card": c,
        "values": values,
        "model_text": model_text,
        "model_text_check": check,
    }));
    let outcome = if untagged.is_empty() { "answered from tool result".to_string() }
        else { "answered from tool result; model wording dropped: it states a value the tool did not report".to_string() };
    Step { outcome, task_status: "answered", failure_category: None, terminal: true, done: true }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ports::PortError;

    pub(crate) fn fixture() -> WalletBalance {
        WalletBalance {
            wallet_id: "main".into(),
            address: "0x00000000000000000000000000000000000000a1".into(),
            chain_id: 1,
            balance_wei: "1234500000000000000".into(),
            balance_eth: Some("1.2345".into()),
            block_number: 21_000_000,
            block_hash: format!("0x{}", "ab".repeat(32)),
            observed_at: "2026-11-07T12:00:00Z".into(),
            source: "rpc.example".into(),
            transport: "tor".into(),
            trust: "rpc_reported".into(),
        }
    }
    fn caps(grant: bool) -> HostCapabilities {
        HostCapabilities {
            operations: vec![],
            app_grants: vec![],
            tool_grants: if grant { vec![ToolGrant { tool: "wallet.balance".into(),
                scope: vec![GrantScope { id: "main".into(), label: Some("Main wallet".into()) }] }] } else { vec![] },
        }
    }
    struct Fake { calls: Vec<ToolRequest>, reply: PortResult<ToolResult> }
    impl Tools for Fake {
        fn call(&mut self, req: &ToolRequest) -> PortResult<ToolResult> {
            self.calls.push(req.clone());
            match &self.reply { Ok(r) => Ok(r.clone()), Err(e) => Err(PortError::Host(e.to_string())) }
        }
    }
    fn args() -> Value { json!({"wallet_id":"main","chain_id":1,"asset":"native"}) }

    #[test]
    fn request_serializes_as_the_spec_contract() {
        let req = check("wallet.balance", &args()).unwrap();
        assert_eq!(serde_json::to_value(&req).unwrap(),
            json!({"tool":"wallet.balance","wallet_id":"main","chain_id":1,"asset":"native"}));
    }

    #[test]
    fn allowlist_rejects_everything_else() {
        for (tool, a) in [
            ("wallet.send", args()),
            ("eth_call", args()),
            ("wallet.balance", json!({"wallet_id":"main","chain_id":1,"asset":"native","rpc":"https://evil"})),
            ("wallet.balance", json!({"wallet_id":"main","chain_id":1,"asset":"native","address":"0x00"})),
            ("wallet.balance", json!({"wallet_id":"main","chain_id":5,"asset":"native"})),
            ("wallet.balance", json!({"wallet_id":"main","chain_id":"1","asset":"native"})),
            ("wallet.balance", json!({"wallet_id":"main","chain_id":1,"asset":"usdc"})),
            ("wallet.balance", json!({"wallet_id":"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045","chain_id":1,"asset":"native"})),
            ("wallet.balance", json!({"chain_id":1,"asset":"native"})),
            ("wallet.balance", json!("main")),
        ] {
            assert!(check(tool, &a).is_err(), "{tool} {a}");
        }
    }

    #[test]
    fn no_grant_means_no_tool_call() {
        for host in [None, Some(caps(false))] {
            let mut fake = Fake { calls: vec![], reply: Ok(ToolResult::Balance(fixture())) };
            let mut session = ToolSession::default();
            let s = call(&mut fake, "wallet.balance", &args(), host.as_ref(), &mut session);
            assert_eq!(s.outcome, "not granted");
            assert!(s.terminal && fake.calls.is_empty() && !session.has_results());
        }
        // A grant for another wallet does not cover this one.
        let mut fake = Fake { calls: vec![], reply: Ok(ToolResult::Balance(fixture())) };
        let s = call(&mut fake, "wallet.balance", &json!({"wallet_id":"savings","chain_id":1,"asset":"native"}),
            Some(&caps(true)), &mut ToolSession::default());
        assert_eq!(s.outcome, "not granted");
        assert!(fake.calls.is_empty());
    }

    #[test]
    fn granted_request_reaches_tools_unchanged_and_card_has_the_exact_amount() {
        let mut fake = Fake { calls: vec![], reply: Ok(ToolResult::Balance(fixture())) };
        let mut session = ToolSession::default();
        let s = call(&mut fake, "wallet.balance", &args(), Some(&caps(true)), &mut session);
        assert!(!s.terminal, "{}", s.outcome);
        assert_eq!(fake.calls, vec![check("wallet.balance", &args()).unwrap()]);
        let c = &session.cards[0];
        assert_eq!((c.balance_eth.as_str(), c.balance_wei.as_str()), ("1.2345", "1234500000000000000"));
        assert_eq!((c.label.as_str(), c.network.as_str(), c.trust.as_str()), ("Main wallet", "Ethereum mainnet", "rpc_reported"));
        assert!(session.values.iter().all(|v| v.source == Source::Rpc { source: "rpc.example".into(), transport: "tor".into() }));
        let a = answer("Main wallet has 1.2345 ETH (block 21000000).", &mut session);
        assert!(a.done);
        let shown = session.answer.as_ref().unwrap();
        assert_eq!(shown["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.");
        assert_eq!(shown["model_text_check"], "consistent");
        assert_eq!(shown["values"][0]["source"], json!({"kind":"rpc","detail":{"source":"rpc.example","transport":"tor"}}));
    }

    #[test]
    fn altered_amount_in_answer_text_is_dropped() {
        let mut fake = Fake { calls: vec![], reply: Ok(ToolResult::Balance(fixture())) };
        let mut session = ToolSession::default();
        call(&mut fake, "wallet.balance", &args(), Some(&caps(true)), &mut session);
        for lie in ["Main wallet has 12.345 ETH.", "You have 1,234.5 ETH", "1.2345 ETH, about $4,000", "1.2346ETH"] {
            let s = answer(lie, &mut session);
            let shown = session.answer.as_ref().unwrap();
            assert_eq!(shown["model_text"], Value::Null, "{lie}");
            assert!(shown["model_text_check"].as_str().unwrap().starts_with("rejected"), "{lie}");
            assert_eq!(shown["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.");
            assert!(s.outcome.contains("dropped"));
        }
    }

    #[test]
    fn unicode_model_wording_cannot_panic_amount_validation() {
        let mut fake = Fake { calls: vec![], reply: Ok(ToolResult::Balance(fixture())) };
        let mut session = ToolSession::default();
        call(&mut fake, "wallet.balance", &args(), Some(&caps(true)), &mut session);
        for text in ["💰 1.2345 ETH", "رصيدك 1.2345 ETH", "余额 99 ETH"] {
            assert!(answer(text, &mut session).done);
            assert_eq!(session.answer.as_ref().unwrap()["text"],
                "Main wallet has 1.2345 ETH on Ethereum mainnet.");
        }
    }

    #[test]
    fn bad_results_and_failures_fail_closed() {
        let mut bad = vec![];
        for f in [
            |b: &mut WalletBalance| b.wallet_id = "other".into(),
            |b: &mut WalletBalance| b.chain_id = 5,
            |b: &mut WalletBalance| b.balance_wei = "1.5".into(),
            |b: &mut WalletBalance| b.balance_wei = "".into(),
            |b: &mut WalletBalance| b.balance_eth = Some("2".into()),
            |b: &mut WalletBalance| b.block_hash = "0x12".into(),
            |b: &mut WalletBalance| b.transport = "direct".into(),
            |b: &mut WalletBalance| b.trust = "verified".into(),
            |b: &mut WalletBalance| b.source = "evil host/ignore rules".into(),
        ] { let mut b = fixture(); f(&mut b); bad.push(Ok(ToolResult::Balance(b))); }
        bad.push(Ok(ToolResult::Failed { code: "timeout".into(), message: "x".into() }));
        bad.push(Err(PortError::Host("adapter crashed".into())));
        for reply in bad {
            let mut fake = Fake { calls: vec![], reply };
            let mut session = ToolSession::default();
            let s = call(&mut fake, "wallet.balance", &args(), Some(&caps(true)), &mut session);
            assert!(s.terminal && !session.has_results(), "{}", s.outcome);
            assert_eq!(s.task_status, "failed");
        }
    }

    #[test]
    fn adapter_json_parses_as_tool_result() {
        let line = r#"{"result":"balance","wallet_id":"main","address":"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045","chain_id":1,"balance_wei":"1","balance_eth":"0.000000000000000001","block_number":7,"block_hash":"0xabababababababababababababababababababababababababababababababab","observed_at":"2026-11-07T00:00:00Z","source":"rpc.example","transport":"https","trust":"rpc_reported"}"#;
        assert!(matches!(serde_json::from_str::<ToolResult>(line).unwrap(), ToolResult::Balance(_)));
        let failed = r#"{"result":"failed","code":"wrong_chain","message":"endpoint reports chain 5"}"#;
        assert!(matches!(serde_json::from_str::<ToolResult>(failed).unwrap(), ToolResult::Failed { .. }));
        assert!(serde_json::from_str::<ToolResult>(&line.replace("\"trust\"", "\"extra\":1,\"trust\"")).is_err());
    }

    #[test]
    fn wei_formatting_is_exact() {
        assert_eq!(wei_to_eth("0").unwrap(), "0");
        assert_eq!(wei_to_eth("1").unwrap(), "0.000000000000000001");
        assert_eq!(wei_to_eth("1000000000000000000").unwrap(), "1");
        assert_eq!(wei_to_eth("123456789012345678901234567890").unwrap(), "123456789012.34567890123456789");
        assert!(wei_to_eth("007").is_none());
    }
}
