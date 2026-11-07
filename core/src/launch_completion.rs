//! A deliberately small owner-only launch postcondition. No action dispatch, model text,
//! benchmark data, aliases, or fuzzy matching participates in recognition or verification.
use crate::{capabilities, types::*, verification::Assessment};

pub const FOREGROUND_CONTRACT: &str = "foreground_component_v1";

#[derive(Debug, PartialEq, Eq)]
pub enum LaunchGoal {
    Target(String),
    /// Launch-shaped but unknown or ambiguous: a model cannot fill in the missing identity.
    Unresolved,
}

/// Full-string grammar: [please ](open|launch) [the ]<exact label/package>[ app][.].
/// Enumerate complete forms rather than truncating at an app name, punctuation or conjunction.
/// Restricted label vocabulary deliberately leaves complicated names/requests to normal planning.
/// Even installed labels may contain instructions; those must not expand this grammar.
pub fn recognize(request: &str, apps: &[AppEntry]) -> Option<LaunchGoal> {
    if request.chars().any(char::is_control) { return None; }
    let request = request.trim_matches(' ').to_lowercase();
    let request = request.strip_prefix("please ").unwrap_or(&request);
    let tail = request.strip_prefix("open ").or_else(|| request.strip_prefix("launch "))?;
    if tail.is_empty() || tail.trim() != tail || tail.contains(". ")
        || tail.split_whitespace().any(|word| word == "-" || word == "_")
        || !tail.chars().all(|c| c.is_alphanumeric() || " ._-".contains(c)) {
        return None;
    }
    // Full words only. Reject implicit sequencing and side-effect verbs as well as explicit
    // conjunctions, including when a malicious/odd installed label contains the entire suffix.
    const RESERVED: &[&str] = &[
        "and", "or", "then", "also", "with", "to", "before", "after", "while", "but",
        "without", "if", "when", "unless", "until", "once", "for", "except",
        "in", "on", "at", "from", "by", "as", "using", "tomorrow", "later",
        "open", "launch", "send", "delete", "change", "set", "turn", "create", "save",
        "add", "remove", "pay", "install", "grant", "reply", "write", "start", "stop",
        "share", "post", "enable", "disable", "rename", "move", "copy", "buy", "schedule",
        "call", "text", "email", "forward", "take", "record", "edit", "update", "erase",
        "clear", "switch", "reset", "select", "confirm", "approve", "submit", "execute",
        "run", "toggle", "export", "import", "download", "upload", "purchase", "cancel",
        "unlock", "lock", "connect", "disconnect", "compose", "attach",
    ];
    if tail.split(|c: char| !c.is_alphanumeric()).any(|w| RESERVED.contains(&w)) {
        return None;
    }
    let mut packages = std::collections::HashSet::new();
    for app in apps {
        if app.package.is_empty() { continue; }
        for name in [&app.label, &app.package] {
            if name.is_empty() || name.trim() != name { continue; }
            for article in ["", "the "] {
                for suffix in ["", " app"] {
                    for punctuation in ["", "."] {
                        if tail == format!("{article}{}{suffix}{punctuation}", name.to_lowercase()) {
                            packages.insert(app.package.clone());
                        }
                    }
                }
            }
        }
    }
    Some(if packages.len() == 1 {
        LaunchGoal::Target(packages.into_iter().next().unwrap())
    } else { LaunchGoal::Unresolved })
}

fn unknown(feedback: &'static str) -> Assessment {
    Assessment { verification: Verification::default(), feedback }
}

fn advertised(screen: &ScreenState, package: &str) -> bool {
    screen.capabilities.as_ref().is_some_and(|h|
        h.observations.iter().any(|o| o == FOREGROUND_CONTRACT)
        && capabilities::permitted(&HandsOp::OpenApp { package: package.into(), activity: None },
            package, Some(h)))
}

fn consistent(screen: &ScreenState) -> Option<&ForegroundObservation> {
    screen.foreground.as_ref().filter(|f|
        f.sequence > 0 && !f.package.is_empty() && !f.activity.trim().is_empty()
        && f.package == screen.package && f.activity == screen.activity)
}

/// `fresh` must come from a new Screen.observe call in the Done branch, with cancellation
/// checked after it. The high-water mark covers all earlier observations in this run.
/// This validates a trusted host assertion, not app content or a cryptographic OS receipt.
pub fn assess(goal: &LaunchGoal, before: &ScreenState, fresh: &ScreenState,
    high_water: u64, request: &str, apps: &[AppEntry]) -> Assessment {
    let LaunchGoal::Target(target) = goal else {
        return unknown("launch target is unknown or ambiguous; completion not established");
    };
    let current_apps = if fresh.installed_apps.is_empty() { apps } else { &fresh.installed_apps };
    if recognize(request, current_apps).as_ref() != Some(goal) {
        return unknown("installed launch target changed; completion not established");
    }
    if !advertised(before, target) || !advertised(fresh, target) {
        return unknown("foreground observation capability or app grant unavailable");
    }
    let (Some(previous), Some(observation)) = (consistent(before), consistent(fresh)) else {
        return unknown("explicit consistent foreground observation unavailable");
    };
    if observation.sequence <= high_water.max(previous.sequence) {
        return unknown("foreground observation is stale; completion not established");
    }
    if observation.package != *target {
        return unknown("requested app is not observed in the foreground");
    }
    Assessment {
        verification: Verification {
            status: VerificationStatus::Verified,
            evidence_basis: "code_validated_host_foreground".into(),
            nodes: vec![], foreground: Some(observation.clone()),
        },
        feedback: "requested app observed in foreground by host readback",
    }
}
