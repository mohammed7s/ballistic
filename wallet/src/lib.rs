//! Ballistic's read-only wallet adapter (wallet/README.md). One operation: the native ETH balance
//! of a configured public address at an explicit recent block, with the evidence core needs to
//! render a balance card (core/README.md, "Tools port").
//!
//!   1. eth_chainId                         must equal the requested chain
//!   2. eth_getBlockByNumber("latest")      number N and hash H
//!   3. eth_getBalance(address, N)          at that explicit block number, never "latest"
//!   4. eth_getBlockByNumber(N)             hash must still be H; otherwise retry from 2
//!
//! Every request goes through the one transport the caller chose. Any network, HTTP, JSON or
//! number error fails the whole query: no zero, no partial answer, no other transport. There is no
//! cache. Logs never contain the full address, and this crate never sees a prompt.

pub mod ffi;
pub mod transport;
pub mod units;

use std::future::Future;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use url::Url;

use transport::{Transport, TransportKind};

/// Cold connection + query budget (docs/WALLET_BALANCE_SPEC.md).
pub const DEFAULT_DEADLINE: Duration = Duration::from_secs(60);

/// Header/balance/header passes before a moving chain head is reported as `chain_changed`.
pub const MAX_ATTEMPTS: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    InvalidInput,
    UnsupportedTransport,
    Connect,
    Network,
    HttpStatus,
    MalformedResponse,
    RpcError,
    WrongChain,
    ChainChanged,
    Timeout,
    Cancelled,
}

/// A failed query. `message` is this crate's own text; it never echoes the address or an RPC
/// server's free-form error text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WalletError {
    pub code: ErrorCode,
    pub message: String,
}

impl WalletError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self { code, message: message.into() }
    }
    /// The JSON line the CLI prints; core reads it as `ToolResult::Failed`.
    pub fn to_json(&self) -> Value {
        json!({"result": "failed", "code": self.code, "message": self.message})
    }
}

impl std::fmt::Display for WalletError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}: {}", self.code, self.message)
    }
}

/// What to read. `address` comes from the owner's wallet configuration, never from a model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BalanceQuery {
    pub wallet_id: String,
    pub address: String,
    pub chain_id: u64,
}

/// The adapter's answer. Field names are the bridge contract (`ToolResult::Balance` in core).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Balance {
    pub result: String,
    pub wallet_id: String,
    pub address: String,
    pub chain_id: u64,
    /// Exact base units, decimal string.
    pub balance_wei: String,
    /// Exact decimal ETH, from `balance_wei` in integer arithmetic.
    pub balance_eth: String,
    pub block_number: u64,
    pub block_hash: String,
    /// UTC, RFC 3339, when the consistent header re-read completed.
    pub observed_at: String,
    /// The RPC endpoint's host.
    pub source: String,
    pub transport: TransportKind,
    /// The provider reported it; nothing here verifies it cryptographically.
    pub trust: String,
}

/// `0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045` → `0xd8dA...6045`, for logs.
pub fn mask(address: &str) -> String {
    if address.len() <= 10 || !address.is_ascii() {
        return "0x...".into();
    }
    format!("{}...{}", &address[..6], &address[address.len() - 4..])
}

/// One stderr line. Callers pass masked addresses only.
pub fn log(line: &str) {
    eprintln!("ballistic-wallet: {line}");
}

pub fn valid_address(a: &str) -> bool {
    a.len() == 42 && a.starts_with("0x") && a[2..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn valid_hash(h: &str) -> bool {
    h.len() == 66 && h.starts_with("0x") && h[2..].bytes().all(|b| b.is_ascii_hexdigit())
}

/// Wallet ids are short owner-chosen keys (`main`, `savings`), not addresses or labels.
pub fn valid_wallet_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 32
        && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// Seconds since the epoch → `YYYY-MM-DDTHH:MM:SSZ` (civil-from-days, proleptic Gregorian).
pub fn rfc3339(t: SystemTime) -> String {
    let secs = t.duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) as i64;
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// JSON-RPC 2.0 over a [`Transport`]. Checks the envelope; the caller checks the result.
pub struct Rpc<T: Transport> {
    transport: T,
    next_id: u64,
}

impl<T: Transport> Rpc<T> {
    pub fn new(transport: T) -> Self {
        Self { transport, next_id: 1 }
    }

    pub async fn call(&mut self, method: &str, params: Value) -> Result<Value, WalletError> {
        let id = self.next_id;
        self.next_id += 1;
        let body = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        let bytes = self.transport.post(body.to_string().into_bytes()).await?;
        let malformed = |m: &str| WalletError::new(ErrorCode::MalformedResponse, format!("{method}: {m}"));
        let reply: Value = serde_json::from_slice(&bytes).map_err(|_| malformed("response is not JSON"))?;
        let reply = reply.as_object().ok_or_else(|| malformed("response is not a JSON-RPC object"))?;
        if reply.get("id") != Some(&json!(id)) {
            return Err(malformed("response id does not match the request"));
        }
        if let Some(err) = reply.get("error") {
            // The server's message may echo request parameters; report its numeric code only.
            let code = err.get("code").and_then(Value::as_i64).unwrap_or(0);
            return Err(WalletError::new(ErrorCode::RpcError, format!("{method}: RPC error code {code}")));
        }
        reply.get("result").cloned().ok_or_else(|| malformed("response has no result"))
    }
}

struct Header {
    number: u64,
    hash: String,
}

fn header(method_tag: &str, v: &Value) -> Result<Header, WalletError> {
    let malformed = |m: &str| WalletError::new(ErrorCode::MalformedResponse, format!("block {method_tag}: {m}"));
    let obj = v.as_object().ok_or_else(|| malformed("no block object"))?;
    let number = obj
        .get("number")
        .and_then(Value::as_str)
        .and_then(units::quantity_u64)
        .ok_or_else(|| malformed("bad number"))?;
    let hash = obj
        .get("hash")
        .and_then(Value::as_str)
        .filter(|h| valid_hash(h))
        .ok_or_else(|| malformed("bad hash"))?;
    Ok(Header { number, hash: hash.to_ascii_lowercase() })
}

/// Steps 1–4 above over an already connected transport. No deadline here; see [`balance`].
pub async fn read_balance<T: Transport>(
    rpc: &mut Rpc<T>,
    query: &BalanceQuery,
    source: &str,
) -> Result<Balance, WalletError> {
    let chain = rpc.call("eth_chainId", json!([])).await?;
    let chain = chain
        .as_str()
        .and_then(units::quantity_u64)
        .ok_or_else(|| WalletError::new(ErrorCode::MalformedResponse, "eth_chainId: bad quantity"))?;
    if chain != query.chain_id {
        return Err(WalletError::new(
            ErrorCode::WrongChain,
            format!("endpoint reports chain {chain}, expected {}", query.chain_id),
        ));
    }
    let address = query.address.to_ascii_lowercase();
    for attempt in 1..=MAX_ATTEMPTS {
        let head = header("latest", &rpc.call("eth_getBlockByNumber", json!(["latest", false])).await?)?;
        let at = units::to_quantity(head.number);
        let wei = rpc.call("eth_getBalance", json!([address, at])).await?;
        let wei = wei
            .as_str()
            .and_then(units::quantity_decimal)
            .ok_or_else(|| WalletError::new(ErrorCode::MalformedResponse, "eth_getBalance: bad quantity"))?;
        let again = header(&at, &rpc.call("eth_getBlockByNumber", json!([at, false])).await?)?;
        if again.number != head.number {
            return Err(WalletError::new(ErrorCode::MalformedResponse, "block re-read returned a different number"));
        }
        if again.hash != head.hash {
            log(&format!("block {} changed hash between reads (attempt {attempt}); retrying", head.number));
            continue;
        }
        let balance_eth = units::wei_to_eth(&wei).expect("quantity_decimal yields canonical decimals");
        return Ok(Balance {
            result: "balance".into(),
            wallet_id: query.wallet_id.clone(),
            address: query.address.clone(),
            chain_id: query.chain_id,
            balance_wei: wei,
            balance_eth,
            block_number: head.number,
            block_hash: head.hash,
            observed_at: rfc3339(SystemTime::now()),
            source: source.to_string(),
            transport: rpc.transport.kind(),
            trust: "rpc_reported".into(),
        });
    }
    Err(WalletError::new(
        ErrorCode::ChainChanged,
        format!("block hash changed during the query {MAX_ATTEMPTS} times"),
    ))
}

/// A full query: endpoint check, connection (Tor bootstrap included), steps 1–4, under one
/// deadline. `cancel` resolving stops the query; dropping the returned future also cancels it.
pub struct Request {
    pub query: BalanceQuery,
    pub rpc_url: Url,
    pub transport: TransportKind,
    pub deadline: Duration,
    /// Tor only: Arti's state and directory cache go under this absolute directory (`state/`,
    /// `cache/`). Required on Android (the app's private files directory); elsewhere `None`
    /// means Arti's per-user defaults. Tor network data only, never balances.
    pub state_dir: Option<PathBuf>,
}

pub fn check_request(req: &Request) -> Result<(), WalletError> {
    let bad = |m: &str| Err(WalletError::new(ErrorCode::InvalidInput, m));
    if !valid_wallet_id(&req.query.wallet_id) {
        return bad("wallet id must be 1-32 of a-z 0-9 _ -");
    }
    if !valid_address(&req.query.address) {
        return bad("address must be 0x and 40 hex digits");
    }
    if req.query.chain_id == 0 {
        return bad("chain id must be positive");
    }
    match &req.state_dir {
        Some(dir) if !dir.is_absolute() => return bad("state dir must be an absolute path"),
        None if cfg!(target_os = "android") && req.transport == TransportKind::Tor => {
            return bad("Tor on Android needs a state dir (the app's private files directory)")
        }
        _ => {}
    }
    transport::check_endpoint(&req.rpc_url, req.transport)
}

pub async fn balance(req: &Request, cancel: impl Future<Output = ()>) -> Result<Balance, WalletError> {
    check_request(req)?;
    let work = async {
        let source = transport::source_id(&req.rpc_url);
        log(&format!(
            "balance of {} on chain {} via {} ({})",
            mask(&req.query.address),
            req.query.chain_id,
            source,
            req.transport.as_str()
        ));
        match req.transport {
            TransportKind::Https => {
                #[cfg(feature = "https")]
                {
                    let mut rpc = Rpc::new(transport::https::Https::new(req.rpc_url.clone())?);
                    return read_balance(&mut rpc, &req.query, &source).await;
                }
                #[cfg(not(feature = "https"))]
                return Err(WalletError::new(ErrorCode::UnsupportedTransport, "built without the https feature"));
            }
            TransportKind::Tor => {
                #[cfg(feature = "tor")]
                {
                    let started = std::time::Instant::now();
                    let tor = transport::tor::Tor::connect(req.rpc_url.clone(), req.state_dir.as_deref()).await?;
                    log(&format!("Tor bootstrapped in {} ms", started.elapsed().as_millis()));
                    let started = std::time::Instant::now();
                    let mut rpc = Rpc::new(tor);
                    let out = read_balance(&mut rpc, &req.query, &source).await;
                    log(&format!("queries over Tor took {} ms", started.elapsed().as_millis()));
                    return out;
                }
                #[cfg(not(feature = "tor"))]
                return Err(WalletError::new(
                    ErrorCode::UnsupportedTransport,
                    "built without the tor feature; refusing to use a direct connection instead",
                ));
            }
        }
    };
    tokio::select! {
        biased;
        _ = cancel => Err(WalletError::new(ErrorCode::Cancelled, "cancelled")),
        out = tokio::time::timeout(req.deadline, work) => out.unwrap_or_else(|_| Err(WalletError::new(
            ErrorCode::Timeout, format!("no consistent answer within {} s", req.deadline.as_secs())))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn masks_addresses() {
        assert_eq!(mask("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"), "0xd8dA...6045");
        assert_eq!(mask("0x12"), "0x...");
    }

    #[test]
    fn formats_utc_times() {
        assert_eq!(rfc3339(UNIX_EPOCH), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339(UNIX_EPOCH + Duration::from_secs(1_794_009_600)), "2026-11-07T00:00:00Z");
        assert_eq!(rfc3339(UNIX_EPOCH + Duration::from_secs(951_782_400 + 3_661)), "2000-02-29T01:01:01Z");
    }
}
