//! Ballistic core: the agent. Design in `core/README.md`.
//!
//! Hosts (harness, Android app, iOS app) implement the four traits in [`ports`] and call the
//! agent loop. Nothing in this crate knows about Android, ADB or HTTP endpoints beyond what the
//! Models port is told in its configuration.

pub mod bridge;
pub mod ports;
pub mod types;

pub mod actions;
pub mod screen_filter;
mod typed_value;
pub mod agent;
pub mod context;
pub mod run;
pub mod reader;
pub mod judge;
pub mod skills;
pub mod readback;

pub mod values;
pub mod chat;

pub mod capabilities;
pub mod tools;
pub mod verification;
pub mod launch_completion;

#[cfg(feature = "uniffi")]
pub mod ffi;
#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();
