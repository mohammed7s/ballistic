//! Alloy JSON-RPC through Arti (Tor network)
//!
//! [`TorRpc::connect`] bootstraps one client. Cloning [`TorRpc`] shares its circuits and its HTTP
//! connection pool. [`TorRpc::isolated`] returns a new group: its streams do not share circuits
//! with the parent or with any other isolated client. It is not one circuit per RPC. Call
//! `isolated` again for the next unlinkable group. Reusing one isolated value shares that group
//! (`let iso = tor.isolated(); iso.send(...); iso.send(...)`).
//!
//! - [`TorRpc::rpc_client`], [`TorRpc::provider`], [`TorRpc::send`], [`TorRpc::get`],
//!   [`TorRpc::post`] — whichever client you call them on. On the bootstrapped client, keep traffic
//!   that is safe to link: `eth_blockNumber`, generic/global `eth_getLogs` , e.g a PIR HTTP POST
//!   basically any request that does not contain privacy-sensitive data e.g. specific user accounts
//! - [`TorRpc::isolated`] — one fresh `isolated_client`. Hold it for a session (the calls about one
//!   address). Call `isolated` again when the next session must not share circuits with it.

use std::{
    future::Future,
    pin::Pin,
    task::{Context, Poll},
};

#[cfg(feature = "alloy")]
use alloy::{
    providers::RootProvider,
    rpc::{
        client::RpcClient,
        json_rpc::{RequestPacket, ResponsePacket},
    },
    transports::{
        TransportError,
        http::{Http, HyperClient},
    },
};
use hyper::body::Bytes;
use hyper_util::{
    client::legacy::connect::{Connected, Connection},
    rt::{TokioExecutor, TokioIo},
};
use anyhow::{Context as _, Result, bail};
use arti_client::{DataStream, TorClient, TorClientConfig};
use http_body_util::{BodyExt, Full, Limited};
use hyper_rustls::HttpsConnectorBuilder;
use tor_rtcompat::PreferredRuntime;
use tower::Service;
use url::Url;

/// A bootstrapped Tor client. Cloning is cheap and shares the directory state and the HTTP
/// connection pool.
///
/// [`isolated`](Self::isolated) returns a client whose streams do not share circuits with this
/// one. Reusing that value shares the new group. Call `isolated` again for the next unlinkable
/// group.
///
/// Arti refuses local addresses by default. That error happens when a request is sent, not when
/// one of these clients is built.
#[derive(Clone)]
pub struct TorRpc {
    client: TorClient<PreferredRuntime>,
    http: Pool,
}

impl TorRpc {
    /// Bootstrap a Tor client. Fails if the local network cannot reach the Tor directory.
    ///
    /// # Errors
    /// Returns when Arti cannot bootstrap.
    pub async fn connect() -> Result<Self> {
        Self::connect_with_config(TorClientConfig::default()).await
    }

    /// [`connect`](Self::connect) with a caller-built Arti configuration, e.g. state and cache
    /// directories in app-private storage (`TorClientConfigBuilder::from_directories`).
    ///
    /// # Errors
    /// Returns when Arti cannot bootstrap.
    pub async fn connect_with_config(config: TorClientConfig) -> Result<Self> {
        let client = TorClient::create_bootstrapped(config)
            .await
            .context("bootstrap Tor")?;
        // `ClientConfig::builder` panics unless a process-wide provider is installed.
        // Arti may already have installed ring; a second attempt returns `Err`.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = pool(client.clone());
        Ok(Self { client, http })
    }

    /// A new isolation group. Streams on the returned client do not share circuits with `self`.
    ///
    /// Cloning the returned value shares that group. Call `isolated` again for the next unlinkable
    /// group.
    #[must_use]
    pub fn isolated(&self) -> Self {
        let client = self.client.isolated_client();
        Self {
            http: pool(client.clone()),
            client,
        }
    }

    /// JSON-RPC client that reuses this Tor client and its circuits.
    #[cfg(feature = "alloy")]
    #[must_use]
    pub fn rpc_client(&self, url: Url) -> RpcClient {
        RpcClient::new(transport(self.http.clone(), url), false)
    }

    /// Alloy provider that reuses this Tor client and its circuits.
    #[cfg(feature = "alloy")]
    #[must_use]
    pub fn provider(&self, url: Url) -> RootProvider {
        RootProvider::new(self.rpc_client(url))
    }

    /// `GET url` over this Tor client.
    ///
    /// # Errors
    /// Returns when the download fails or the status is not 2xx.
    pub async fn get(&self, url: &Url) -> Result<Vec<u8>> {
        self.request(url, "GET", &[], &[]).await
    }

    /// Arbitrary `POST` request with `body` over this Tor client.
    ///
    /// Used by e.g. PIR that must reach a PIR HTTP API without clearnet.
    ///
    /// # Errors
    /// Returns when the upload fails or the status is not 2xx.
    pub async fn post(
        &self,
        url: &Url,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>> {
        self.request(url, "POST", headers, body).await
    }

    /// Arbitrary HTTP request over this Tor client. Returns the body when the status is 2xx.
    ///
    /// # Errors
    /// Returns when the exchange fails or the status is not 2xx.
    pub async fn request(
        &self,
        url: &Url,
        method: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>> {
        let resp = self.http(url, method, headers, body).await?;
        if !resp.status().is_success() {
            bail!("{method} {url} returned HTTP {}", resp.status());
        }
        Ok(resp.into_body().to_vec())
    }

    /// HTTP over this Tor client, including status and headers.
    ///
    /// The body is collected into [`Bytes`]. Non-2xx statuses are returned, not treated as
    /// errors.
    ///
    /// # Errors
    /// Returns when Tor or the HTTP exchange fails.
    pub async fn http(
        &self,
        url: &Url,
        method: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<hyper::Response<Bytes>> {
        exchange(&self.http, url, method, headers, body, None).await
    }

    /// HTTP with a response-body limit enforced while streaming, before collecting the body.
    /// Returns [`ResponseTooLarge`] when the body exceeds `max_response_bytes`.
    pub async fn http_limited(
        &self,
        url: &Url,
        method: &str,
        headers: &[(String, String)],
        body: &[u8],
        max_response_bytes: usize,
    ) -> Result<hyper::Response<Bytes>> {
        exchange(&self.http, url, method, headers, body, Some(max_response_bytes)).await
    }

    /// Send a JSON-RPC packet over this Tor client (one HTTP POST).
    ///
    /// Batch every RPC that should share this isolation group into `packet`.
    ///
    /// # Errors
    /// Returns a transport error when serialization, Tor, or HTTP fails.
    #[cfg(feature = "alloy")]
    pub async fn send(
        &self,
        url: Url,
        packet: RequestPacket,
    ) -> Result<ResponsePacket, TransportError> {
        Service::call(&mut transport(self.http.clone(), url), packet).await
    }
}

type Pool =
    hyper_util::client::legacy::Client<hyper_rustls::HttpsConnector<TorConnector>, Full<Bytes>>;

fn pool(client: TorClient<PreferredRuntime>) -> Pool {
    let https = HttpsConnectorBuilder::new()
        .with_webpki_roots()
        .https_or_http()
        .enable_http1()
        .wrap_connector(TorConnector(client));
    hyper_util::client::legacy::Client::builder(TokioExecutor::new()).build(https)
}

#[cfg(feature = "alloy")]
fn transport(pool: Pool, url: Url) -> Http<HyperClient<Full<Bytes>, Pool>> {
    Http::with_client(HyperClient::with_service(pool), url)
}

async fn exchange(
    http: &Pool,
    url: &Url,
    method: &str,
    headers: &[(String, String)],
    body: &[u8],
    max_response_bytes: Option<usize>,
) -> Result<hyper::Response<Bytes>> {
    let mut builder = hyper::Request::builder().method(method).uri(url.as_str());
    for (name, value) in headers {
        builder = builder.header(name, value);
    }
    let request = builder
        .body(Full::new(Bytes::copy_from_slice(body)))
        .context("build http request")?;
    let mut client = http.clone();
    let response = Service::call(&mut client, request)
        .await
        .context("http through Tor")?;
    let (parts, body) = response.into_parts();
    let body = match max_response_bytes {
        Some(limit) => collect_limited(body, limit).await?,
        None => body.collect().await.context("http body")?.to_bytes(),
    };
    Ok(hyper::Response::from_parts(parts, body))
}

/// A response exceeded the caller's byte budget. Contains no response or endpoint data.
#[derive(Debug)]
pub struct ResponseTooLarge;

impl std::fmt::Display for ResponseTooLarge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("HTTP response body too large")
    }
}

impl std::error::Error for ResponseTooLarge {}

async fn collect_limited<B>(body: B, limit: usize) -> Result<Bytes>
where
    B: hyper::body::Body<Data = Bytes>,
    B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    let collected = Limited::new(body, limit).collect().await.map_err(|err| {
        if err.is::<http_body_util::LengthLimitError>() {
            anyhow::Error::new(ResponseTooLarge)
        } else {
            anyhow::Error::from_boxed(err).context("http body")
        }
    })?;
    Ok(collected.to_bytes())
}

#[cfg(test)]
mod body_limit_tests {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};

    struct Chunks {
        chunks: VecDeque<Bytes>,
        polls: Arc<AtomicUsize>,
    }

    impl hyper::body::Body for Chunks {
        type Data = Bytes;
        type Error = std::convert::Infallible;

        fn poll_frame(mut self: Pin<&mut Self>, _: &mut Context<'_>)
            -> Poll<Option<std::result::Result<hyper::body::Frame<Bytes>, Self::Error>>>
        {
            self.polls.fetch_add(1, Ordering::SeqCst);
            Poll::Ready(self.chunks.pop_front().map(|b| Ok(hyper::body::Frame::data(b))))
        }
    }

    #[tokio::test]
    async fn rejects_oversized_stream_without_polling_the_rest() {
        let polls = Arc::new(AtomicUsize::new(0));
        let body = Chunks {
            chunks: [Bytes::from_static(b"123"), Bytes::from_static(b"45"), Bytes::from_static(b"unread")].into(),
            polls: polls.clone(),
        };
        let err = collect_limited(body, 4).await.unwrap_err();
        assert!(err.downcast_ref::<ResponseTooLarge>().is_some());
        assert_eq!(polls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn accepts_exactly_the_limit_across_chunks() {
        let body = Chunks {
            chunks: [Bytes::from_static(b"12"), Bytes::from_static(b"34")].into(),
            polls: Arc::new(AtomicUsize::new(0)),
        };
        assert_eq!(collect_limited(body, 4).await.unwrap(), Bytes::from_static(b"1234"));
    }
}

/// Dial `(host, port)` through Arti. hyper-rustls wraps the stream in TLS for `https`.
#[derive(Clone)]
struct TorConnector(TorClient<PreferredRuntime>);

type TorConnectFut =
    Pin<Box<dyn Future<Output = Result<TokioIo<TorStream>, anyhow::Error>> + Send>>;

impl Service<hyper::Uri> for TorConnector {
    type Response = TokioIo<TorStream>;
    type Error = anyhow::Error;
    type Future = TorConnectFut;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, uri: hyper::Uri) -> Self::Future {
        let client = self.0.clone();
        Box::pin(async move {
            let host = uri.host().context("url host")?.to_owned();
            let port = uri
                .port_u16()
                .unwrap_or(if uri.scheme_str() == Some("https") {
                    443
                } else {
                    80
                });
            let stream = client
                .connect((host.as_str(), port))
                .await
                .with_context(|| format!("Tor connect {host}:{port}"))?;
            Ok(TokioIo::new(TorStream(stream)))
        })
    }
}

/// `DataStream` does not implement hyper's [`Connection`](Connection) metadata trait.
struct TorStream(DataStream);

impl Connection for TorStream {
    fn connected(&self) -> Connected {
        Connected::new()
    }
}

impl tokio::io::AsyncRead for TorStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_read(cx, buf)
    }
}

impl tokio::io::AsyncWrite for TorStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.0).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.0).poll_shutdown(cx)
    }
}

#[cfg(all(test, feature = "alloy"))]
mod tests {
    use super::*;

    #[tokio::test]
    #[ignore = "bootstraps Tor and times shared vs isolated JSON-RPC calls"]
    async fn circuit_session_latency() {
        let tor = TorRpc::connect().await.unwrap();
        let url: Url = "https://ethereum.publicnode.com".parse().unwrap();
        let shared = tor.provider(url.clone());
        let started = std::time::Instant::now();
        let head = alloy::providers::Provider::get_block_number(&shared)
            .await
            .unwrap();
        eprintln!(
            "shared eth_blockNumber -> {head} in {:.1}s",
            started.elapsed().as_secs_f64()
        );
        let from = head.saturating_sub(31);
        let weth: alloy::primitives::Address = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
            .parse()
            .unwrap();
        let filter = alloy::rpc::types::Filter::new()
            .address(weth)
            .from_block(from)
            .to_block(head);
        for i in 0..2 {
            let started = std::time::Instant::now();
            match alloy::providers::Provider::get_logs(&shared, &filter).await {
                Ok(logs) => eprintln!(
                    "shared eth_getLogs {i} blocks {from}..={head} {} logs in {:.1}s",
                    logs.len(),
                    started.elapsed().as_secs_f64()
                ),
                Err(err) => eprintln!(
                    "shared eth_getLogs {i} blocks {from}..={head} failed in {:.1}s: {err}",
                    started.elapsed().as_secs_f64()
                ),
            }
        }
        let started = std::time::Instant::now();
        let n = alloy::providers::Provider::get_block_number(&tor.isolated().provider(url))
            .await
            .unwrap();
        eprintln!(
            "isolated eth_blockNumber -> {n} in {:.1}s",
            started.elapsed().as_secs_f64()
        );
    }
}
