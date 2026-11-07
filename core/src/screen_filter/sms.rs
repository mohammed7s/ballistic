//! Simple SMS Messenger 5.19.3 recipient confirmation, before pruning/folding.
//! Source and observed host-tree envelope: docs/SMS_CONTROL_20261107.md.
//! Package/resource matching is compatibility recognition, not origin/version attestation.
use std::collections::HashSet;

use crate::types::*;

const PKG: &str = "com.simplemobiletools.smsmessenger";
pub(super) const LABEL: &str = "Open conversation";

/// The pinned listener reads the address and opens ThreadActivity (or rejects an invalid
/// short code). Identity does not assert input validity, confirm an operation, or send text.
/// Number values, contact names, captions, request text and node numbers supply no identity.
pub(super) fn confirmation_node(screen: &ScreenState) -> Option<usize> {
    if screen.package != PKG
        || screen.activity != "com.simplemobiletools.smsmessenger.activities.NewConversationActivity"
        || screen.width <= 0 || screen.height <= 0 {
        return None;
    }
    let nodes = &screen.nodes;
    let mut seen = HashSet::new();
    if nodes.iter().any(|n| !seen.insert(n.id)) { return None; }

    // Count even hidden/foreign copies: pruning must not turn ambiguous IDs into identities.
    let unique = |rid: &str| {
        let mut matches = nodes.iter().enumerate().filter(|(_, n)| n.resource_id.as_deref() == Some(rid));
        let (i, _) = matches.next()?;
        if matches.next().is_some() { None } else { Some(i) }
    };
    let control = unique("com.simplemobiletools.smsmessenger:id/new_conversation_confirm")?;
    let address = unique("com.simplemobiletools.smsmessenger:id/new_conversation_address")?;
    let coordinator = unique("com.simplemobiletools.smsmessenger:id/new_conversation_coordinator")?;
    let mut roots = nodes.iter().enumerate().filter(|(_, n)| n.parent.is_none() && n.package.as_deref() == Some(PKG));
    let (root, _) = roots.next()?;
    if roots.next().is_some() { return None; }

    let c = &nodes[control];
    let a = &nodes[address];
    let p = &nodes[coordinator];
    let r = &nodes[root];
    // Direct raw ancestry excludes controls copied into collection rows, including collections
    // that would otherwise disappear during pruning. Only the observed flattened form is known.
    if !(root < coordinator && coordinator < control && coordinator < address)
        || p.parent != Some(r.id) || c.parent != Some(p.id) || a.parent != Some(p.id)
        || nodes.iter().any(|n| n.parent == Some(r.id) && n.id != p.id)
        || nodes.iter().any(|n| n.parent == Some(c.id) || n.parent == Some(a.id)) {
        return None;
    }
    let shape = |n: &Node, role, class: &str| {
        n.package.as_deref() == Some(PKG) && n.role == role
            && n.class_name.as_deref() == Some(class)
            && n.flags.visible && n.flags.enabled && n.bounds.area() > 0
            && super::on_screen(&n.bounds, screen.width, screen.height)
            && !n.flags.checkable && !n.flags.checked && !n.flags.scrollable && !n.flags.selected
    };
    let container = |n: &Node| {
        !n.flags.clickable && !n.flags.long_clickable && !n.flags.editable
            && n.label.is_none() && n.text.is_none()
    };
    if !shape(r, Role::Container, "android.widget.FrameLayout") || !container(r) || r.resource_id.is_some()
        || !shape(p, Role::Container, "android.view.ViewGroup") || !container(p)
        || !shape(a, Role::Edit, "android.widget.EditText") || !a.flags.clickable || !a.flags.editable
        || !shape(c, Role::Image, "android.widget.ImageView") || !c.flags.clickable
        || c.flags.long_clickable || c.flags.editable {
        return None;
    }
    Some(control)
}
