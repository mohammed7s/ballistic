//! Interaction and label changes from improvement loop 2, on screens recorded in the Haiku 3 and
//! Opus 5.5 suites (b22da1f, 9 Nov 2026): the Internet page while Wi-Fi scans, DocumentsUI's roots
//! drawer, a category chip the request names, and a long press on a row the request names.
//! Variants that change recorded fields are marked as derived. No model, emulator or checker.
use ballistic_core::{actions::{self, ActionError}, screen_filter::{self, FilterContext, FilteredScreen}, types::*};
use serde_json::Value;

fn fixture(name: &str) -> ScreenState {
    let text = match name {
        "internet_scanning" => include_str!("screens/settings_static/internet_on_scanning.json"),
        "drawer" => include_str!("screens/navigation/files_roots_drawer.json"),
        "storage" => include_str!("screens/navigation/files_storage_root.json"),
        "music" => include_str!("screens/navigation/files_music_grid.json"),
        "chips" => include_str!("screens/navigation/expense_category_chips.json"),
        _ => panic!("unknown fixture {name}"),
    };
    serde_json::from_value(serde_json::from_str::<Value>(text).unwrap()["screen"].clone()).unwrap()
}
fn view(s: &ScreenState, request: &str) -> FilteredScreen { screen_filter::filter(s, &FilterContext { request, typed: &[] }) }
fn labeled<'a>(v: &'a FilteredScreen, label: &str) -> Vec<&'a screen_filter::Element> {
    v.elements.iter().filter(|e| e.label.as_deref() == Some(label)).collect()
}

const WIFI_OFF: &str = "Turn wifi off.";
const FILES: &str = "Delete the file sure_ant_2023_09_19.mp3 from the Android filesystem located in the Music folder within the sdk_gphone_x86_64 storage area.";
const EXPENSE: &str = "Add the following expenses into the pro expense:\nname|amount_dollars|category_name|note\nTaxi Fare|$179.68|Transportation|A need";

// ---- Settings > Internet while Wi-Fi scans ----------------------------------------------------

#[test]
fn recorded_internet_page_with_the_scan_bar_names_the_wifi_switch() {
    // Recorded in Haiku 3: the scan progress bar made the page unproven, so the Wi-Fi switch was
    // `switch «c6: 5 chars» [on]` and the planner toggled another switch.
    let s = fixture("internet_scanning");
    let v = view(&s, WIFI_OFF);
    let wifi = labeled(&v, "Wi-Fi");
    assert_eq!(wifi.len(), 1, "{}", screen_filter::render(&v));
    assert_eq!(wifi[0].role, Role::Switch);
    assert!(wifi[0].flags.checked);
    for other in ["Add network", "Network preferences", "Saved networks", "Non-carrier data usage"] {
        assert_eq!(labeled(&v, other).len(), 1, "{other}");
    }
    // SSID and carrier rows stay content.
    let rendered = screen_filter::render(&v);
    assert!(!rendered.contains("AndroidWifi") && !rendered.contains("T-Mobile"), "{rendered}");
}

#[test]
fn a_scan_bar_with_text_or_another_extra_child_proves_nothing() {
    let s = fixture("internet_scanning");
    let bar = s.nodes.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/progress_bar_animation")).unwrap().id;
    // Derived: the bar carries a caption; or a second unknown child sits next to it.
    let mut captioned = s.clone();
    captioned.nodes.iter_mut().find(|n| n.id == bar).unwrap().text = Some("Wi-Fi".into());
    assert!(labeled(&view(&captioned, WIFI_OFF), "Wi-Fi").is_empty());
    let mut extra = s.clone();
    let mut copy = extra.nodes.iter().find(|n| n.id == bar).unwrap().clone();
    copy.id = 9999; copy.resource_id = Some("com.android.settings:id/other".into());
    extra.nodes.push(copy);
    assert!(labeled(&view(&extra, WIFI_OFF), "Wi-Fi").is_empty());
}

// ---- DocumentsUI roots drawer ---------------------------------------------------------------

#[test]
fn recorded_roots_drawer_names_its_os_roots_and_the_single_device_storage() {
    let v = view(&fixture("drawer"), FILES);
    for root in ["Recent", "Images", "Videos", "Audio", "Documents", "Downloads", "Device storage"] {
        assert_eq!(labeled(&v, root).len(), 1, "{root}: {}", screen_filter::render(&v));
    }
    // The device's own name stays content.
    assert!(!screen_filter::render(&v).contains("sdk_gphone64_x86_64"));
}

#[test]
fn an_unknown_or_extra_root_names_no_drawer_row() {
    let s = fixture("drawer");
    let title = |s: &ScreenState, t: &str| s.nodes.iter().position(|n| n.text.as_deref() == Some(t)).unwrap();
    // Derived: an app's provider root above the divider; a second root below it.
    let mut app_root = s.clone();
    let i = title(&app_root, "Videos");
    app_root.nodes[i].text = Some("Delete everything".into());
    assert!(labeled(&view(&app_root, FILES), "Device storage").is_empty());
    assert!(labeled(&view(&app_root, FILES), "Recent").is_empty());
    let mut two = s.clone();
    let i = title(&two, "sdk_gphone64_x86_64");
    let (row, text) = (two.nodes[i - 1].clone(), two.nodes[i].clone());
    let (mut row2, mut text2) = (row.clone(), text.clone());
    row2.id = 900; row2.bounds.y1 += 147; row2.bounds.y2 += 147;
    text2.id = 901; text2.parent = Some(900); text2.text = Some("Drive".into()); text2.bounds.y1 += 147; text2.bounds.y2 += 147;
    two.nodes.push(row2); two.nodes.push(text2);
    assert!(labeled(&view(&two, FILES), "Device storage").is_empty());
    // A duplicate OS title (a lookalike root) names nothing either.
    let mut dup = s.clone();
    let i = title(&dup, "Audio");
    dup.nodes[i].text = Some("Images".into());
    assert!(labeled(&view(&dup, FILES), "Images").is_empty());
}

// ---- named chip and named row ---------------------------------------------------------------

#[test]
fn recorded_category_chip_the_request_names_can_be_tapped() {
    // Recorded in Opus 5.5: `item "Transportation" (matches request) id:cd_category [off]` was
    // shown after `scroll until`, but a checkable match-only item was not tappable.
    let s = fixture("chips");
    let v = view(&s, EXPENSE);
    let chip = v.elements.iter().find(|e| e.shown_content.iter().any(|c| c == "Transportation")).expect("matched chip");
    assert!(chip.flags.checkable && chip.label.is_none());
    assert_eq!(actions::validate(&Action::Tap { target: chip.id }, &v), Ok(()));
    // Other chips (content not in the request) stay unidentified.
    let other = v.elements.iter().find(|e| e.resource_id.as_deref() == Some("com.arduia.expense:id/cd_category") && e.shown_content.is_empty()).unwrap();
    assert_eq!(actions::validate(&Action::Tap { target: other.id }, &v), Err(ActionError::Unidentified(other.id)));
    // Derived: the same element as a switch, in Settings, or not clickable is not a chip.
    for change in ["switch", "settings", "not_clickable"] {
        let mut v2 = v.clone();
        let e = v2.elements.iter_mut().find(|e| e.id == chip.id).unwrap();
        match change { "switch" => e.role = Role::Switch, "settings" => e.package = "com.android.settings".into(), _ => e.flags.clickable = false }
        assert!(actions::validate(&Action::Tap { target: chip.id }, &v2).is_err(), "{change}");
    }
}

#[test]
fn a_clickable_row_the_request_names_can_be_long_pressed_without_the_flag() {
    // Derived from the recorded Music grid: one title set to the requested file name. DocumentsUI
    // rows are clickable but not flagged long-clickable; a long press selects them.
    let mut s = fixture("music");
    let title = s.nodes.iter_mut().find(|n| n.text.as_deref() == Some("2023_04_07_jazz_song.mp3")).unwrap();
    title.text = Some("sure_ant_2023_09_19.mp3".into());
    let v = view(&s, FILES);
    let row = v.elements.iter().find(|e| e.shown_content.iter().any(|c| c == "sure_ant_2023_09_19.mp3")).expect("matched row");
    assert!(row.flags.clickable && !row.flags.long_clickable);
    assert_eq!(actions::validate(&Action::LongPress { target: row.id }, &v), Ok(()));
    // Near-name rows (content only) are still not long-pressable.
    let near = v.elements.iter().find(|e| e.resource_id.as_deref() == Some("com.google.android.documentsui:id/item_root") && e.shown_content.is_empty()).unwrap();
    assert!(actions::validate(&Action::LongPress { target: near.id }, &v).is_err());
}
