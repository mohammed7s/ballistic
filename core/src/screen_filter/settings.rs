//! Conservative AOSP 13 Settings page signatures, not a title allowlist.
//!
//! Generic preference IDs and SubSettings toolbar titles are NOT identities: a SIM or SSID
//! can supply them. Recognize only the complete observed static dashboard envelopes below.
//! Every node in the Settings tree must be accounted for, including ownership and children.
//! Extra device rows, gear widgets, injected tiles, missing anchors, or a different layout
//! invalidate the whole signature. Row order, node numbers, summaries and checked values do
//! not select an identity. See docs/SETTINGS_OBSERVATION_20261107.md for pinned source/proof
//! and the intentionally unsupported Wi-Fi/Bluetooth switch pages.
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
        if n.package.as_deref() != Some(PKG) || n.role != role
            || n.class_name.as_deref() != Some(class) || n.resource_id.as_deref() != rid
            || n.flags.editable || n.flags.long_clickable || n.flags.selected
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
            let switch = children.iter().find(|n| n.resource_id.as_deref() == Some("android:id/switch_widget"))?;
            self.shape(switch, Role::Switch, "android.widget.Switch", Some("android:id/switch_widget"))?;
            if !switch.flags.checkable || switch.flags.clickable || switch.flags.scrollable
                || !Self::empty(switch) || !self.visible(switch) || !self.children(switch).is_empty() {
                return None;
            }
            // The filter folds the actual observed checked flag into this already proven row.
        }
        Some(())
    }
}

struct Row {
    title: Option<&'static str>,
    clickable: bool,
    summary: bool,
    switch: bool,
    /// Must actually be visible; hidden optional rows cannot establish the page identity.
    anchor: bool,
}

impl Row {
    const fn nav(title: &'static str, summary: bool) -> Self {
        Self { title: Some(title), clickable: true, summary, switch: false, anchor: true }
    }
}

// network_provider_internet.xml; only the recorded non-tether-switch variant is supported.
const NETWORK: &[Row] = &[
    Row::nav("Internet", true),
    Row::nav("Calls & SMS", true),
    Row::nav("SIMs", true),
    Row { title: Some("Airplane mode"), clickable: true, summary: false, switch: true, anchor: true },
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
    Row { title: Some("Saved devices"), clickable: false, summary: false, switch: false, anchor: true },
    Row::nav("See all", true),
    Row::nav("Connection preferences", true),
    Row { title: None, clickable: false, summary: false, switch: false, anchor: false },
];

pub(super) fn labels(screen: &ScreenState) -> HashMap<NodeId, &'static str> {
    prove(screen).unwrap_or_default()
}

fn prove(screen: &ScreenState) -> Option<HashMap<NodeId, &'static str>> {
    if screen.package != PKG || screen.activity != SUB_SETTINGS || screen.width <= 0 || screen.height <= 0 {
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
    if content_children.len() != 2 { return None; }
    let toolbar = content_children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/collapsing_toolbar"))?;
    let list = content_children.iter().find(|n| n.resource_id.as_deref() == Some("com.android.settings:id/recycler_view"))?;
    tree.shape(toolbar, Role::Container, "android.widget.FrameLayout", Some("com.android.settings:id/collapsing_toolbar"))?;
    if !Tree::passive(toolbar) || toolbar.text.is_some() || !tree.visible(toolbar) { return None; }
    let (page, specs) = match toolbar.label.as_deref()? {
        "Network & internet" => ("Network & internet", NETWORK),
        "Connected devices" => ("Connected devices", CONNECTED),
        _ => return None,
    };
    let toolbar_children = tree.children(toolbar);
    if toolbar_children.len() != 2 { return None; }
    let up = toolbar_children.iter().find(|n| n.role == Role::Button)?;
    tree.shape(up, Role::Button, "android.widget.ImageButton", None)?;
    if !up.flags.clickable || up.flags.checkable || up.flags.scrollable
        || up.label.as_deref() != Some("Navigate up") || up.text.is_some()
        || !tree.visible(up) || !tree.children(up).is_empty() { return None; }
    let heading = toolbar_children.iter().find(|n| n.role == Role::Text)?;
    tree.text(heading, None)?;
    if heading.text.as_deref() != Some(page) || heading.label.is_some() { return None; }
    tree.plain_container(list, Role::List, "androidx.recyclerview.widget.RecyclerView", Some("com.android.settings:id/recycler_view"))?;
    let rows = tree.children(list);
    if rows.len() != specs.len() { return None; }
    let mut labels = HashMap::new();
    let mut used = HashSet::new();
    // Match fixed titles only inside the COMPLETE schema, never by row index or screen position.
    for spec in specs {
        let matches: Vec<_> = rows.iter().filter(|r| !used.contains(&r.id) && tree.children(r).iter().any(|n|
            n.resource_id.as_deref() == Some("android:id/title")
                && spec.title.is_none_or(|title| n.text.as_deref() == Some(title)))).collect();
        let [row] = matches[..] else { return None; };
        tree.row(row, spec, list.flags.scrollable)?;
        used.insert(row.id);
        // Optional hidden rows help reject a different layout but cannot themselves become
        // an actionable identity unless both the row and its actual title are visible.
        if spec.clickable && tree.visible(row) && tree.children(row).iter().any(|n|
            n.resource_id.as_deref() == Some("android:id/title") && tree.visible(n)) {
            labels.insert(row.id, spec.title?);
        }
    }
    // No unaccounted Settings descendants (or Settings nodes rooted elsewhere) may hide a
    // device row/overlay. Foreign children inside the envelope fail shape checks as well.
    if screen.nodes.iter().any(|n| n.package.as_deref() == Some(PKG) && !tree.seen.contains(&n.id)) {
        return None;
    }
    Some(labels)
}
