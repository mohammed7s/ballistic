//! Shipped contracts for the existing Hands adapter. No app-supplied instructions execute.
use crate::{ports::*, types::*};

pub const CONTRACTS: &str = include_str!("../schema/capabilities.json");

pub fn operation_id(op: &HandsOp) -> &'static str {
    match op {
        HandsOp::OpenApp { .. } => "open_app", HandsOp::Tap { .. } => "tap",
        HandsOp::LongPress { .. } => "long_press", HandsOp::Type { .. } => "type",
        HandsOp::Scroll { .. } => "scroll", HandsOp::Swipe { .. } => "swipe",
        HandsOp::Back => "back", HandsOp::Home => "home", HandsOp::Wait { .. } => "wait",
    }
}
pub fn app_scope<'a>(op: &'a HandsOp, foreground: &'a str) -> &'a str {
    match op { HandsOp::OpenApp { package, .. } => package, _ => foreground }
}
pub fn permitted(op: &HandsOp, app: &str, host: Option<&HostCapabilities>) -> bool {
    host.is_some_and(|h| h.operations.iter().any(|id| id == operation_id(op))
        && h.app_grants.iter().any(|grant| grant == app))
}

/// Compact planner-facing subset. Coordinate arguments remain private to the Hands contract.
pub fn planner_instructions(host: Option<&HostCapabilities>) -> String {
    let metadata: serde_json::Value=serde_json::from_str(CONTRACTS).expect("shipped capability metadata");
    let available: Vec<&str>=metadata["operations"].as_array().unwrap().iter()
        .filter_map(|op| op["id"].as_str())
        .filter(|id| host.is_some_and(|h| h.operations.iter().any(|op|op==id)))
        .collect();
    format!("Available host operations (app grants still apply): {}. Use the planner action schema above. opened_ui only confirms navigation, not the requested task postcondition. Unknown execution outcomes stop the run; never blindly retry a possibly submitted effect.", available.join(", "))
}

/// Private fields prevent callers manufacturing approval. Consumed on dispatch, and compared
/// against the full typed operation and app, not a model's description or operation name.
pub(crate) struct Authorization { operation: HandsOp, app: String }
pub(crate) fn authorize<C: Confirm>(host: &mut C, request: &ConfirmRequest) -> PortResult<Option<Authorization>> {
    Ok((host.ask(request)? == ConfirmResult::Approved).then(|| Authorization {
        operation: request.operation.clone(), app: request.app.clone(),
    }))
}
pub(crate) fn dispatch<H: Hands>(host: &mut H, op: &HandsOp, app: &str,
    grant: Option<&HostCapabilities>, confirmation_required: bool,
    authorization: Option<Authorization>) -> PortResult<HandsResult> {
    if !permitted(op, app, grant) { return Err(PortError::Host("capability denied".into())); }
    if confirmation_required && !authorization.is_some_and(|a| a.operation == *op && a.app == app) {
        return Err(PortError::Host("approval does not cover this operation".into()));
    }
    host.act(op)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Host { calls: usize }
    impl Confirm for Host { fn ask(&mut self, _: &ConfirmRequest) -> PortResult<ConfirmResult> { Ok(ConfirmResult::Approved) } }
    impl Hands for Host { fn act(&mut self, _: &HandsOp) -> PortResult<HandsResult> { self.calls+=1; Ok(HandsResult::Ok) } }
    #[test]
    fn changed_operation_or_app_invalidates_authorization() {
        for (op, app) in [(HandsOp::Tap{x:9,y:2},"notes"),(HandsOp::Tap{x:1,y:2},"messages")] {
            let mut host=Host{calls:0};
            let req=ConfirmRequest{operation:HandsOp::Tap{x:1,y:2},app:"notes".into(),verb:"tap".into(),target:"delete".into(),values:vec![],hard:true,reason:"delete".into()};
            let approval=authorize(&mut host,&req).unwrap();
            let grants=HostCapabilities{operations:vec!["tap".into()],app_grants:vec!["notes".into(),"messages".into()]};
            assert!(dispatch(&mut host,&op,app,Some(&grants),true,approval).is_err());
            assert_eq!(host.calls,0);
        }
    }
    #[test]
    fn shipped_contracts_match_supported_operations() {
        let metadata:serde_json::Value=serde_json::from_str(CONTRACTS).unwrap();
        let ops=[HandsOp::OpenApp{package:"p".into(),activity:None},HandsOp::Tap{x:0,y:0},HandsOp::LongPress{x:0,y:0},HandsOp::Type{x:0,y:0,text:"".into(),submit:false},HandsOp::Scroll{area:Bounds::default(),direction:Direction::Down},HandsOp::Swipe{area:Bounds::default(),direction:Direction::Down},HandsOp::Back,HandsOp::Home,HandsOp::Wait{millis:1}];
        assert_eq!(metadata["operations"].as_array().unwrap().len(),ops.len());
        for op in ops { assert!(metadata["operations"].as_array().unwrap().iter().any(|v|v["id"]==operation_id(&op))); }
    }
}
