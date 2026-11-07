//! Data that crosses the four ports. Everything here is plain data with a JSON form; the same
//! shapes are used on the JSON-lines bridge to the harness and (later) through UniFFI to the
//! Android and iOS shells. Design: core/README.md §4 (ports), §6 (actions), §7 (provenance).

use serde::{Deserialize, Serialize};

/// Identifier of a node within one observation. Not stable across observations.
pub type NodeId = u32;

/// What a node is, as far as the filter can tell. Derived from class name and flags.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Button,
    Text,
    Edit,
    Image,
    List,
    Item,
    Switch,
    Checkbox,
    Slider,
    Tab,
    Container,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Bounds {
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
}

impl Bounds {
    pub fn width(&self) -> i32 {
        self.x2 - self.x1
    }
    pub fn height(&self) -> i32 {
        self.y2 - self.y1
    }
    pub fn area(&self) -> i64 {
        (self.width().max(0) as i64) * (self.height().max(0) as i64)
    }
    pub fn centre(&self) -> (i32, i32) {
        ((self.x1 + self.x2) / 2, (self.y1 + self.y2) / 2)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Flags {
    pub clickable: bool,
    pub long_clickable: bool,
    pub editable: bool,
    pub scrollable: bool,
    pub checkable: bool,
    pub checked: bool,
    pub selected: bool,
    pub enabled: bool,
    pub focused: bool,
    pub visible: bool,
}

/// One accessibility node, as the host saw it. `label` is what the control IS (content
/// description, button caption, hint); `text` is what it SHOWS (may be someone else's content).
/// The host fills both from the platform's fields; the filter (§5) decides which the planner sees.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Node {
    pub id: NodeId,
    #[serde(default)]
    pub parent: Option<NodeId>,
    pub role: Role,
    #[serde(default)]
    pub class_name: Option<String>,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub resource_id: Option<String>,
    #[serde(default)]
    pub package: Option<String>,
    pub bounds: Bounds,
    #[serde(default)]
    pub flags: Flags,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppEntry {
    pub label: String,
    pub package: String,
    /// Component the host starts for this app, if it knows one (harness: AndroidWorld's table).
    #[serde(default)]
    pub activity: Option<String>,
}

/// One observation of the screen. Nodes are in reading order with parents before children.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScreenState {
    pub package: String,
    pub activity: String,
    pub width: i32,
    pub height: i32,
    pub nodes: Vec<Node>,
    /// Sent once per session by the host; core keeps the last non-empty list.
    #[serde(default)]
    pub installed_apps: Vec<AppEntry>,
    /// Host authority, never model text. None parses old recordings but denies execution.
    #[serde(default)]
    pub capabilities: Option<HostCapabilities>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

/// What the planner may emit (§6). `target` fields are element ids from the filtered view
/// (`e7` → 7); core resolves them to nodes and coordinates before anything reaches Hands.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Action {
    OpenApp { app: String },
    Tap { target: u32 },
    LongPress { target: u32 },
    Type {
        target: u32,
        text: String,
        #[serde(default)]
        submit: bool,
    },
    Scroll {
        #[serde(default)]
        target: Option<u32>,
        direction: Direction,
    },
    Swipe { target: u32, direction: Direction },
    Back,
    Home,
    Wait,
    Read {
        targets: Vec<u32>,
        question: String,
        shape: ReadShape,
    },
    Answer { text: String },
    Done { evidence: String },
    GiveUp { reason: String },
}

/// The fixed shapes a reader answer may take (§7). Anything outside the shape is dropped.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadShape {
    Element,
    Facts,
    Summary,
}

/// Where a value came from. Set by ports and the filter, never by a model (§7).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(tag = "kind", content = "detail", rename_all = "snake_case")]
pub enum Source {
    /// The owner's own words in this request.
    OwnerRequest,
    /// The owner's own data (address book, history).
    OwnerData,
    /// A control's label on screen (what it IS).
    ScreenLabel,
    /// Content shown by an app: package, and the node it came from.
    App(String),
    Sms(String),
    Note(String),
    Web(String),
    /// A JSON-RPC endpoint's report, via the Tools port: endpoint host and transport.
    Rpc { source: String, transport: String },
}

impl Source {
    /// Trusted sources may become action arguments without the taint rule firing.
    pub fn is_owner(&self) -> bool {
        matches!(self, Source::OwnerRequest | Source::OwnerData | Source::ScreenLabel)
    }
}

/// A string plus where it came from.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tagged {
    pub value: String,
    pub source: Source,
}

/// What Hands actually executes: coordinates and strings only, no element ids.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
pub enum HandsOp {
    OpenApp {
        package: String,
        #[serde(default)]
        activity: Option<String>,
    },
    Tap { x: i32, y: i32 },
    LongPress { x: i32, y: i32 },
    /// Tap the field first, then type. `submit` presses Enter afterwards.
    Type {
        x: i32,
        y: i32,
        text: String,
        submit: bool,
    },
    /// Drag inside `area` (the target's bounds, or the screen) so that content moves to reveal
    /// what lies in `direction`: scroll down = finger moves up.
    Scroll { area: Bounds, direction: Direction },
    /// Drag across `area` in `direction` itself (sliders, carousels): swipe right = finger
    /// moves right.
    Swipe { area: Bounds, direction: Direction },
    Back,
    Home,
    Wait { millis: u32 },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "result", rename_all = "snake_case")]
pub enum HandsResult {
    Ok,
    Error { message: String },
    /// Host guarantees no input was dispatched.
    NotExecuted { message: String },
}

/// Which model answers. Each may be a different backend (§12).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelRole {
    Planner,
    Reader,
    Judge,
    Verifier,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Completion {
    pub text: String,
    #[serde(default)]
    pub usage: Usage,
}

/// What the owner is shown before a guarded action (§9). Every value names its source.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConfirmRequest {
    /// Exact resolved parameters covered by this one-use approval.
    pub operation: HandsOp,
    pub app: String,
    pub verb: String,
    pub target: String,
    #[serde(default)]
    pub values: Vec<Tagged>,
    pub hard: bool,
    pub reason: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConfirmResult {
    Approved,
    Denied,
}

/// A host can restrict the shipped action set, never extend it with executable app text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HostCapabilities {
    pub operations: Vec<String>,
    pub app_grants: Vec<String>,
    /// Owner grants for read-only tools (`tools.rs`). Absent: no tool may be called.
    #[serde(default)]
    pub tool_grants: Vec<crate::tools::ToolGrant>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionStatus { NotExecuted, Executed, Unknown }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VerificationStatus { Verified, Failed, Unknown }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Verification {
    pub status: VerificationStatus,
    /// Set by core. A model never upgrades itself to authoritative readback.
    pub evidence_basis: String,
    pub nodes: Vec<String>,
}
impl Default for Verification {
    fn default() -> Self {
        Self { status: VerificationStatus::Unknown, evidence_basis: "none".into(), nodes: vec![] }
    }
}
