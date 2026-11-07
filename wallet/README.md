# Ballistic wallet adapter

Read-only. Answers one question for core's Tools port (`core/README.md`, "Tools port"): the
native ETH balance of a configured public address on Ethereum mainnet, at an explicit recent
block, with the evidence the balance card shows. No seed phrase, no key, no signing, no
transaction, no cache. Design: `docs/WALLET_BALANCE_SPEC.md`; upstream review:
`docs/KOHAKU_WALLET_RESEARCH.md`.

## What it does

```text
core: tool action checked (allowlist, grant)   →  host: wallet id → address + endpoint
  → ballistic-wallet, one transport for every request:
      1. eth_chainId                      must equal the requested chain, else wrong_chain
      2. eth_getBlockByNumber("latest")   number N, hash H
      3. eth_getBalance(address, N)       at that explicit number, never "latest"
      4. eth_getBlockByNumber(N)          hash still H? else retry 2–4 (3 passes), then chain_changed
  → {"result":"balance", wallet_id, address, chain_id, balance_wei, balance_eth, block_number,
     block_hash, observed_at, source, transport, trust:"rpc_reported"}
  → core: validates every field, recomputes balance_eth, renders the answer and card
```

- **Exact amounts.** Hex quantities are parsed strictly (`0x`, no leading zeros, at most 256
  bits) and converted to decimal strings digit by digit; wei → ETH is string arithmetic. No
  floating point anywhere.
- **Fail closed.** Any network, HTTP, JSON-RPC, JSON or number error fails the query with a code
  (`network`, `http_status`, `rpc_error`, `malformed_response`, `wrong_chain`, `chain_changed`,
  `timeout`, `cancelled`, `connect`, `invalid_input`, `unsupported_transport`). Never zero, never
  a partial answer. Tor requested from a build without the `tor` feature is
  `unsupported_transport`, not a direct request.
- **Deadline and cancellation.** 60 s for the whole query, Tor bootstrap included
  (`--deadline-secs` may shorten it). SIGINT/SIGTERM cancel the CLI; dropping the library's future
  or resolving its `cancel` future cancels the query.
- **Endpoints.** HTTPS only; plain `http` only for a loopback host on the direct transport (test
  stubs). No credentials in the URL, environment proxies ignored, redirects not followed. The
  endpoint comes from the host's configuration; the model never names one.
- **Logs.** stderr only, address as first 6 + last 4 characters (`0xd8dA...6045`). An RPC
  server's error message is not repeated (it may echo the address); only its numeric code is.
  This crate never receives a prompt.
- **Tor isolation.** Each query uses a fresh Kohaku isolation group (`TorRpc::isolated`), so its
  circuits are not shared with another wallet context.

## Exact privacy claim

Copied from `docs/WALLET_BALANCE_SPEC.md`:

| Boundary | Proposed protection | Remaining visibility |
| --- | --- | --- |
| Conversation → model | Local inference, no cloud fallback for this flow | Ballistic on the device processes the question and answer |
| Phone → RPC service | Kohaku's Tor transport hides the phone's source IP from the endpoint | RPC sees the requested address, method, timing and its own response |
| Tor relays → RPC payload | Require HTTPS with certificate validation | Tor is not a guarantee against traffic correlation |
| Different wallet contexts | Use a separate Kohaku isolation group for each context | Same address or identifying API key can still link requests |

The balance remains public on Ethereum. This design does not hide the queried
address from the RPC provider, shield funds, use private information retrieval,
or verify the provider's answer cryptographically. The useful improvement is
avoiding routine IP-to-wallet association at the RPC endpoint while keeping
wallet conversations out of a cloud model.

This crate covers the transport rows. The conversation row depends on the host's model route; the
harness test below uses no model at all.

## Wallet configuration (host-owned)

The host keeps the owner's wallets in `wallets.json` in its storage directory (the "+ a storage
directory" of `core/README.md` §3). Core never reads it; it sees only wallet ids and aliases in
`tool_grants`. Android: app-private files directory, written by the settings screen (not built).
Harness: any path given to `WalletTools.from_file` (`harness/core_bridge.py`); `run_task.py` and
`chat.py` do not take a flag for it yet.

```json
{"wallets": [{"id": "main", "alias": "Main wallet", "address": "0x…", "chain_id": 1,
              "grants": ["wallet.balance"]}],
 "rpc": {"url": "https://…", "transport": "tor"}}
```

`grants` is the owner's balance-read grant for that wallet; without `"wallet.balance"` in it the
wallet is not advertised and core refuses before any request. Use an endpoint without a personal
API key (a key links requests).

## Build, test, run

```sh
export PATH=~/.cargo/bin:$PATH
cd wallet
cargo test                                   # https feature: units, formatting, mock JSON-RPC server
cargo build                                  # target/debug/ballistic-wallet (direct HTTPS only)

# Tor: vendored Kohaku transport with rustls; Alloy/native-tls/OpenSSL are disabled.
CARGO_TARGET_DIR=target/tor cargo build --release --locked --features tor
CARGO_TARGET_DIR=target/tor cargo test --release --locked --features tor \
  -p ballistic_wallet -p kohaku-tor-rpc
# Selecting both packages also runs the vendored streaming-limit tests using wallet/Cargo.lock.

ballistic-wallet balance --wallet-id main --address 0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045 \
  --rpc https://ethereum-rpc.publicnode.com --transport https --chain-id 1
#   stdout: one JSON line, {"result":"balance",...} exit 0 | {"result":"failed","code":...} exit 1
#   usage error: exit 2

# Through core and the harness bridge, no emulator, no model (scripted planner):
cd .. && python3 -m unittest harness/test_wallet_tool.py -v
BALLISTIC_WALLET_LIVE=1 python3 -m unittest harness/test_wallet_tool.py -v        # + live, direct HTTPS
BALLISTIC_WALLET_LIVE=1 BALLISTIC_WALLET_TRANSPORT=tor \
  BALLISTIC_WALLET_BIN=$PWD/wallet/target/tor/release/ballistic-wallet \
  python3 -m unittest harness.test_wallet_tool.WalletToolTest.test_live_mainnet -v  # + live, Tor
```

Mock-server fixtures (`tests/mock_rpc.rs`): happy path (and the exact method/parameter
sequence), malformed hex (`0xZZ`, `0x`, `0x0001`, unprefixed, negative, wrong JSON type, block
without hash), wrong chain (no address sent), block hash changing once then holding, a hash
that never holds (`chain_changed`), a server that never answers (`timeout`), cancellation, an
RPC error echoing the address (not repeated), endpoint and input rules, Tor requested without the
feature. Formatting (`src/units.rs`): 0 wei, 1 wei, 1 ETH, 1.2345 ETH, a 30-digit value, the
256-bit maximum.

## Earlier desktop verification (7 November 2026)

Linux x86_64, Rust 1.99.1, address `0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045`.

- Direct HTTPS, `ethereum-rpc.publicnode.com`: 5.793818533768043911 ETH
  (5793818533768043911 wei) at block 26359851, about 1.3 s wall. The same amount was read back
  independently with curl at that block. `eth.llamarpc.com` answered HTTP 525 at that time; the
  adapter reported `http_status` and printed no amount.
- Tor (Arti 0.32 via kohaku-tor-rpc `21e3cd9`), `ethereum-rpc.publicnode.com`: same amount at
  block 26359872. Cold bootstrap (no Arti state on disk) 8.4 s, then 5.7 s for the four requests;
  14.1 s wall. Warm (directory cache present): bootstrap 0.14 s, requests 9.0 s. A 1 s deadline
  gave `timeout`; an unresolvable host gave `network` (Tor END cell, RESOLVEFAILED); a build without
  `tor` asked for Tor gave `unsupported_transport`.
- Through core: `harness/test_wallet_tool.py` live case passed over HTTPS (block 26359881) and
  over Tor (block 26359883), the report carrying the card with `source` kind `rpc`.

That earlier run did not verify that the endpoint saw a Tor exit address (needs a controlled endpoint's logs,
spec demo item 2); behaviour with Tor blocked on the network; any Android build; a local model
choosing the tool; cancellation from a UI.

Arti keeps its own state and directory cache (here `~/.local/share/arti`, 16 KB, and
`~/.cache/arti`, 11 MB, Arti's defaults). That is Tor network data, not wallet data; balances are
not cached anywhere.

## Earlier desktop footprint

These measurements used the original upstream dependency graph before vendoring. They do not
describe the current Android build, which disables Alloy and native-tls/OpenSSL.

| | direct HTTPS only | with `tor` |
| --- | --- | --- |
| crates in the normal dependency graph | 92 | 492 (34 `arti`/`tor-*`, 37 `alloy*`) |
| release binary, stripped | 3.8 MB | 15.8 MB |
| release build from clean, this machine | 27 s | 4 min 34 s (`-j4`) |

With `tor` the graph also contains `openssl`/`native-tls` (from Alloy's hyper feature; Kohaku's
Tor connector itself uses rustls) and bundled SQLite (`rusqlite`, Arti's directory store). The
desktop binary does not link libssl at run time (ldd shows libc, libm, libgcc_s only), but the
build needs OpenSSL headers.

## Android library and CLI

```sh
CARGO_BUILD_JOBS=2 wallet/scripts/build_android.sh --api 33 --abi arm64-v8a --abi x86_64
# wallet/target/android/dist/jniLibs/<abi>/libballistic_wallet.so
# wallet/target/android/dist/bin/<abi>/ballistic-wallet
```

Needs the Android NDK, stable Rust Android targets and `cargo-ndk`. The script builds both
`https` and `tor` by default, with no OpenSSL. `--features tor` builds only Tor; asking that build
for direct HTTPS fails with `unsupported_transport`. Dependencies and `Cargo.lock` stay pinned.
Cargo defaults to two build jobs (`CARGO_BUILD_JOBS` overrides it). Rust target installation
failures stop the script before compilation; both ABIs build sequentially in one cargo-ndk run.

The C entry point is `ballistic_wallet_balance` (`src/ffi.rs`); a JNI adapter is still required.
Call it on a worker thread with a UTF-8 JSON request and a writable output buffer. Return 0 means
a complete balance, 1 a JSON failure, and negative returns mean the output is unusable. Never
render a previous buffer after a negative return. The buffer is caller-sized; no fixed output
size is guaranteed. Malformed request JSON produces a fixed, sanitized failure message.

The optional cancel pointer must reference aligned native 32-bit atomic storage that remains
alive until the blocking call returns. Use atomic writes from the cancelling thread; a Kotlin
`Int` or ordinary concurrent C assignment does not meet this contract. An already-set flag stops
before runtime creation; subsequent cancellation is polled every 50 ms. The full query deadline
includes Tor bootstrap. Cancellation drops the query; it cannot recall network bytes already
sent, and `shutdown_background` does not wait for blocking runtime work to finish.

For Tor on Android, supply `state_dir` under the app's private files directory. The adapter
requires an absolute path and passes `<state_dir>/state` and `<state_dir>/cache` to Arti. The host
must choose the app-private location; this library does not prove arbitrary absolute paths belong
to the app. These files are Tor state and directory cache, not cached balances. A fresh isolation
group is used for each query. The adapter bounds RPC response bodies to 1 MiB during streaming,
including Tor, and never falls back to direct HTTPS.

Native Android execution, JNI loading, app-private storage behavior, UI cancellation, cold/warm
timing, battery/data use and the actual Tor exit seen by an endpoint remain unverified.

### Hardening verification (7 November 2026)

The post-`ca2b5ff` changes cap response collection while streaming on both transports, sanitize
FFI parse/URL errors, remove the incorrect fixed output-buffer guarantee, and prioritize a
ready cancellation before starting the query. The FFI also checks a preset cancel flag before
parsing or creating a runtime. These changes have been reviewed for integration; device
verification remains pending as described below.

Fresh Linux x86_64 checks with Rust 1.99.1 and the pinned wallet lockfile:

- HTTPS: 8 unit tests and 13 mock-RPC integration tests passed.
- HTTPS + Tor, release: 8 wallet unit tests, 12 mock-RPC integration tests and 2 vendored
  streaming-limit tests passed. These mocks use direct loopback HTTP; they do not test a Tor circuit.
- No features and Tor-only: 8 unit tests passed in each configuration. Three CLI probes of
  unavailable transports returned `unsupported_transport`, with no connection accepted by a
  local listener.
- The selected HTTPS + Tor normal dependency graph contains no Alloy, OpenSSL, `native-tls`
  or `hyper-tls`. The saved vendor patch matches the saved upstream source snapshot.

Local test logs, disabled-transport probe results and build provenance are under ignored
`wallet/target/verification-20261107/`. Existing caches were reused; Cargo concurrency was two
jobs per host. The standalone vendor lockfile left by the earlier interrupted test attempt is
preserved and ignored; the checks above use `wallet/Cargo.lock`.

The hardened Android rebuild started at **03:28:27 UTC** on 7 November, using remote stable
Rust/Cargo **1.96.0**, cargo-ndk **4.1.2**, NDK **27.3.13750724**, API **33**, features
`https,tor`, both ABIs and two build jobs. The source snapshot was verified remotely before
launch. Its SHA-256 manifest digest is
`63247639265c412a27361ddece68a904d0d1bea7a9a02420cd56ca3931415926`.
The build-input manifest digest is
`7e5b47810b1690d7391a2085a8648172f5806d0820045f754f8b0d34d5588c9a`;
subsequent README/ignore edits do not change those build inputs.

**Build completion and fresh binary sizes/hashes are not yet verified.** The build host
`100.114.231.86` went offline at 03:30 UTC, and repeated SSH status/collection attempts timed
out. The last successful status showed the arm64 wallet compilation running. The detached job
was not cancelled or duplicated. Inspect the existing job before considering a restart:

- Remote build directory: `~/ballistic_wallet_android/` (launcher PID recorded as `612899`).
- Log and terminal exit status: `verification-20261107/android-build.log` and
  `verification-20261107/android-build.exit` under that directory.
- Fresh output destination: `dist-hardened-20261107/`; the older `dist/` artifacts predate
  hardening and must not be used to validate this patch.
- Once the host is reachable, run `wallet/target/verification-20261107/collect-android.sh`.
  It requires exit status zero, checks the remote source snapshot, records each artifact's
  SHA-256, size, ELF dependencies/alignment and exported C symbol, then downloads and verifies
  all four binaries into `wallet/target/android/dist/`. It never starts a build or uses adb.

No emulator or physical device was accessed for this verification. The wallet emulator turn
remains subject to the parent's queue; neither Android HTTPS nor Android Tor execution is
claimed. The offline source is ready for review; device validation still needs the completed
rebuild and a queued emulator turn.

Remaining host integration: a Kotlin Tools-port adapter, owner-editable wallet settings/grants,
a balance card with source/block/time and transport, and loading/cancel controls. Keep public
balance results labeled `rpc_reported`; Tor neither hides the requested address from the RPC
provider nor cryptographically verifies its answer. Test blocked Tor without direct fallback
and use controlled endpoint logs before claiming observed source-IP protection.
