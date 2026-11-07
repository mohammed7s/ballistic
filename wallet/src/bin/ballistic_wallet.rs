//! `ballistic-wallet`: one balance query, one JSON line on stdout. The harness calls this to
//! answer core's `tool` ask (harness/core_bridge.py); the Android host will call the library.
//!
//!   ballistic-wallet balance --wallet-id main --address 0x... --rpc https://... \
//!                            --transport https|tor --chain-id 1 [--deadline-secs 60] [--state-dir DIR]
//!
//! stdout: `{"result":"balance",...}` (exit 0) or `{"result":"failed","code":...}` (exit 1).
//! Usage errors exit 2. Logs go to stderr and never contain the full address.
//! SIGINT/SIGTERM cancel the query.

use std::time::Duration;

use ballistic_wallet::{balance, transport::TransportKind, BalanceQuery, ErrorCode, Request, WalletError, DEFAULT_DEADLINE};

const USAGE: &str = "usage: ballistic-wallet balance --wallet-id ID --address 0x... --rpc URL --transport https|tor --chain-id N [--deadline-secs N] [--state-dir DIR]";

fn parse_args() -> Result<Request, String> {
    let mut it = std::env::args().skip(1);
    if it.next().as_deref() != Some("balance") {
        return Err(USAGE.into());
    }
    let (mut wallet_id, mut address, mut rpc, mut transport, mut chain_id) = (None, None, None, None, None);
    let mut deadline = DEFAULT_DEADLINE;
    let mut state_dir = None;
    while let Some(a) = it.next() {
        let mut value = || it.next().ok_or(format!("{a} needs a value"));
        match a.as_str() {
            "--wallet-id" => wallet_id = Some(value()?),
            "--address" => address = Some(value()?),
            "--rpc" => rpc = Some(value()?.parse::<url::Url>().map_err(|e| format!("--rpc: {e}"))?),
            "--transport" => {
                transport = Some(TransportKind::parse(&value()?).ok_or("--transport must be https or tor")?)
            }
            "--chain-id" => chain_id = Some(value()?.parse::<u64>().map_err(|_| "--chain-id must be a number")?),
            "--deadline-secs" => {
                let s: u64 = value()?.parse().map_err(|_| "--deadline-secs must be a number")?;
                if s == 0 || s > DEFAULT_DEADLINE.as_secs() {
                    return Err(format!("--deadline-secs must be 1..={}", DEFAULT_DEADLINE.as_secs()));
                }
                deadline = Duration::from_secs(s);
            }
            "--state-dir" => state_dir = Some(std::path::PathBuf::from(value()?)),
            other => return Err(format!("unknown argument {other}\n{USAGE}")),
        }
    }
    let need = |name: &str| format!("missing {name}\n{USAGE}");
    Ok(Request {
        query: BalanceQuery {
            wallet_id: wallet_id.ok_or_else(|| need("--wallet-id"))?,
            address: address.ok_or_else(|| need("--address"))?,
            chain_id: chain_id.ok_or_else(|| need("--chain-id"))?,
        },
        rpc_url: rpc.ok_or_else(|| need("--rpc"))?,
        transport: transport.ok_or_else(|| need("--transport"))?,
        deadline,
        state_dir,
    })
}

async fn interrupted() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        match signal(SignalKind::terminate()) {
            Ok(mut term) => tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = term.recv() => {} },
            Err(_) => { let _ = tokio::signal::ctrl_c().await; }
        }
    }
    #[cfg(not(unix))]
    let _ = tokio::signal::ctrl_c().await;
}

fn main() {
    let req = match parse_args() {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => {
            println!("{}", WalletError::new(ErrorCode::Connect, format!("async runtime: {e}")).to_json());
            std::process::exit(1);
        }
    };
    let out = runtime.block_on(balance(&req, interrupted()));
    match out {
        Ok(b) => println!("{}", serde_json::to_string(&b).expect("balance serializes")),
        Err(e) => {
            ballistic_wallet::log(&format!("failed: {e}"));
            println!("{}", e.to_json());
            // Do not wait for Tor or HTTP background tasks to wind down.
            runtime.shutdown_background();
            std::process::exit(1);
        }
    }
    runtime.shutdown_background();
}
