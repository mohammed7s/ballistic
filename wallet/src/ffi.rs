//! C ABI of `libballistic_wallet.so`, for the Android app's JNI (or UniFFI) glue. One call:
//!
//!   int32_t ballistic_wallet_balance(const char *request_json, const int32_t *cancel,
//!                                    char *out, size_t out_len);
//!
//! `request_json` (UTF-8, NUL-terminated):
//!   {"wallet_id":"main","address":"0x...","chain_id":1,"rpc":"https://...","transport":"tor",
//!    "deadline_secs":60,"state_dir":"/data/user/0/<app>/files/arti"}
//! (`deadline_secs` optional, 1..=60; `state_dir` required for Tor on Android.)
//!
//! `out` receives one NUL-terminated JSON line, the same the CLI prints: the balance (return 0)
//! or `{"result":"failed","code":...}` (return 1). Negative returns write nothing usable:
//! -1 a null pointer or non-UTF-8 request, -2 `out_len` too small,
//! -3 a panic inside the query.
//!
//! The call blocks the calling thread (a fresh Tokio runtime per call; call it off the UI thread).
//! `cancel` may be null; otherwise it is polled, and a nonzero value drops the query's future, so
//! the call returns `cancelled` promptly. The deadline covers Tor bootstrap and all requests.

use std::ffi::{c_char, CStr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::Duration;

use serde::Deserialize;

use crate::transport::TransportKind;
use crate::{balance, BalanceQuery, ErrorCode, Request, WalletError, DEFAULT_DEADLINE};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FfiRequest {
    wallet_id: String,
    address: String,
    chain_id: u64,
    rpc: String,
    transport: String,
    deadline_secs: Option<u64>,
    state_dir: Option<String>,
}

fn parse(json: &str) -> Result<Request, WalletError> {
    let bad = |m: String| WalletError::new(ErrorCode::InvalidInput, m);
    // Serde errors can echo arbitrary field names and values from the host's input.
    let r: FfiRequest = serde_json::from_str(json).map_err(|_| bad("invalid request JSON".into()))?;
    let transport = TransportKind::parse(&r.transport).ok_or_else(|| bad("transport must be https or tor".into()))?;
    let deadline = match r.deadline_secs {
        None => DEFAULT_DEADLINE,
        Some(s) if (1..=DEFAULT_DEADLINE.as_secs()).contains(&s) => Duration::from_secs(s),
        Some(_) => return Err(bad(format!("deadline_secs must be 1..={}", DEFAULT_DEADLINE.as_secs()))),
    };
    Ok(Request {
        query: BalanceQuery { wallet_id: r.wallet_id, address: r.address, chain_id: r.chain_id },
        rpc_url: r.rpc.parse().map_err(|_| bad("invalid RPC URL".into()))?,
        transport,
        deadline,
        state_dir: r.state_dir.map(PathBuf::from),
    })
}

/// Resolves once `*flag` is nonzero; never if `flag` is null.
async fn cancelled(flag: Option<&AtomicI32>) {
    match flag {
        None => std::future::pending().await,
        Some(f) => {
            while f.load(Ordering::Acquire) == 0 {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
}

/// One query, start to finish: (return code, JSON line).
pub fn balance_json(request_json: &str, cancel: Option<&AtomicI32>) -> (i32, String) {
    let failed = |e: WalletError| (1, e.to_json().to_string());
    if cancel.is_some_and(|flag| flag.load(Ordering::Acquire) != 0) {
        return failed(WalletError::new(ErrorCode::Cancelled, "cancelled"));
    }
    let req = match parse(request_json) {
        Ok(r) => r,
        Err(e) => return failed(e),
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => return failed(WalletError::new(ErrorCode::Connect, format!("async runtime: {e}"))),
    };
    let out = runtime.block_on(balance(&req, cancelled(cancel)));
    // Do not wait for Tor or HTTP background tasks to wind down.
    runtime.shutdown_background();
    match out {
        Ok(b) => (0, serde_json::to_string(&b).expect("balance serializes")),
        Err(e) => {
            crate::log(&format!("failed: {e}"));
            failed(e)
        }
    }
}

/// See the module documentation.
///
/// # Safety
/// `request_json` must be a NUL-terminated string; `cancel`, if not null, must point to an
/// `int32_t` aligned for `AtomicI32` that stays valid (and is only written atomically) until the
/// call returns; `out` must
/// be writable for `out_len` bytes.
#[no_mangle]
pub unsafe extern "C" fn ballistic_wallet_balance(
    request_json: *const c_char,
    cancel: *const i32,
    out: *mut c_char,
    out_len: usize,
) -> i32 {
    if request_json.is_null() || out.is_null() {
        return -1;
    }
    let Ok(request) = CStr::from_ptr(request_json).to_str() else {
        return -1;
    };
    // SAFETY: the caller keeps `cancel` valid and aligned for the duration of the call.
    let cancel = (!cancel.is_null()).then(|| AtomicI32::from_ptr(cancel as *mut i32));
    let Ok((code, line)) = std::panic::catch_unwind(|| balance_json(request, cancel.map(|c| &*c))) else {
        return -3;
    };
    if line.len() + 1 > out_len {
        return -2;
    }
    std::ptr::copy_nonoverlapping(line.as_ptr(), out.cast::<u8>(), line.len());
    *out.add(line.len()) = 0;
    code
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bad_requests_without_a_query() {
        let (code, line) = balance_json("{}", None);
        assert_eq!(code, 1);
        assert!(line.contains("\"invalid_input\""), "{line}");
        let (code, line) = balance_json(
            r#"{"wallet_id":"main","address":"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045","chain_id":1,
                "rpc":"https://example.invalid/","transport":"tor","state_dir":"relative/arti"}"#,
            None,
        );
        assert_eq!(code, 1);
        assert!(line.contains("absolute"), "{line}");
    }

    #[test]
    fn c_abi_reports_small_buffers_and_nulls() {
        let req = std::ffi::CString::new(r#"{"transport":"carrier-pigeon"}"#).unwrap();
        let mut small = [0 as c_char; 8];
        let mut big = [0 as c_char; 1024];
        unsafe {
            assert_eq!(ballistic_wallet_balance(std::ptr::null(), std::ptr::null(), big.as_mut_ptr(), big.len()), -1);
            assert_eq!(ballistic_wallet_balance(req.as_ptr(), std::ptr::null(), small.as_mut_ptr(), small.len()), -2);
            assert_eq!(ballistic_wallet_balance(req.as_ptr(), std::ptr::null(), big.as_mut_ptr(), big.len()), 1);
            let line = CStr::from_ptr(big.as_ptr()).to_str().unwrap();
            let v: serde_json::Value = serde_json::from_str(line).unwrap();
            assert_eq!((v["result"].as_str(), v["code"].as_str()), (Some("failed"), Some("invalid_input")), "{line}");
        }
    }

    #[test]
    fn malformed_host_input_is_not_echoed_and_fits_small_output() {
        let private = "private-host-value".repeat(200);
        let inputs = [
            serde_json::json!({private.clone(): 1}).to_string(),
            serde_json::json!({"wallet_id": private, "chain_id": "private-host-value"}).to_string(),
        ];
        for input in inputs {
            let req = std::ffi::CString::new(input).unwrap();
            let mut out = [0 as c_char; 256];
            unsafe {
                assert_eq!(ballistic_wallet_balance(req.as_ptr(), std::ptr::null(), out.as_mut_ptr(), out.len()), 1);
                let line = CStr::from_ptr(out.as_ptr()).to_str().unwrap();
                assert_eq!(serde_json::from_str::<serde_json::Value>(line).unwrap(),
                    serde_json::json!({"result":"failed","code":"invalid_input","message":"invalid request JSON"}));
            }
        }
    }

    #[test]
    fn already_cancelled_ffi_call_returns_before_parsing_or_runtime_creation() {
        let flag = AtomicI32::new(1);
        let (code, line) = balance_json("not JSON", Some(&flag));
        assert_eq!(code, 1);
        assert_eq!(serde_json::from_str::<serde_json::Value>(&line).unwrap()["code"], "cancelled");
    }
}
