//! The four doors into core (core/README.md §4). A host implements these; core never touches a
//! platform directly. The harness implements them over a JSON-lines pipe (`bridge.rs`); the
//! Android and iOS shells implement them in-process through UniFFI.

use crate::tools::{ToolRequest, ToolResult};
use crate::types::*;

#[derive(Debug, thiserror::Error)]
pub enum PortError {
    #[error("host disconnected: {0}")]
    Disconnected(String),
    #[error("host returned malformed data: {0}")]
    Malformed(String),
    #[error("host error: {0}")]
    Host(String),
}

pub type PortResult<T> = Result<T, PortError>;

/// Eyes. Returns the current screen; retries an empty tree inside the port.
pub trait Screen {
    fn observe(&mut self) -> PortResult<ScreenState>;
}

/// Hands. Executes exactly what it is told, in screen coordinates. Never decides anything.
pub trait Hands {
    fn act(&mut self, op: &HandsOp) -> PortResult<HandsResult>;
}

/// One completion per call. The host routes `role` to a backend (core/README.md §12).
pub trait Models {
    fn complete(
        &mut self,
        role: ModelRole,
        messages: &[ChatMessage],
        max_tokens: u32,
    ) -> PortResult<Completion>;
}

/// Asks the owner. The harness answers from a script; the app shows the confirmation window.
pub trait Confirm {
    fn ask(&mut self, request: &ConfirmRequest) -> PortResult<ConfirmResult>;
}

/// Optional read-only queries outside the screen. No request variants are currently supported;
/// future implementations must validate typed requests and owner grants before dispatch.
pub trait Tools {
    fn call(&mut self, request: &ToolRequest) -> PortResult<ToolResult>;
}
