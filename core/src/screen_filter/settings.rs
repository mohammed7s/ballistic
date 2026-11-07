//! Conservative AOSP 13 Settings page signatures, not a title allowlist.
//!
//! Generic preference IDs and SubSettings toolbar titles are NOT identities: a SIM or SSID
//! can supply them. Recognize only the complete observed static dashboard envelopes below.
//! Every node in the Settings tree must be accounted for, including ownership and children.
//! Internet permits the observed opaque carrier/SSID row forms; Connection preferences permits
//! exactly the observed three opaque injected rows. Unrecognized rows/widgets/descendants,
//! missing anchors or ownership, and other layouts invalidate the whole signature.
//! Row order, node numbers, summaries and checked values do
//! not select an identity. See docs/SETTINGS_OBSERVATION_20261107.md for pinned source/proof
//! including the captured Display, Internet and Bluetooth switch variants.
use std::collections::{HashMap, HashSet};

use crate::types::*;

const PKG: &str = "com.android.settings";
pub(super) const SUB_SETTINGS: &str = "com.android.settings.SubSettings";

struct Tree<'a> {
    screen: &'a ScreenState,
    children: HashMap<NodeId, Vec<&'a Node>>,
    seen: HashSet<NodeId>,
}

impl<'a> Tree<'a> {
    fn children(&self, n: &Node) -> Vec<&'a Node> {
        self.children.get(&n.id).cloned().unwrap_or_default()
    }

    fn visible(&self, n: &Node) -> bool {
        n.flags.visible && n.bounds.area() > 0
            && super::on_screen(&n.bounds, self.screen.width, self.screen.height)
    }

    fn shape(&mut self, n: &Node, role: Role, class: &str, rid: Option<&str>) -> Option<()> {
        self.shape_with_long_click(n, role, class, rid, false)
    }

    fn shape_with_long_click(&mut self, n: &Node, role: Role, class: &str, rid: Option<&str>, long: bool) -> Option<()> {
        if n.package.as_deref() != Some(PKG) || n.role != role
            || n.class_name.as_deref() != Some(class) || n.resource_id.as_deref() != rid
            || n.flags.editable || n.flags.long_clickable != long || n.flags.selected
            || !self.seen.insert(n.id) {
            return None;
        }
        Some(())
    }

    fn passive(n: &Node) -> bool {
        !n.flags.clickable && !n.flags.checkable && !n.flags.scrollable
    }

    fn empty(n: &Node) -> bool {
        n.label.is_none() && n.text.is_none()
    }

    fn text(&mut self, n: &Node, rid: Option<&str>) -> Option<()> {
        self.shape(n, Role::Text, "android.widget.TextView", rid)?;
        if !Self::passive(n) || !self.children(n).is_empty() { return None; }
        Some(())
    }

    fn plain_container(&mut self, n: &Node, role: Role, class: &str, rid: Option<&str>) -> Option<()> {
        self.shape(n, role, class, rid)?;
        if !Self::empty(n) || n.flags.clickable || n.flags.checkable || !self.visible(n) {
            return None;
        }
        Some(())
    }

    /// Generic row structure is meaningful only after ALL siblings match a page signature.
    fn row(&mut self, n: &Node, spec: &Row, scrollable: bool) -> Option<()> {
        let role = if !spec.clickable { Role::Container }
            else if scrollable { Role::Item } else { Role::Button };
        self.shape(n, role, "android.widget.LinearLayout", None)?;
        if !Self::empty(n) || n.flags.clickable != spec.clickable || n.flags.checkable
            || n.flags.scrollable || (spec.anchor && !self.visible(n)) {
            return None;
        }
        let children = self.children(n);
        if children.len() != 1 + usize::from(spec.summary) + usize::from(spec.switch) {
            return None;
        }
        let title = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/title"))?;
        self.text(title, Some("android:id/title"))?;
        if spec.title.is_some_and(|t| title.text.as_deref() != Some(t))
            || title.label.is_some() || (spec.anchor && !self.visible(title)) {
            return None;
        }
        if spec.summary {
            let summary = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/summary"))?;
            self.text(summary, Some("android:id/summary"))?;
            // Deliberately never inspect summary strings, including carrier/SSID/device names.
        }
        if spec.switch {
            let rid = if spec.split { "com.android.settings:id/switchWidget" } else { "android:id/switch_widget" };
            let switch = children.iter().find(|n| n.resource_id.as_deref() == Some(rid))?;
            self.shape(switch, Role::Switch, "android.widget.Switch", Some(rid))?;
            let caption_ok = if spec.split { switch.label.as_deref() == spec.title && switch.text.is_none() }
                else { Self::empty(switch) };
            if !switch.flags.checkable || switch.flags.clickable != spec.split || switch.flags.scrollable
                || !caption_ok || (spec.anchor && !self.visible(switch)) || !self.children(switch).is_empty() {
                return None;
            }
            // The filter folds the actual observed checked flag into this already proven row.
        }
        Some(())
    }
    fn main_switch(&mut self, n: &Node) -> Option<()> {
        self.shape(n, Role::Item, "android.widget.LinearLayout", Some("com.android.settings:id/switch_bar"))?;
        if !Self::empty(n) || !n.flags.clickable || n.flags.checkable || n.flags.scrollable || !self.visible(n) { return None; }
        let children = self.children(n);
        if children.len() != 2 { return None; }
        let title = children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/switch_text"))?;
        self.text(title, Some("com.android.settings:id/switch_text"))?;
        if title.text.as_deref() != Some("Use Bluetooth") || title.label.is_some() || !self.visible(title) { return None; }
        let switch = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/switch_widget"))?;
        self.shape(switch, Role::Switch, "android.widget.Switch", Some("android:id/switch_widget"))?;
        if !switch.flags.checkable || switch.flags.clickable || switch.flags.scrollable || !Self::empty(switch)
            || !self.visible(switch) || !self.children(switch).is_empty() { return None; }
        Some(())
    }

    fn footer(&mut self, row: &Node, learn_more: bool) -> Option<()> {
        self.shape(row, Role::Container, "android.widget.LinearLayout", None)?;
        if !Self::passive(row) || !Self::empty(row) || !self.visible(row) { return None; }
        let children = self.children(row);
        if children.len() != 1 + usize::from(learn_more) { return None; }
        let title = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/title"))?;
        self.text(title, Some("android:id/title"))?;
        if learn_more {
            let more = children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/settingslib_learn_more"))?;
            self.text(more, Some("com.android.settings:id/settingslib_learn_more"))?;
        }
        // Neither footer text nor a local device name is a control identity.
        Some(())
    }

    fn internet_extras(&mut self, extras: &[&Node], scrollable: bool, labels: &mut HashMap<NodeId, &'static str>) -> Option<()> {
        let role = if scrollable { Role::Item } else { Role::Button };
        let (mut mobile, mut wifi, mut add, mut footer) = (0, 0, 0, 0);
        for row in extras {
            let children = self.children(row);
            if row.role == Role::Container {
                self.footer(row, true)?; footer += 1; continue;
            }
            let gear = children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/settings_button"));
            let qr = children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/button_icon"));
            let long = row.flags.long_clickable;
            self.shape_with_long_click(row, role, "android.widget.LinearLayout", None, long)?;
            if !row.flags.clickable || row.flags.checkable || row.flags.scrollable || !self.visible(row) || row.text.is_some() { return None; }
            let title = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/title"))?;
            self.text(title, Some("android:id/title"))?;
            if !self.visible(title) { return None; }
            if let Some(button) = qr {
                if long || !Self::empty(row) || children.len() != 2 || title.text.as_deref() != Some("Add network") || title.label.is_some() { return None; }
                self.shape(button, Role::Button, "android.widget.ImageButton", Some("com.android.settings:id/button_icon"))?;
                if button.label.as_deref() != Some("Scan QR code") || button.text.is_some() || !button.flags.clickable
                    || button.flags.checkable || button.flags.scrollable || !self.visible(button) || !self.children(button).is_empty() { return None; }
                labels.insert(row.id, "Add network"); add += 1;
            } else {
                let summary = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/summary"))?;
                self.text(summary, Some("android:id/summary"))?;
                if let Some(button) = gear {
                    // The recorded mobile-provider preference is a two-target row. Carrier title,
                    // summary and gear caption remain content, including static-title collisions.
                    if long || !Self::empty(row) || children.len() != 3 { return None; }
                    self.shape(button, Role::Image, "android.widget.ImageView", Some("com.android.settings:id/settings_button"))?;
                    if !button.flags.clickable || button.flags.checkable || button.flags.scrollable || button.text.is_some()
                        || !self.visible(button) || !self.children(button).is_empty() { return None; }
                    mobile += 1;
                } else {
                    // LongPressWifiEntryPreference owns these dynamic titles/descriptions. The
                    // source creates repeated rows; strings are never parsed to identify Wi-Fi.
                    if !long || children.len() != 2 { return None; }
                    wifi += 1;
                }
            }
        }
        // Exact observed layout families, independent of the switch's checked value.
        // The mobile row remains opaque; unrecorded connected-network gear variants reject.
        if mobile != 1 || !((wifi >= 1 && add == 1 && footer == 0) || (wifi == 0 && add == 0 && footer == 1)) { return None; }
        Some(())
    }

}

struct Row {
    title: Option<&'static str>,
    clickable: bool,
    summary: bool,
    switch: bool,
    split: bool,
    /// Must actually be visible; hidden optional rows cannot establish the page identity.
    anchor: bool,
}

impl Row {
    const fn nav(title: &'static str, summary: bool) -> Self {
        Self { title: Some(title), clickable: true, summary, switch: false, split: false, anchor: true }
    }
}

// network_provider_internet.xml; only the recorded non-tether-switch variant is supported.
const NETWORK: &[Row] = &[
    Row::nav("Internet", true),
    Row::nav("Calls & SMS", true),
    Row::nav("SIMs", true),
    Row { title: Some("Airplane mode"), clickable: true, summary: false, switch: true, split: false, anchor: true },
    Row::nav("Hotspot & tethering", true),
    Row::nav("Data Saver", true),
    Row::nav("VPN", true),
    Row::nav("Mobile plan", false),
    Row::nav("Private DNS", true),
    Row { anchor: false, ..Row::nav("Adaptive connectivity", true) },
];

// connected_devices.xml with no dynamic device rows. Footer title is intentionally arbitrary
// content: DiscoverableFooterPreferenceController can append the local device name.
const CONNECTED: &[Row] = &[
    Row::nav("Pair new device", true),
    Row { title: Some("Saved devices"), clickable: false, summary: false, switch: false, split: false, anchor: true },
    Row::nav("See all", true),
    Row::nav("Connection preferences", true),
    Row { title: None, clickable: false, summary: false, switch: false, split: false, anchor: false },
];

const DISPLAY: &[Row] = &[
    Row::heading("Brightness", true), Row::nav("Brightness level", true),
    Row::heading("Lock display", true), Row::nav("Lock screen", true), Row::nav("Screen timeout", true),
    Row::heading("Appearance", true),
    Row { switch: true, split: true, ..Row::nav("Dark theme", true) },
    Row::nav("Screen saver", true), Row::nav("Display size and text", false),
    Row::heading("Color", true), Row { anchor: false, ..Row::nav("Colors", false) },
    Row::heading("Other display controls", false),
    Row { anchor: false, switch: true, split: true, ..Row::nav("Auto-rotate screen", true) },
];
const CONNECTION: &[Row] = &[Row::nav("Bluetooth", false), Row::nav("Cast", true), Row::nav("Printing", true)];
const INTERNET: &[Row] = &[
    Row { switch: true, ..Row::nav("Wi-Fi", false) }, Row::nav("Network preferences", true),
    Row::nav("Saved networks", true), Row::nav("Non-carrier data usage", true),
];
const BLUETOOTH: &[Row] = &[Row::nav("Device name", true), Row::nav("Pair new device", false)];
const BLUETOOTH_BRIEF: &[Row] = &[Row::nav("Pair new device", true)];
impl Row {
    const fn heading(title: &'static str, anchor: bool) -> Self {
        Self { title: Some(title), clickable: false, summary: false, switch: false, split: false, anchor }
    }
}

/// Dedicated activities are not a fallback source of raw OS labels. The same envelopes are
/// also reached through SubSettings. An activity alone never proves a control identity.
pub(super) fn dedicated_activity(activity: &str) -> bool {
    matches!(activity, "com.android.settings.Settings$DisplaySettingsActivity"
        | "com.android.settings.Settings$NetworkDashboardActivity"
        | "com.android.settings.Settings$ConnectedDeviceDashboardActivity"
        | "com.android.settings.Settings$WifiSettingsActivity")
}

pub(super) fn labels(screen: &ScreenState) -> HashMap<NodeId, &'static str> {
    prove(screen).unwrap_or_default()
}

fn prove(screen: &ScreenState) -> Option<HashMap<NodeId, &'static str>> {
    if screen.package != PKG || (screen.activity != SUB_SETTINGS && !dedicated_activity(&screen.activity)) || screen.width <= 0 || screen.height <= 0 {
        return None;
    }
    let mut children: HashMap<NodeId, Vec<&Node>> = HashMap::new();
    let mut ids = HashSet::new();
    for n in &screen.nodes {
        // Duplicate IDs, dangling parents and children preceding parents are ambiguous.
        if n.parent.is_some_and(|p| !ids.contains(&p)) || !ids.insert(n.id) { return None; }
        if let Some(p) = n.parent { children.entry(p).or_default().push(n); }
    }
    let mut tree = Tree { screen, children, seen: HashSet::new() };
    let roots: Vec<_> = screen.nodes.iter().filter(|n| n.parent.is_none() && n.package.as_deref() == Some(PKG)).collect();
    let [root] = roots[..] else { return None; };
    tree.plain_container(root, Role::Container, "android.widget.FrameLayout", None)?;
    if root.flags.scrollable { return None; }
    let root_children = tree.children(root);
    let [content] = root_children[..] else { return None; };
    tree.plain_container(content, Role::List, "android.widget.ScrollView", Some("com.android.settings:id/content_parent"))?;
    if !content.flags.scrollable { return None; }
    let content_children = tree.children(content);
    if !(2..=3).contains(&content_children.len()) { return None; }
    let toolbar = content_children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/collapsing_toolbar"))?;
    let list = content_children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/recycler_view"))?;
    tree.shape(toolbar, Role::Container, "android.widget.FrameLayout", Some("com.android.settings:id/collapsing_toolbar"))?;
    if !Tree::passive(toolbar) || toolbar.text.is_some() || !tree.visible(toolbar) { return None; }
    let (page, specs, activity) = match toolbar.label.as_deref()? {
        "Network & internet" => ("Network & internet", NETWORK, "com.android.settings.Settings$NetworkDashboardActivity"),
        "Connected devices" => ("Connected devices", CONNECTED, "com.android.settings.Settings$ConnectedDeviceDashboardActivity"),
        "Display" => ("Display", DISPLAY, "com.android.settings.Settings$DisplaySettingsActivity"),
        "Internet" => ("Internet", INTERNET, "com.android.settings.Settings$WifiSettingsActivity"),
        "Connection preferences" => ("Connection preferences", CONNECTION, SUB_SETTINGS),
        "Bluetooth" => ("Bluetooth", BLUETOOTH, SUB_SETTINGS),
        _ => return None,
    };
    if screen.activity != SUB_SETTINGS && screen.activity != activity { return None; }
    if content_children.len() != if page == "Bluetooth" { 3 } else { 2 } { return None; }
    let toolbar_children = tree.children(toolbar);
    if toolbar_children.len() != if page == "Internet" { 3 } else { 2 } { return None; }
    let up = toolbar_children.iter().find(|n| n.role == Role::Button)?;
    tree.shape(up, Role::Button, "android.widget.ImageButton", None)?;
    if !up.flags.clickable || up.flags.checkable || up.flags.scrollable
        || up.label.as_deref() != Some("Navigate up") || up.text.is_some()
        || !tree.visible(up) || !tree.children(up).is_empty() { return None; }
    let heading = toolbar_children.iter().find(|n| n.role == Role::Text && !n.flags.clickable)?;
    tree.text(heading, None)?;
    if heading.text.as_deref() != Some(page) || heading.label.is_some() { return None; }
    if page == "Internet" {
        let fix = toolbar_children.iter().find(|n| n.role == Role::Text && n.flags.clickable)?;
        tree.shape(fix, Role::Text, "android.widget.TextView", None)?;
        if fix.label.as_deref() != Some("Fix connectivity") || fix.text.is_some() || !fix.flags.clickable
            || fix.flags.checkable || fix.flags.scrollable || !tree.visible(fix) || !tree.children(fix).is_empty() { return None; }
        // No label for this recovery action: not needed to identify the Wi-Fi switch.
    }
    tree.plain_container(list, Role::List, "androidx.recyclerview.widget.RecyclerView", Some("com.android.settings:id/recycler_view"))?;
    let rows = tree.children(list);
    let mut labels = HashMap::new();
    let mut used = HashSet::new();
    // ConnectedDevice controllers remove Pair/See-all summaries; neither variant proves state.
    let brief_connected = page == "Connected devices" && rows.iter().any(|r|
        tree.children(r).iter().any(|n| n.text.as_deref() == Some("Pair new device"))
        && !tree.children(r).iter().any(|n| n.resource_id.as_deref() == Some("android:id/summary")));
    // Bluetooth without the rename row retains Pair navigation and its explanatory summary.
    // This layout does not select or imply the switch state; the observed flag is preserved.
    let specs = if page == "Bluetooth" && rows.len() == 2 { BLUETOOTH_BRIEF } else { specs };
    for spec in specs {
        let matches: Vec<_> = rows.iter().filter(|r| !used.contains(&r.id) && !r.flags.long_clickable
            && !tree.children(r).iter().any(|n| n.resource_id.as_deref() == Some("com.android.settings:id/settings_button"))
            && tree.children(r).iter().any(|n|
            n.resource_id.as_deref() == Some("android:id/title")
                && spec.title.is_none_or(|title| n.text.as_deref() == Some(title)))).collect();
        let [row] = matches[..] else { return None; };
        let adjusted;
        let spec = if brief_connected && matches!(spec.title, Some("Pair new device" | "See all")) {
            adjusted = Row { summary: false, ..*spec }; &adjusted
        } else { spec };
        tree.row(row, spec, list.flags.scrollable)?;
        used.insert(row.id);
        if tree.visible(row) && tree.children(row).iter().any(|n|
            n.resource_id.as_deref() == Some("android:id/title") && tree.visible(n)) {
            if let Some(title) = spec.title {
                if spec.clickable { labels.insert(row.id, title); }
                else if page == "Display" {
                    // Passive category containers are lifted by the filter; label the proven
                    // TextView itself so its static heading survives that transformation.
                    let heading = tree.children(row).into_iter().find(|n| n.resource_id.as_deref() == Some("android:id/title"))?;
                    labels.insert(heading.id, title);
                }
                if spec.split {
                    let switch = tree.children(row).into_iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/switchWidget"))?;
                    if tree.visible(switch) { labels.insert(switch.id, title); }
                }
            }
        }
    }
    let extras: Vec<_> = rows.iter().copied().filter(|n| !used.contains(&n.id)).collect();
    match page {
        "Internet" => tree.internet_extras(&extras, list.flags.scrollable, &mut labels)?,
        "Connection preferences" => {
            // Compatibility restriction, not an AOSP identity: the recorded installation has
            // three injected rows. They share the generic title+summary shape of Cast/Printing,
            // so arbitrary cardinality would allow an injected row to replace a missing anchor.
            // Keep this complete observed envelope until the host can distinguish origins.
            // No injected title/summary is emitted; collisions reject the entire signature.
            if extras.len() != 3 { return None; }
            for row in extras { tree.row(row, &Row { title: None, ..Row::nav("", true) }, list.flags.scrollable)?; }
        }
        "Bluetooth" => {
            let [footer] = extras[..] else { return None; };
            tree.footer(footer, false)?;
            let bar = content_children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/switch_bar"))?;
            tree.main_switch(bar)?;
            labels.insert(bar.id, "Bluetooth");
        }
        _ if !extras.is_empty() => return None,
        _ => (),
    }
    if page == "Display" { labels.insert(up.id, "Navigate up"); labels.insert(toolbar.id, "Display"); }
    // No unaccounted Settings descendants (or Settings nodes rooted elsewhere) may hide a
    // device row/overlay. Foreign children inside the envelope fail shape checks as well.
    if screen.nodes.iter().any(|n| n.package.as_deref() == Some(PKG) && !tree.seen.contains(&n.id)) {
        return None;
    }
    Some(labels)
}
