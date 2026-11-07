//! Validate citations in a model screen assessment, never authoritative readback.
use std::collections::HashSet;

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
/// Fixed feedback can guide another observation without feeding verifier prose back to the
/// planner. Citation validity is necessary, but does not prove semantic coverage of the request.
pub struct Assessment {
    pub verification: Verification,
    pub feedback: &'static str,
}

fn unknown(feedback: &'static str) -> Assessment {
    Assessment { verification: Verification::default(), feedback }
}

pub fn assess_with_feedback(text: &str, screen: &FilteredScreen) -> Assessment {
    let text = text.trim().trim_start_matches("```json").trim_start_matches("```").trim_end_matches("```").trim();
    let Ok(v) = serde_json::from_str::<Verdict>(text) else {
        return unknown("invalid verifier response");
    };
    let _ = v.reason; // Never return model prose to the planner.
    if v.evidence_basis != "visible_ui" {
        return unknown("unsupported evidence basis");
    }
    if v.status == VerificationStatus::Unknown {
        return Assessment {
            verification: Verification { status: v.status, evidence_basis: "model_assessment".into(), nodes: v.nodes },
            feedback: "current screen does not establish every requested postcondition",
        };
    }
    if v.nodes.is_empty() {
        return unknown("no current evidence cited");
    }
    let mut seen = HashSet::new();
    for id in &v.nodes {
        let element = id.strip_prefix('e').and_then(|n| n.parse::<u32>().ok())
            .filter(|n| format!("e{n}") == *id).and_then(|n| screen.element(n));
        let Some(element) = element else { return unknown("citation is not a current canonical element id"); };
        if !seen.insert(id) {
            return unknown("duplicate evidence citation");
        }
        let named = element.label.as_ref().is_some_and(|s| !s.trim().is_empty());
        // State alone cannot establish which setting a switch controls. Matched words such
        // as "off", resource ids and reader picks never grant control identity.
        if (element.flags.checkable || element.role == Role::Switch) && !named {
            return unknown("citation does not identify the control");
        }
        // An opaque cN reference says only that content exists. Resource ids and geometry
        // cannot reveal its value. Require something the verifier actually received.
        if !named && element.shown_content.is_empty() && element.typed.is_empty() {
            return unknown("citation has no readable evidence; inspect the requested result");
        }
    }
    Assessment {
        feedback: if v.status == VerificationStatus::Verified { "model assessed complete" }
            else { "visible evidence does not satisfy the requested postcondition" },
        verification: Verification { status: v.status, evidence_basis: "model_assessment".into(), nodes: v.nodes },
    }
}

pub fn assess(text: &str, screen: &FilteredScreen) -> Verification {
    assess_with_feedback(text, screen).verification
}
