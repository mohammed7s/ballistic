//! How JSON-RPC bytes reach the endpoint. Two transports, each behind a feature:
//!
//!   https   direct HTTPS through reqwest + rustls (tests, desktop development)
//!   tor     Kohaku's tor-rpc: HTTPS carried over an Arti circuit, one isolation group per query
//!
//! The caller names the transport; nothing here falls back from one to the other. Requesting a
//! transport that was not compiled in is an error, not a reason to use the other one.

use std::future::Future;

use serde::{Deserialize, Serialize};
use url::Url;

use crate::{ErrorCode, WalletError};

/// Largest JSON-RPC response body accepted (the three methods used return a few hundred bytes).
pub const MAX_RESPONSE_BYTES: usize = 1 << 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransportKind {
    Tor,
    Https,
}

impl TransportKind {
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "tor" => Some(Self::Tor),
            "https" => Some(Self::Https),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Tor => "tor",
            Self::Https => "https",
        }
    }
}

/// One JSON-RPC POST: request body in, response body out. A non-2xx status is an error.
pub trait Transport {
    fn kind(&self) -> TransportKind;
    fn post(&self, body: Vec<u8>) -> impl Future<Output = Result<Vec<u8>, WalletError>> + Send;
}

fn is_loopback(url: &Url) -> bool {
    match url.host() {
        Some(url::Host::Domain(d)) => d == "localhost",
        Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
        Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
        None => false,
    }
}

/// The endpoint must be HTTPS with a host. Plain `http` is accepted only for a loopback host on
/// the direct transport (the mock server in tests); over Tor the payload always needs TLS.
pub fn check_endpoint(url: &Url, kind: TransportKind) -> Result<(), WalletError> {
    let bad = |m: &str| Err(WalletError::new(ErrorCode::InvalidInput, m));
    if url.host_str().is_none_or(str::is_empty) {
        return bad("RPC URL has no host");
    }
    if !url.username().is_empty() || url.password().is_some() {
        return bad("RPC URL must not carry credentials");
    }
    match (url.scheme(), kind) {
        ("https", _) => Ok(()),
        ("http", TransportKind::Https) if is_loopback(url) => Ok(()),
        _ => bad("RPC URL must be https (plain http only for a loopback test endpoint without Tor)"),
    }
}

/// Source identifier shown on the balance card: the endpoint's host (and port if not default).
pub fn source_id(url: &Url) -> String {
    let host = url.host_str().unwrap_or_default();
    match url.port() {
        Some(p) => format!("{host}:{p}"),
        None => host.to_string(),
    }
}

#[cfg(feature = "https")]
pub mod https {
    use super::*;

    /// Direct HTTPS. Proxies from the environment are ignored and redirects are not followed, so
    /// the request goes to the configured host and nowhere else.
    pub struct Https {
        client: reqwest::Client,
        url: Url,
    }

    impl Https {
        pub fn new(url: Url) -> Result<Self, WalletError> {
            check_endpoint(&url, TransportKind::Https)?;
            let client = reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .map_err(|e| WalletError::new(ErrorCode::Connect, format!("HTTPS client: {e}")))?;
            Ok(Self { client, url })
        }
    }

    impl Transport for Https {
        fn kind(&self) -> TransportKind {
            TransportKind::Https
        }
        async fn post(&self, body: Vec<u8>) -> Result<Vec<u8>, WalletError> {
            let net = |e: reqwest::Error| {
                WalletError::new(ErrorCode::Network, format!("HTTPS request failed: {}", e.without_url()))
            };
            let mut resp = self
                .client
                .post(self.url.clone())
                .header("content-type", "application/json")
                .body(body)
                .send()
                .await
                .map_err(net)?;
            if !resp.status().is_success() {
                return Err(WalletError::new(ErrorCode::HttpStatus, format!("RPC returned HTTP {}", resp.status().as_u16())));
            }
            let mut out = Vec::new();
            while let Some(chunk) = resp.chunk().await.map_err(net)? {
                if chunk.len() > MAX_RESPONSE_BYTES - out.len() {
                    return Err(WalletError::new(ErrorCode::MalformedResponse, "RPC response too large"));
                }
                out.extend_from_slice(&chunk);
            }
            Ok(out)
        }
    }
}

#[cfg(feature = "tor")]
pub mod tor {
    use std::path::Path;

    use super::*;
    use arti_client::config::TorClientConfigBuilder;
    use arti_client::TorClientConfig;
    use kohaku_tor_rpc::TorRpc;

    /// HTTPS through Arti. `connect` bootstraps a Tor client; each `Tor` value is its own Kohaku
    /// isolation group, so its circuits are not shared with any other query context.
    pub struct Tor {
        rpc: TorRpc,
        url: Url,
    }

    /// Arti's configuration: state in `<state_dir>/state`, directory cache in `<state_dir>/cache`
    /// (on Android, the app's private files directory), else Arti's per-user defaults.
    pub fn arti_config(state_dir: Option<&Path>) -> Result<TorClientConfig, WalletError> {
        let builder = match state_dir {
            Some(dir) => TorClientConfigBuilder::from_directories(dir.join("state"), dir.join("cache")),
            None => TorClientConfigBuilder::default(),
        };
        builder
            .build()
            .map_err(|e| WalletError::new(ErrorCode::InvalidInput, format!("Tor configuration: {e}")))
    }

    impl Tor {
        pub async fn connect(url: Url, state_dir: Option<&Path>) -> Result<Self, WalletError> {
            check_endpoint(&url, TransportKind::Tor)?;
            let root = TorRpc::connect_with_config(arti_config(state_dir)?)
                .await
                .map_err(|e| WalletError::new(ErrorCode::Connect, format!("Tor bootstrap failed: {e:#}")))?;
            Ok(Self { rpc: root.isolated(), url })
        }
    }

    impl Transport for Tor {
        fn kind(&self) -> TransportKind {
            TransportKind::Tor
        }
        async fn post(&self, body: Vec<u8>) -> Result<Vec<u8>, WalletError> {
            let headers = [("content-type".to_string(), "application/json".to_string())];
            let resp = self
                .rpc
                .http_limited(&self.url, "POST", &headers, &body, MAX_RESPONSE_BYTES)
                .await
                .map_err(|e| {
                    if e.downcast_ref::<kohaku_tor_rpc::ResponseTooLarge>().is_some() {
                        WalletError::new(ErrorCode::MalformedResponse, "RPC response too large")
                    } else {
                        WalletError::new(ErrorCode::Network, format!("request through Tor failed: {e:#}"))
                    }
                })?;
            if !resp.status().is_success() {
                return Err(WalletError::new(ErrorCode::HttpStatus, format!("RPC returned HTTP {}", resp.status().as_u16())));
            }
            let body = resp.into_body();
            if body.len() > MAX_RESPONSE_BYTES {
                return Err(WalletError::new(ErrorCode::MalformedResponse, "RPC response too large"));
            }
            Ok(body.to_vec())
        }
    }
}
