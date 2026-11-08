//! A deliberately small owner-only launch postcondition. No action dispatch, model text,
//! benchmark data, aliases, or fuzzy matching participates in recognition or verification.
use crate::{capabilities, types::*, verification::Assessment};

pub const FOREGROUND_CONTRACT: &str = "foreground_component_v1";

#[derive(Debug, Clone, PartialEq, Eq)]
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

/// A conditional sentence some owners (and AndroidWorld goals) append to a launch request. It
/// asks for nothing unless a pop-up appears, so its postcondition is "no pop-up is showing".
/// Exact sentences only, compared case-insensitively with collapsed spaces; nothing else may
/// follow the launch sentence.
const POPUP_CLAUSES: &[&str] = &[
    "clear any pop-ups that may appear by granting all permissions that are required",
    "clear any popups that may appear by granting all permissions that are required",
];

/// [`recognize`], or the launch sentence followed by exactly one [`POPUP_CLAUSES`] sentence.
/// Returns the goal, whether the pop-up clause is present, and the launch sentence alone.
pub fn recognize_with_popups(request: &str, apps: &[AppEntry]) -> Option<(LaunchGoal, bool, String)> {
    if let Some(goal) = recognize(request, apps) { return Some((goal, false, request.to_string())); }
    if request.chars().any(char::is_control) { return None; }
    let (head, tail) = request.trim().split_once(". ")?;
    let clause = tail.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    let clause = clause.strip_suffix('.').unwrap_or(&clause);
    if !POPUP_CLAUSES.contains(&clause) { return None; }
    let head = format!("{head}.");
    recognize(&head, apps).map(|goal| (goal, true, head))
}

const PERMISSION_PACKAGES: &[&str] = &["com.android.permissioncontroller", "com.google.android.permissioncontroller"];
const DIALOG_IDS: &[&str] = &["android:id/alertTitle", "android:id/button1", "android:id/button2", "android:id/button3", "android:id/parentPanel"];
const IME_PACKAGES: &[&str] = &["com.google.android.inputmethod.latin", "com.android.inputmethod.latin"];

/// True when the fresh tree shows no permission prompt, no platform alert dialog and no window
/// of any other app over the target (status bar and keyboard excepted). An in-app pop-up drawn
/// with ordinary views cannot be recognized and is not claimed absent beyond this.
pub fn no_popup(fresh: &ScreenState, target: &str) -> bool {
    fresh.nodes.iter().filter(|n| n.flags.visible && n.bounds.area() > 0).all(|n| {
        let package = n.package.as_deref().unwrap_or(&fresh.package);
        !PERMISSION_PACKAGES.contains(&package)
            && !n.resource_id.as_deref().is_some_and(|r| DIALOG_IDS.contains(&r))
            && (package == target || package == "com.android.systemui" || IME_PACKAGES.contains(&package))
    })
}

/// [`assess`] for [`recognize_with_popups`]: the launch postcondition on `launch_request` (the
/// launch sentence), plus [`no_popup`] when the clause is present.
pub fn assess_with_popups(goal: &LaunchGoal, popups: bool, launch_request: &str, before: &ScreenState, fresh: &ScreenState,
    high_water: u64, apps: &[AppEntry]) -> Assessment {
    let assessment = assess(goal, before, fresh, high_water, launch_request, apps);
    if !popups || assessment.verification.status != VerificationStatus::Verified { return assessment; }
    let LaunchGoal::Target(target) = goal else { return assessment; };
    if !no_popup(fresh, target) {
        return unknown("a pop-up or another window is showing over the requested app; clear it, then check again");
    }
    Assessment { feedback: "requested app observed in foreground by host readback, with no pop-up showing", ..assessment }
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
