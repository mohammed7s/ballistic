//! Controls that place a phone call (SPEC.md §7.1: calls are confirmation-gated like sends).
//! Recognized from the control's own label and resource-id words, the same identity sources
//! as the confirmation words in `actions.rs`, and never from content. The rule is generic, not
//! per app: a label that starts with "call" ("Call", "Call back", "Call Ana") or names a voice or
//! video call, the AOSP dialpad button's label "dial", or a resource id that names a call button
//! (`dialpad_voice_call_button`, `call_action`). Navigation that only mentions calls ("Call
//! history", `tab_call_history`, "Call settings") is not a call control, so tabs stay unguarded
//! and do not count toward the guarded-repeat stop.

fn words(s: &str) -> Vec<String> {
    s.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).map(str::to_lowercase).collect()
}

/// Words that make a "call" label or id a list, log, tab or settings entry, not a call.
const NAVIGATION: &[&str] = &["history", "log", "logs", "settings", "tab", "recents", "list", "details", "blocking", "blocked"];
/// Resource-id words that mark an activatable control.
const CONTROL: &[&str] = &["button", "fab", "action", "icon"];

/// The control places (or may place) a call.
pub fn places_call(label: Option<&str>, resource_id: Option<&str>) -> bool {
    let navigation = |w: &[String]| w.iter().any(|x| NAVIGATION.contains(&x.as_str()));
    if let Some(l) = label {
        let w = words(l);
        let voice_or_video = w.windows(2).any(|p| matches!((p[0].as_str(), p[1].as_str()), ("voice" | "video" | "phone", "call")));
        if !navigation(&w) && (w.first().is_some_and(|f| f == "call") || voice_or_video || w == ["dial"]) {
            return true;
        }
    }
    if let Some(r) = resource_id {
        let w = words(r.rsplit(":id/").next().unwrap_or(r));
        if !navigation(&w) && w.iter().any(|x| x == "call") && w.iter().any(|x| CONTROL.contains(&x.as_str())) {
            return true;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::places_call;

    #[test]
    fn call_controls_and_call_navigation() {
        for (label, rid) in [(Some("Call"), None), (Some("Call back"), None), (Some("Call Ana Lopez"), None), (Some("Video call"), None),
            (Some("dial"), Some("com.google.android.dialer:id/dialpad_voice_call_button")), (None, Some("com.example:id/call_action")),
            (Some("Start voice call"), None)] {
            assert!(places_call(label, rid), "{label:?} {rid:?}");
        }
        for (label, rid) in [(Some("Recents"), Some("com.google.android.dialer:id/tab_call_history")), (Some("Call history"), None),
            (Some("Call settings"), None), (Some("key pad"), Some("com.google.android.dialer:id/dialpad_fab")), (Some("Callback number"), None),
            (Some("Recall"), None), (Some("Speed dial"), Some("com.google.android.dialer:id/tab_speed_dial")), (None, None)] {
            assert!(!places_call(label, rid), "{label:?} {rid:?}");
        }
    }
}
