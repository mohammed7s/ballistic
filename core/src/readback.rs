//! Readback (core/README.md §10): what changed between the screen an input was planned on and a
//! fresh observation after it, decided in code from the two trees.
//!
//! The result is one of three outcomes plus signals from a fixed vocabulary. Neither carries a
//! string from the screen, so a readback adds nothing an app wrote to any prompt. It never
//! dispatches anything and it never authorizes anything: the idempotency guard in the agent
//! loop counts acknowledged guarded inputs whatever the readback says.

use serde::Serialize;

use crate::types::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadbackOutcome {
    Changed,
    Unchanged,
    /// No fresh observation (the Screen port failed or returned no nodes).
    Unreadable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Readback {
    pub outcome: ReadbackOutcome,
    /// Fixed words only: app_changed, activity_changed, field_emptied, target_gone,
    /// elements_added, elements_removed, content_changed.
    pub signals: Vec<&'static str>,
}

impl Readback {
    pub fn unreadable() -> Readback {
        Readback { outcome: ReadbackOutcome::Unreadable, signals: vec![] }
    }

    /// History form for the planner: `readback: changed (field_emptied, elements_removed)`.
    pub fn describe(&self) -> String {
        let outcome = match self.outcome {
            ReadbackOutcome::Changed => "changed",
            ReadbackOutcome::Unchanged => "unchanged",
            ReadbackOutcome::Unreadable => "unreadable",
        };
        if self.signals.is_empty() { outcome.into() } else { format!("{outcome} ({})", self.signals.join(", ")) }
    }
}

/// Status-bar chrome changes on its own (clock, signal); it is not evidence about an input.
fn relevant<'a>(screen: &'a ScreenState, packages: &'a [&'a str]) -> impl Iterator<Item = &'a Node> + 'a {
    screen.nodes.iter().filter(move |n| {
        let p = n.package.as_deref().unwrap_or(&screen.package);
        packages.contains(&p) && n.flags.visible && n.bounds.area() > 0
    })
}

type Shape<'a> = (Option<&'a str>, Option<&'a str>, Option<&'a str>, &'static str);

fn shape(n: &Node) -> Shape<'_> {
    (n.package.as_deref(), n.class_name.as_deref(), n.resource_id.as_deref(), crate::screen_filter::role_name(n.role))
}

fn same_place(a: &Node, b: &Node) -> bool {
    shape(a) == shape(b) && a.bounds == b.bounds
}

/// An edit field showing more than its own hint.
fn filled(n: &Node) -> bool {
    n.flags.editable && n.text.as_deref().map(str::trim).is_some_and(|t| !t.is_empty() && Some(t) != n.label.as_deref().map(str::trim))
}

/// Compare the screen before an input with the one after it. `target` is the node the input
/// went to on `before`.
pub fn compare(before: &ScreenState, after: &ScreenState, target: Option<NodeId>) -> Readback {
    if after.nodes.is_empty() {
        return Readback::unreadable();
    }
    let mut signals = Vec::new();
    if before.package != after.package {
        signals.push("app_changed");
    } else if before.activity != after.activity {
        signals.push("activity_changed");
    }
    let packages = [before.package.as_str(), after.package.as_str()];
    let old: Vec<&Node> = relevant(before, &packages).collect();
    let new: Vec<&Node> = relevant(after, &packages).collect();
    if old.iter().filter(|n| filled(n)).any(|o| new.iter().any(|n| same_place(o, n) && !filled(n))) {
        signals.push("field_emptied");
    }
    if let Some(t) = target.and_then(|t| before.nodes.iter().find(|n| n.id == t)) {
        if t.flags.visible && t.bounds.area() > 0 && !new.iter().any(|n| same_place(t, n)) {
            signals.push("target_gone");
        }
    }
    let mut old_shapes: Vec<Shape> = old.iter().map(|n| shape(n)).collect();
    let mut new_shapes: Vec<Shape> = new.iter().map(|n| shape(n)).collect();
    old_shapes.sort();
    new_shapes.sort();
    let (added, removed) = multiset_difference(&old_shapes, &new_shapes);
    if added { signals.push("elements_added"); }
    if removed { signals.push("elements_removed"); }
    let strings = |ns: &[&Node]| ns.iter().map(|n| (n.label.clone(), n.text.clone(), n.flags.checked)).collect::<Vec<_>>();
    if signals.is_empty() && strings(&old) != strings(&new) {
        signals.push("content_changed");
    }
    let outcome = if signals.is_empty() { ReadbackOutcome::Unchanged } else { ReadbackOutcome::Changed };
    Readback { outcome, signals }
}

/// (something in `new` not in `old`, something in `old` not in `new`), both sorted.
fn multiset_difference<T: Ord>(old: &[T], new: &[T]) -> (bool, bool) {
    let (mut i, mut j, mut added, mut removed) = (0, 0, false, false);
    while i < old.len() && j < new.len() {
        match old[i].cmp(&new[j]) {
            std::cmp::Ordering::Equal => { i += 1; j += 1; }
            std::cmp::Ordering::Less => { removed = true; i += 1; }
            std::cmp::Ordering::Greater => { added = true; j += 1; }
        }
    }
    (added || j < new.len(), removed || i < old.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multiset_difference_counts_duplicates() {
        assert_eq!(multiset_difference(&[1, 2, 2], &[1, 2, 2]), (false, false));
        assert_eq!(multiset_difference(&[1, 2], &[1, 2, 2]), (true, false));
        assert_eq!(multiset_difference(&[1, 2, 3], &[1, 2]), (false, true));
        assert_eq!(multiset_difference(&[1], &[2]), (true, true));
    }
}
