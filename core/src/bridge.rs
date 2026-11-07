//! The four ports over a pipe: one JSON object per line, core asks, the host answers.
//! Used by the harness (`harness/core_bridge.py`). Every line is also what the run log records,
//! so a logged run can be replayed through the same code (core/README.md §15, rules 3–4).
//!
//! Core → host (one line):   {"id": 7, "ask": "observe"}
//!                           {"id": 8, "ask": "act", "op": {...HandsOp...}}
//!                           {"id": 9, "ask": "complete", "role": "planner", "messages": [...], "max_tokens": 800}
//!                           {"id": 10, "ask": "confirm", "request": {...ConfirmRequest...}}
//!                           {"id": 11, "ask": "tool", "request": {...ToolRequest...}}
//!                           {"id": 0, "ask": "log", "event": {...}}            (no answer expected)
//! Host → core (one line):   {"id": 7, "ok": true, "data": {...}}
//!                           {"id": 7, "ok": false, "error": "text"}

use std::io::{BufRead, Write};

use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value;

use crate::ports::*;
use crate::tools::{ToolRequest, ToolResult};
use crate::types::*;

#[derive(Serialize)]
struct Ask<'a, T: Serialize> {
    id: u64,
    ask: &'a str,
    #[serde(flatten)]
    body: T,
}

#[derive(Deserialize)]
struct Reply {
    id: u64,
    ok: bool,
    #[serde(default)]
    data: Value,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Serialize)]
struct Empty {}

#[derive(Serialize)]
struct ActBody<'a> {
    op: &'a HandsOp,
}

#[derive(Serialize)]
struct CompleteBody<'a> {
    role: ModelRole,
    messages: &'a [ChatMessage],
    max_tokens: u32,
}

#[derive(Serialize)]
struct ConfirmBody<'a> {
    request: &'a ConfirmRequest,
}

#[derive(Serialize)]
struct ToolBody<'a> {
    request: &'a ToolRequest,
}

#[derive(Serialize)]
struct LogBody<'a> {
    event: &'a Value,
}

/// A host on the other end of two byte streams. Implements all four ports.
pub struct Bridge<R: BufRead, W: Write> {
    reader: R,
    writer: W,
    next_id: u64,
}

impl<R: BufRead, W: Write> Bridge<R, W> {
    pub fn new(reader: R, writer: W) -> Self {
        Bridge { reader, writer, next_id: 1 }
    }

    fn send<T: Serialize>(&mut self, id: u64, ask: &str, body: T) -> PortResult<()> {
        let line = serde_json::to_string(&Ask { id, ask, body })
            .map_err(|e| PortError::Malformed(e.to_string()))?;
        self.writer
            .write_all(line.as_bytes())
            .and_then(|_| self.writer.write_all(b"\n"))
            .and_then(|_| self.writer.flush())
            .map_err(|e| PortError::Disconnected(e.to_string()))
    }

    fn ask<T: Serialize, D: DeserializeOwned>(&mut self, ask: &str, body: T) -> PortResult<D> {
        let id = self.next_id;
        self.next_id += 1;
        self.send(id, ask, body)?;
        loop {
            let mut line = String::new();
            let n = self
                .reader
                .read_line(&mut line)
                .map_err(|e| PortError::Disconnected(e.to_string()))?;
            if n == 0 {
                return Err(PortError::Disconnected("host closed the pipe".into()));
            }
            if line.trim().is_empty() {
                continue;
            }
            let reply: Reply = serde_json::from_str(&line)
                .map_err(|e| PortError::Malformed(format!("{e}: {}", line.trim())))?;
            if reply.id != id {
                // Out-of-order replies are a host bug; say so rather than guess.
                return Err(PortError::Malformed(format!(
                    "expected reply {id}, got {}",
                    reply.id
                )));
            }
            if !reply.ok {
                return Err(PortError::Host(reply.error.unwrap_or_default()));
            }
            return serde_json::from_value(reply.data)
                .map_err(|e| PortError::Malformed(e.to_string()));
        }
    }

    /// Fire-and-forget line for the host's run log.
    pub fn log(&mut self, event: &Value) -> PortResult<()> {
        self.send(0, "log", LogBody { event })
    }
}

impl<R: BufRead, W: Write> Screen for Bridge<R, W> {
    fn observe(&mut self) -> PortResult<ScreenState> {
        self.ask("observe", Empty {})
    }
}

impl<R: BufRead, W: Write> Hands for Bridge<R, W> {
    fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult> {
        self.ask("act", ActBody { op })
    }
}

impl<R: BufRead, W: Write> Models for Bridge<R, W> {
    fn complete(
        &mut self,
        role: ModelRole,
        messages: &[ChatMessage],
        max_tokens: u32,
    ) -> PortResult<Completion> {
        self.ask("complete", CompleteBody { role, messages, max_tokens })
    }
}

impl<R: BufRead, W: Write> Confirm for Bridge<R, W> {
    fn ask(&mut self, request: &ConfirmRequest) -> PortResult<ConfirmResult> {
        Bridge::ask(self, "confirm", ConfirmBody { request })
    }
}

impl<R: BufRead, W: Write> Tools for Bridge<R, W> {
    fn call(&mut self, request: &ToolRequest) -> PortResult<ToolResult> {
        self.ask("tool", ToolBody { request })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn observe_round_trip() {
        let reply = r#"{"id":1,"ok":true,"data":{"package":"com.a","activity":".Main","width":1080,"height":2400,"nodes":[]}}"#;
        let mut out = Vec::new();
        let mut b = Bridge::new(Cursor::new(format!("{reply}\n")), &mut out);
        let s = b.observe().unwrap();
        assert_eq!(s.package, "com.a");
        assert_eq!(s.nodes.len(), 0);
        let sent = String::from_utf8(out).unwrap();
        assert_eq!(sent.trim(), r#"{"id":1,"ask":"observe"}"#);
    }

    #[test]
    fn host_error_is_reported() {
        let reply = r#"{"id":1,"ok":false,"error":"adb died"}"#;
        let mut out = Vec::new();
        let mut b = Bridge::new(Cursor::new(format!("{reply}\n")), &mut out);
        let err = b.act(&HandsOp::Home).unwrap_err();
        assert!(matches!(err, PortError::Host(m) if m == "adb died"));
    }
}
