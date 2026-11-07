//! Optional read-only Tools port contracts. No tools are currently supported.
//! A host grant alone cannot introduce an executable tool: a future tool needs a typed
//! request variant, core validation and an explicit planner action before it can be used.

use serde::{Deserialize, Serialize};

/// No request variants are implemented. Arbitrary host/model JSON cannot become a request.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "tool")]
pub enum ToolRequest {}

/// The owner's grant for a tool. Retained for host contract compatibility; currently inert.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ToolGrant {
    pub tool: String,
    #[serde(default)]
    pub scope: Vec<GrantScope>,
}

/// A configured resource covered by a grant; its optional label is owner-supplied data.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GrantScope {
    pub id: String,
    #[serde(default)]
    pub label: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "result", rename_all = "snake_case")]
pub enum ToolResult {
    Failed { code: String, #[serde(default)] message: String },
}
