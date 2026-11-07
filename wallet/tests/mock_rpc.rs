//! The balance query against a scripted JSON-RPC server on loopback (direct transport, plain
//! http, which `check_endpoint` allows only for loopback). Fixtures: happy path, malformed hex,
//! wrong chain, a block hash that changes once then holds, one that never holds, a server that
//! never answers, and the Tor/no-fallback rules.
#![cfg(feature = "https")]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use ballistic_wallet::{balance, transport::TransportKind, BalanceQuery, ErrorCode, Request};
use serde_json::{json, Value};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const ADDRESS: &str = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";
const HASH_A: &str = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const HASH_B: &str = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
/// 1.2345 ETH, the spec's illustrative fixture amount.
const WEI_HEX: &str = "0x1121d33597384000";

type Script = Box<dyn Fn(&str, &Value, usize) -> Option<Value> + Send + Sync>;

/// Every request seen: (method, params).
type Seen = Arc<Mutex<Vec<(String, Value)>>>;

/// Serve `script` on 127.0.0.1. `None` from the script means: accept, read, never answer.
async fn serve(script: Script) -> (String, Seen) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/", listener.local_addr().unwrap());
    let seen: Seen = Arc::default();
    let script = Arc::new(script);
    let log = seen.clone();
    tokio::spawn(async move {
        loop {
            let (mut sock, _) = listener.accept().await.unwrap();
            let (script, log) = (script.clone(), log.clone());
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                let body = loop {
                    let n = sock.read(&mut chunk).await.unwrap();
                    if n == 0 { return; }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    if let Some(end) = text.find("\r\n\r\n") {
                        let len: usize = text[..end].lines()
                            .find_map(|l| l.to_ascii_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse().unwrap()))
                            .unwrap_or(0);
                        if buf.len() >= end + 4 + len { break buf[end + 4..end + 4 + len].to_vec(); }
                    }
                };
                let req: Value = serde_json::from_slice(&body).unwrap();
                let method = req["method"].as_str().unwrap().to_string();
                let index = {
                    let mut l = log.lock().unwrap();
                    l.push((method.clone(), req["params"].clone()));
                    l.iter().filter(|(m, _)| *m == method).count() - 1
                };
                let Some(result) = script(&method, &req["params"], index) else {
                    tokio::time::sleep(Duration::from_secs(3600)).await;
                    return;
                };
                let reply = json!({"jsonrpc": "2.0", "id": req["id"], "result": result}).to_string();
                let resp = format!("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{reply}", reply.len());
                let _ = sock.write_all(resp.as_bytes()).await;
            });
        }
    });
    (url, seen)
}

fn block(number: &str, hash: &str) -> Value {
    json!({"number": number, "hash": hash, "parentHash": HASH_B, "transactions": []})
}

/// A well-behaved chain-1 endpoint; `hash_for(index)` picks the hash of the index-th block read.
fn chain(balance: &'static str, hash_for: fn(usize) -> &'static str) -> Script {
    Box::new(move |method, _params, i| Some(match method {
        "eth_chainId" => json!("0x1"),
        "eth_getBlockByNumber" => block("0x1406f40", hash_for(i)),
        "eth_getBalance" => json!(balance),
        _ => json!(null),
    }))
}

fn request(url: &str, deadline: Duration) -> Request {
    Request {
        query: BalanceQuery { wallet_id: "main".into(), address: ADDRESS.into(), chain_id: 1 },
        rpc_url: url.parse().unwrap(),
        transport: TransportKind::Https,
        deadline,
        state_dir: None,
    }
}

async fn run(script: Script) -> (Result<ballistic_wallet::Balance, ballistic_wallet::WalletError>, Vec<(String, Value)>) {
    let (url, seen) = serve(script).await;
    let out = balance(&request(&url, Duration::from_secs(10)), std::future::pending()).await;
    let seen = seen.lock().unwrap().clone();
    (out, seen)
}

#[tokio::test]
async fn happy_path_reads_balance_at_the_explicit_block() {
    let (out, seen) = run(chain(WEI_HEX, |_| HASH_A)).await;
    let b = out.unwrap();
    assert_eq!(b.balance_wei, "1234500000000000000");
    assert_eq!(b.balance_eth, "1.2345");
    assert_eq!(b.block_number, 21_000_000);
    assert_eq!(b.block_hash, HASH_A);
    assert_eq!((b.wallet_id.as_str(), b.address.as_str(), b.chain_id), ("main", ADDRESS, 1));
    assert_eq!((b.transport, b.trust.as_str(), b.result.as_str()), (TransportKind::Https, "rpc_reported", "balance"));
    assert!(b.source.starts_with("127.0.0.1:"));
    assert!(b.observed_at.ends_with('Z') && b.observed_at.len() == 20);
    let methods: Vec<&str> = seen.iter().map(|(m, _)| m.as_str()).collect();
    assert_eq!(methods, ["eth_chainId", "eth_getBlockByNumber", "eth_getBalance", "eth_getBlockByNumber"]);
    assert_eq!(seen[1].1, json!(["latest", false]));
    // Balance at the block number just read, never "latest"; the re-read names the same number.
    assert_eq!(seen[2].1, json!([ADDRESS.to_ascii_lowercase(), "0x1406f40"]));
    assert_eq!(seen[3].1, json!(["0x1406f40", false]));
    let wire = serde_json::to_value(&b).unwrap();
    assert_eq!(wire["transport"], "https");
}

#[tokio::test]
async fn malformed_hex_fails_closed_never_zero() {
    for bad in ["0xZZ", "0x", "0x0001", "1234", "-0x1"] {
        let script: Script = Box::new(move |m, _, _| Some(match m {
            "eth_chainId" => json!("0x1"),
            "eth_getBlockByNumber" => block("0x10", HASH_A),
            _ => json!(bad),
        }));
        let (out, _) = run(script).await;
        assert_eq!(out.unwrap_err().code, ErrorCode::MalformedResponse, "{bad}");
    }
    // A number where a string belongs, and a block with no hash.
    let (out, _) = run(Box::new(|m, _, _| Some(match m { "eth_chainId" => json!(1), _ => json!(null) }))).await;
    assert_eq!(out.unwrap_err().code, ErrorCode::MalformedResponse);
    let (out, _) = run(Box::new(|m, _, _| Some(match m {
        "eth_chainId" => json!("0x1"), "eth_getBlockByNumber" => json!({"number": "0x10"}), _ => json!("0x0") }))).await;
    assert_eq!(out.unwrap_err().code, ErrorCode::MalformedResponse);
}

#[tokio::test]
async fn wrong_chain_id_stops_before_any_address_is_sent() {
    let (out, seen) = run(Box::new(|m, _, _| Some(match m { "eth_chainId" => json!("0xaa36a7"), _ => json!(WEI_HEX) }))).await;
    let err = out.unwrap_err();
    assert_eq!(err.code, ErrorCode::WrongChain);
    assert!(err.message.contains("11155111"));
    assert_eq!(seen.len(), 1);
}

#[tokio::test]
async fn hash_change_then_stable_retries_and_succeeds() {
    // Reads 0 (A) and 1 (B) disagree; reads 2 and 3 agree on B.
    let (out, seen) = run(chain(WEI_HEX, |i| if i == 0 { HASH_A } else { HASH_B })).await;
    let b = out.unwrap();
    assert_eq!(b.block_hash, HASH_B);
    assert_eq!(seen.iter().filter(|(m, _)| m == "eth_getBalance").count(), 2);
}

#[tokio::test]
async fn hash_that_never_holds_is_chain_changed() {
    let (out, seen) = run(chain(WEI_HEX, |i| if i % 2 == 0 { HASH_A } else { HASH_B })).await;
    assert_eq!(out.unwrap_err().code, ErrorCode::ChainChanged);
    assert_eq!(seen.iter().filter(|(m, _)| m == "eth_getBalance").count(), 3);
}

#[tokio::test]
async fn silent_server_hits_the_deadline() {
    let (url, _) = serve(Box::new(|_, _, _| None)).await;
    let started = std::time::Instant::now();
    let err = balance(&request(&url, Duration::from_millis(500)), std::future::pending()).await.unwrap_err();
    assert_eq!(err.code, ErrorCode::Timeout);
    assert!(started.elapsed() < Duration::from_secs(5));
}

#[tokio::test]
async fn cancellation_stops_the_query() {
    let (url, _) = serve(Box::new(|_, _, _| None)).await;
    let cancel = tokio::time::sleep(Duration::from_millis(200));
    let err = balance(&request(&url, Duration::from_secs(30)), cancel).await.unwrap_err();
    assert_eq!(err.code, ErrorCode::Cancelled);
}

#[tokio::test]
async fn already_cancelled_query_does_not_open_a_connection() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/", listener.local_addr().unwrap());
    let err = balance(&request(&url, Duration::from_secs(5)), std::future::ready(())).await.unwrap_err();
    assert_eq!(err.code, ErrorCode::Cancelled);
    assert!(tokio::time::timeout(Duration::from_millis(100), listener.accept()).await.is_err());
}

#[tokio::test]
async fn oversized_stream_is_rejected_before_the_server_finishes() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/", listener.local_addr().unwrap());
    let server = tokio::spawn(async move {
        let (mut sock, _) = listener.accept().await.unwrap();
        let mut req = [0; 4096];
        sock.read(&mut req).await.unwrap();
        sock.write_all(b"HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n").await.unwrap();
        let chunk = vec![b' '; 16384];
        for _ in 0..65 {
            if sock.write_all(b"4000\r\n").await.is_err()
                || sock.write_all(&chunk).await.is_err()
                || sock.write_all(b"\r\n").await.is_err() { return; }
        }
        // No terminating chunk: an implementation collecting the full body times out.
        std::future::pending::<()>().await;
    });
    let err = balance(&request(&url, Duration::from_secs(3)), std::future::pending()).await.unwrap_err();
    server.abort();
    assert_eq!(err.code, ErrorCode::MalformedResponse);
    assert_eq!(err.message, "RPC response too large");
}

#[tokio::test]
async fn rpc_error_text_is_not_echoed() {
    let (url, _) = {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/", listener.local_addr().unwrap());
        tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 4096];
            let _ = sock.read(&mut buf).await;
            let reply = json!({"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":format!("bad {ADDRESS}")}}).to_string();
            let resp = format!("HTTP/1.1 200 OK\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{reply}", reply.len());
            let _ = sock.write_all(resp.as_bytes()).await;
        });
        (url, ())
    };
    let err = balance(&request(&url, Duration::from_secs(5)), std::future::pending()).await.unwrap_err();
    assert_eq!(err.code, ErrorCode::RpcError);
    assert!(!err.message.contains(&ADDRESS[2..]), "{}", err.message);
}

#[tokio::test]
async fn endpoint_and_input_rules() {
    let mut r = request("http://example.com/", Duration::from_secs(1));
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::InvalidInput);
    r.rpc_url = "https://user:pw@example.com/".parse().unwrap();
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::InvalidInput);
    r = request("https://example.com/", Duration::from_secs(1));
    r.query.address = "0x1234".into();
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::InvalidInput);
    r = request("https://example.com/", Duration::from_secs(1));
    r.query.wallet_id = "Main Wallet".into();
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::InvalidInput);
    // Over Tor the payload needs TLS: plain http is refused even for loopback.
    let (url, seen) = serve(chain(WEI_HEX, |_| HASH_A)).await;
    let mut r = request(&url, Duration::from_secs(1));
    r.transport = TransportKind::Tor;
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::InvalidInput);
    assert!(seen.lock().unwrap().is_empty());
}

/// Tor requested from a build without Tor: an error, and no direct request reaches the endpoint.
#[cfg(not(feature = "tor"))]
#[tokio::test]
async fn tor_without_the_feature_never_falls_back_to_direct() {
    let r = Request { transport: TransportKind::Tor, ..request("https://127.0.0.1:9/", Duration::from_secs(2)) };
    assert_eq!(balance(&r, std::future::pending()).await.unwrap_err().code, ErrorCode::UnsupportedTransport);
}

/// The C ABI's path: a full query, then a cancel flag set while the server never answers. Both
/// run `ffi::balance_json` on its own runtime, as the app's JNI thread would.
#[tokio::test(flavor = "multi_thread")]
async fn ffi_query_and_cancel_flag() {
    use std::sync::atomic::{AtomicI32, Ordering};
    let (url, _) = serve(chain(WEI_HEX, |_| HASH_A)).await;
    let req = json!({"wallet_id": "main", "address": ADDRESS, "chain_id": 1, "rpc": url, "transport": "https"}).to_string();
    let (code, line) = tokio::task::spawn_blocking(move || ballistic_wallet::ffi::balance_json(&req, None)).await.unwrap();
    assert_eq!(code, 0, "{line}");
    assert_eq!(serde_json::from_str::<Value>(&line).unwrap()["balance_eth"], "1.2345");

    let (url, _) = serve(Box::new(|_, _, _| None)).await;
    let req = json!({"wallet_id": "main", "address": ADDRESS, "chain_id": 1, "rpc": url, "transport": "https"}).to_string();
    let flag = Arc::new(AtomicI32::new(0));
    let f = flag.clone();
    let started = std::time::Instant::now();
    let query = tokio::task::spawn_blocking(move || ballistic_wallet::ffi::balance_json(&req, Some(&f)));
    tokio::time::sleep(Duration::from_millis(300)).await;
    flag.store(1, Ordering::Release);
    let (code, line) = query.await.unwrap();
    assert_eq!(code, 1);
    assert!(line.contains("\"cancelled\""), "{line}");
    assert!(started.elapsed() < Duration::from_secs(5));
}
