//! Honest early answers (core/README.md §7, "Honest early answers"). Some requests need a
//! capability the host does not advertise: setting an alarm, switching airplane mode, changing a
//! setting such as Wi-Fi when the host cannot tap (no Computer Control). The loop would only open
//! an app and stop, after planner calls. Core answers before any planner call instead, in plain
//! words, from the host's advertised operations and app grants on the first observation.
//!
//! Recognition reads the owner's words only (whole words, case-insensitive). A match can only
//! refuse; it never authorizes anything, and a request it misses goes to the loop as before, where
//! every grant and capability check still applies.

use crate::types::*;

/// What the request needs that the host does not advertise, and the answer for the owner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Gap {
    pub answer: &'static str,
    /// Code-owned description of the missing capability, for the report.
    pub needs: &'static str,
}

const CLOCK_PACKAGES: &[&str] = &["com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"];
const SETTINGS_PACKAGES: &[&str] = &["com.android.settings", "com.android.systemui"];

/// Settings the owner might ask to change; the loop changes them through Settings or Quick
/// Settings, which needs taps.
const SETTINGS_NOUNS: &[&str] = &[
    "wifi", "wi-fi", "bluetooth", "brightness", "dark mode", "dark theme", "do not disturb", "flashlight", "torch",
    "mobile data", "hotspot", "location", "nfc", "auto-rotate", "auto rotate", "volume", "battery saver",
];
const CHANGE_WORDS: &[&str] = &["turn on", "turn off", "turn up", "turn down", "switch on", "switch off", "enable", "disable",
    "toggle", "set", "change", "increase", "decrease", "lower", "raise", "max", "maximum", "minimum"];

fn words(request: &str) -> String {
    let lower = request.to_lowercase();
    let spaced: String = lower.chars().map(|c| if c.is_alphanumeric() || c == '-' { c } else { ' ' }).collect();
    format!(" {} ", spaced.split_whitespace().collect::<Vec<_>>().join(" "))
}

fn has(w: &str, phrase: &str) -> bool { w.contains(&format!(" {phrase} ")) }

fn can_tap_in(host: Option<&HostCapabilities>, packages: &[&str]) -> bool {
    host.is_some_and(|h| h.operations.iter().any(|o| o == "tap")
        && h.app_grants.iter().any(|g| packages.contains(&g.as_str())))
}

/// The first capability the request needs and the host lacks, or `None`.
pub fn gap(request: &str, host: Option<&HostCapabilities>) -> Option<Gap> {
    let w = words(request);
    if (has(&w, "alarm") || has(&w, "alarms") || has(&w, "wake me")) && !can_tap_in(host, CLOCK_PACKAGES) {
        let set = ["set", "create", "add", "make", "schedule", "wake me", "change", "turn on", "turn off", "delete", "remove"]
            .iter().any(|v| has(&w, v));
        return Some(Gap {
            answer: if set { "I can't set alarms on this phone yet." } else { "I can't check alarms on this phone yet." },
            needs: "taps in a granted clock app",
        });
    }
    if (has(&w, "airplane mode") || has(&w, "aeroplane mode") || has(&w, "flight mode")) && !can_tap_in(host, SETTINGS_PACKAGES) {
        return Some(Gap { answer: "I can't turn airplane mode on or off on this phone yet.", needs: "taps in Settings or Quick Settings" });
    }
    if SETTINGS_NOUNS.iter().any(|n| has(&w, n)) && CHANGE_WORDS.iter().any(|v| has(&w, v)) && !can_tap_in(host, SETTINGS_PACKAGES) {
        return Some(Gap { answer: "I can't change settings like Wi-Fi, Bluetooth or brightness on this phone yet.",
            needs: "taps in Settings or Quick Settings" });
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(operations: &[&str], grants: &[&str]) -> HostCapabilities {
        HostCapabilities { operations: operations.iter().map(|s| s.to_string()).collect(),
            app_grants: grants.iter().map(|s| s.to_string()).collect(), observations: vec![], tool_grants: vec![] }
    }

    #[test]
    fn the_phone_without_computer_control_answers_at_once() {
        // What the Android app advertises today (IntentHands: open_app and wait).
        let phone = host(&["open_app", "wait"], &["com.google.android.deskclock", "com.android.settings"]);
        assert_eq!(gap("Set an alarm for 7am", Some(&phone)).unwrap().answer, "I can't set alarms on this phone yet.");
        assert_eq!(gap("what alarms do I have?", Some(&phone)).unwrap().answer, "I can't check alarms on this phone yet.");
        assert_eq!(gap("wake me up at 6", Some(&phone)).unwrap().answer, "I can't set alarms on this phone yet.");
        assert!(gap("Turn on airplane mode", Some(&phone)).unwrap().answer.contains("airplane mode"));
        assert!(gap("turn off Wi-Fi", Some(&phone)).unwrap().answer.starts_with("I can't change settings"));
        assert!(gap("Turn brightness to the max value.", Some(&phone)).is_some());
        // Things it can do, or that are not these requests, go to the loop.
        for other in ["open the clock app", "open Settings", "what is the wifi password?", "text Ana I'm late", "alarmed by the news? open Chrome"] {
            assert_eq!(gap(other, Some(&phone)), None, "{other}");
        }
    }

    #[test]
    fn a_host_that_can_tap_in_the_right_app_gets_the_loop() {
        let emulator = host(&["open_app", "tap", "type", "swipe", "scroll", "back", "home", "wait"],
            &["com.google.android.deskclock", "com.android.settings", "com.android.systemui"]);
        for request in ["Set an alarm for 7am", "Turn on airplane mode", "turn off Wi-Fi", "Turn brightness to the max value."] {
            assert_eq!(gap(request, Some(&emulator)), None, "{request}");
        }
        // Taps, but the clock app is not granted: alarms are still out of reach.
        let no_clock = host(&["open_app", "tap"], &["com.android.settings"]);
        assert!(gap("set an alarm", Some(&no_clock)).is_some());
        // Nothing advertised (an old recording) is nothing granted.
        assert!(gap("set an alarm", None).is_some());
    }
}
