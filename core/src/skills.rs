//! Skills: record a verified run, replay it for the same request (core/README.md §11).
//!
//!   Recorder  follows a run; at a verified `done` it yields a [`Skill`] if every executed step
//!             can be found again by identity and every typed value is the owner's
//!   Replay    for an exact request match, supplies the next action instead of the planner;
//!             each step must find its screen (package, activity) and exactly one target with
//!             the same role, resource id and label, or the planner takes over from there
//!
//! A replayed action goes through the same checks as a planned one: validation, capability
//! grants, the taint rule, the judge, confirmation and dispatch. The last step of a replay is
//! a `done`, which the ordinary done check verifies. The host stores skills; core neither
//! reads nor writes files. Matching is exact request only: no templates or slots.

use serde::{Deserialize, Serialize};

use crate::screen_filter::FilteredScreen;
use crate::types::*;

/// What a control is, in a form that survives a new observation. Never an e-number or text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Target {
    pub role: Role,
    pub resource_id: Option<String>,
    pub label: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum SkillAction {
    OpenApp { package: String },
    Tap { target: Target },
    LongPress { target: Target },
    Type { target: Target, text: String, submit: bool },
    Scroll { target: Option<Target>, direction: Direction },
    Swipe { target: Target, direction: Direction },
    Back,
    Home,
    Wait,
}

/// One step: the screen it was taken on and what was done there.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Step {
    pub package: String,
    pub activity: String,
    pub action: SkillAction,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Skill {
    /// The owner's request, exactly; replay needs the same words (case and spacing aside).
    pub request: String,
    pub steps: Vec<Step>,
}

fn same_request(a: &str, b: &str) -> bool {
    let norm = |s: &str| s.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    norm(a) == norm(b)
}

/// A target core can find again: labelled or with a resource id, not chosen by the reader and
/// not identified only by an owner-matched title (that would be identity by content).
fn target(view: &FilteredScreen, id: u32) -> Option<Target> {
    let e = view.element(id)?;
    if view.reader_picks.contains(&id) || (e.label.is_none() && e.resource_id.is_none()) {
        return None;
    }
    if e.label.is_none() && !e.shown_content.is_empty() {
        return None;
    }
    Some(Target { role: e.role, resource_id: e.resource_id.clone(), label: e.label.clone() })
}

fn find(view: &FilteredScreen, t: &Target) -> Option<u32> {
    let mut hits = view.elements.iter()
        .filter(|e| e.role == t.role && e.resource_id == t.resource_id && e.label == t.label && !view.reader_picks.contains(&e.id));
    let first = hits.next()?;
    hits.next().is_none().then_some(first.id)
}

/// Follows one run. Anything that cannot be replayed safely spoils the recording.
#[derive(Debug, Default)]
pub struct Recorder {
    steps: Vec<Step>,
    spoiled: bool,
}

impl Recorder {
    /// A step that executed. `owner_values_only` is the taint rule's verdict on its typed text.
    pub fn executed(&mut self, action: &Action, op: &HandsOp, view: &FilteredScreen, screen: &ScreenState, owner_values_only: bool) {
        let t = |id: &u32| target(view, *id);
        let recorded = match action {
            Action::OpenApp { .. } => match op {
                HandsOp::OpenApp { package, .. } => Some(SkillAction::OpenApp { package: package.clone() }),
                _ => None,
            },
            Action::Tap { target } => t(target).map(|target| SkillAction::Tap { target }),
            Action::LongPress { target } => t(target).map(|target| SkillAction::LongPress { target }),
            Action::Type { target, text, submit } if owner_values_only =>
                t(target).map(|target| SkillAction::Type { target, text: text.clone(), submit: *submit }),
            Action::Scroll { target: None, direction, until: None } => Some(SkillAction::Scroll { target: None, direction: *direction }),
            Action::Scroll { target: Some(id), direction, until: None } => t(id).map(|target| SkillAction::Scroll { target: Some(target), direction: *direction }),
            Action::Swipe { target, direction } => t(target).map(|target| SkillAction::Swipe { target, direction: *direction }),
            Action::Back => Some(SkillAction::Back),
            Action::Home => Some(SkillAction::Home),
            Action::Wait => Some(SkillAction::Wait),
            _ => None,
        };
        match recorded {
            Some(action) => self.steps.push(Step { package: screen.package.clone(), activity: screen.activity.clone(), action }),
            None => self.spoiled = true,
        }
    }

    /// The run depended on content (a reader pick or facts) or carried values: no skill.
    pub fn spoil(&mut self) {
        self.spoiled = true;
    }

    /// The skill for a verified run, if every step was recordable.
    pub fn finish(self, request: &str) -> Option<Skill> {
        (!self.spoiled && !self.steps.is_empty()).then(|| Skill { request: request.to_string(), steps: self.steps })
    }
}

/// What a replay offers the loop instead of a planner call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Next {
    Act(Action),
    /// All steps ran; claim done so the ordinary done check verifies the result.
    Done,
    /// Step `n` (1-based) did not find its screen or a unique target; the planner takes over.
    Mismatch(usize),
}

#[derive(Debug)]
pub struct Replay {
    skill: Skill,
    next: usize,
}

impl Replay {
    /// The one skill recorded for exactly this request, if there is exactly one.
    pub fn find(skills: &[Skill], request: &str) -> Option<Replay> {
        let mut hits = skills.iter().filter(|s| same_request(&s.request, request));
        let skill = hits.next()?.clone();
        hits.next().is_none().then_some(Replay { skill, next: 0 })
    }

    pub fn len(&self) -> usize {
        self.skill.steps.len()
    }

    pub fn is_empty(&self) -> bool {
        self.skill.steps.is_empty()
    }

    /// The next action on this screen. Re-checks the screen and re-finds the target each time.
    pub fn next(&mut self, view: &FilteredScreen, screen: &ScreenState) -> Next {
        let Some(step) = self.skill.steps.get(self.next) else { return Next::Done };
        let n = self.next + 1;
        let on_screen = step.package == screen.package && step.activity == screen.activity;
        let found = |t: &Target| find(view, t);
        let action = match &step.action {
            // A launch is valid from any screen; it names its package.
            SkillAction::OpenApp { package } => Some(Action::OpenApp { app: package.clone() }),
            _ if !on_screen => None,
            SkillAction::Tap { target } => found(target).map(|target| Action::Tap { target }),
            SkillAction::LongPress { target } => found(target).map(|target| Action::LongPress { target }),
            SkillAction::Type { target, text, submit } => found(target).map(|target| Action::Type { target, text: text.clone(), submit: *submit }),
            SkillAction::Scroll { target: None, direction } => Some(Action::Scroll { target: None, direction: *direction, until: None }),
            SkillAction::Scroll { target: Some(t), direction } => found(t).map(|target| Action::Scroll { target: Some(target), direction: *direction, until: None }),
            SkillAction::Swipe { target, direction } => found(target).map(|target| Action::Swipe { target, direction: *direction }),
            SkillAction::Back => Some(Action::Back),
            SkillAction::Home => Some(Action::Home),
            SkillAction::Wait => Some(Action::Wait),
        };
        match action {
            Some(a) => { self.next += 1; Next::Act(a) }
            None => Next::Mismatch(n),
        }
    }
}
