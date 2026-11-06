//! Parse a model screen assessment; valid citations do not constitute authoritative readback.
use serde::Deserialize;
use crate::{screen_filter::FilteredScreen, types::*};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Verdict {
    status: VerificationStatus,
    nodes: Vec<String>,
    evidence_basis: String,
    #[serde(default)]
    reason: String,
}
pub fn assess(text: &str, screen: &FilteredScreen) -> Verification {
    let text = text.trim().trim_start_matches("```json").trim_start_matches("```").trim_end_matches("```").trim();
    let Ok(v) = serde_json::from_str::<Verdict>(text) else { return Verification::default(); };
    let _ = v.reason; // Never return model prose to the planner.
    let valid = !v.nodes.is_empty() && v.nodes.iter().all(|id| id.strip_prefix('e')
        .filter(|s| !s.starts_with('0')).and_then(|s| s.parse::<u32>().ok())
        .and_then(|id| screen.element(id)).is_some());
    if v.evidence_basis != "visible_ui" || (v.status != VerificationStatus::Unknown && !valid) {
        return Verification::default();
    }
    Verification { status: v.status, evidence_basis: "model_assessment".into(), nodes: v.nodes }
}
