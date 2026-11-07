//! Initial tree-only loop with the reader's `element` shape and guarded-input readback.
//! Facts/summary reader shapes, provenance policy and skills remain unfinished.
//! The harness Bridge and the UniFFI host share this cancellable loop.
use std::io::{BufRead, Write};
use std::time::Instant;
use serde_json::{json, Value};
use crate::{capabilities, verification, launch_completion, actions, bridge::Bridge, ports::*, run::Host,
    readback::{self, Readback}, reader::{self, ElementAnswer, ReaderPick},
    screen_filter::{self, FilterContext, FilteredScreen}, types::*};

const RULES: &str = include_str!("../prompts/planner.txt");
fn message(role: &str, content: String) -> ChatMessage { ChatMessage {role:role.into(),content} }


/// Acknowledged guarded inputs on one control before the run stops (core/README.md §9).
const GUARDED_REPEAT_LIMIT: u32 = 2;

/// One guarded input as the idempotency guard sees it: same app, same control, same text.
/// The control is its resource id, else its label, else its role and bounds; never content.
#[derive(Debug, Clone, PartialEq, Eq)]
struct GuardKey { package: String, control: String, text: Option<String> }

fn guard_key(action: &Action, view: &FilteredScreen) -> Option<GuardKey> {
    let (target, text) = match action {
        Action::Tap{target}|Action::LongPress{target} => (*target, None),
        Action::Type{target,text,..} => (*target, Some(text.clone())),
        _ => return None,
    };
    let e = view.element(target)?;
    let b = e.bounds;
    let control = e.resource_id.clone().or_else(|| e.label.clone())
        .unwrap_or_else(|| format!("{} {},{},{},{}", screen_filter::role_name(e.role), b.x1, b.y1, b.x2, b.y2));
    Some(GuardKey { package: e.package.clone(), control, text })
}

fn target_node(action: &Action, view: &FilteredScreen) -> Option<NodeId> {
    match action {
        Action::Tap{target}|Action::LongPress{target}|Action::Type{target,..}|Action::Swipe{target,..} => view.element(*target).map(|e| e.node),
        Action::Scroll{target:Some(t),..} => view.element(*t).map(|e| e.node),
        _ => None,
    }
}


pub fn run<R: BufRead,W:Write>(host: &mut Bridge<R,W>, request:&str, max_steps:u32) -> PortResult<Value> {
    drive(host, request, max_steps)
}

/// What a run returns when the owner pressed Stop: nothing further was asked of a model or
/// dispatched. Effects already executed stay reported in `execution_status`.
fn stopped(steps: u32, task_status: &str, execution_status: ExecutionStatus, verification: &Verification) -> Value {
    json!({"done":false,"reason":"stopped by owner","steps":steps,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":null,"stopped":true})
}

pub(crate) fn drive<H: Host>(host: &mut H, request:&str, max_steps:u32) -> PortResult<Value> {
    let mut typed = Vec::new();
    let mut history = Vec::<String>::new();
    let mut apps = Vec::new();
    let mut verification = Verification::default();
    let mut execution_status = ExecutionStatus::NotExecuted;
    let mut task_status = "pending";

    // Checked before every observation, model call and confirmation, and immediately before
    // every dispatch. A stop never interrupts a call already made; it prevents the next one.
    macro_rules! stop_if_cancelled { ($steps:expr) => {
        if host.cancelled() { return Ok(stopped($steps, task_status, execution_status, &verification)); }
    } }

    // Acknowledged guarded inputs in this run, counted whatever their readback said.
    let mut acknowledged: Vec<(GuardKey, u32)> = Vec::new();
    let mut picks: Vec<ReaderPick> = Vec::new();
    // Bound reads on an unchanged snapshot; keys remain private to core.
    let mut read_queries = Vec::<String>::new();
    // A readback observation is the next step's screen; an executed input's history line waits
    // for that screen so core can say whether it changed.
    let mut next_screen: Option<ScreenState> = None;
    let mut pending_line: Option<(String, ScreenState, Option<NodeId>)> = None;
    let mut foreground_high_water = 0;

    for step in 1..=max_steps {
        stop_if_cancelled!(step-1);
        let start=Instant::now();
        let screen=match next_screen.take() { Some(s)=>s, None=>host.observe()? };
        if let Some(f)=&screen.foreground { foreground_high_water=foreground_high_water.max(f.sequence); }
        if let Some((line,before,target))=pending_line.take() {
            let change=readback::compare(&before,&screen,target);
            history.push(format!("{line}; screen {}",change.describe()));
            if history.len()>8 {history.remove(0);}
        }
        if !screen.installed_apps.is_empty() { apps=screen.installed_apps.clone(); }
        let mut view=screen_filter::filter(&screen,&FilterContext{request,typed:&typed});
        let mut facts=Vec::new();
        for p in &picks {
            if let Some(id)=p.relocate(&view) {
                view.reader_picks.push(id);
                facts.push(format!("reader: element e{id} (source {})",reader::source_tag(&p.source)));
            }
        }
        let app_names:Vec<_>=apps.iter().map(|a|a.label.as_str()).collect();
        let facts_block=if facts.is_empty() {String::new()} else {format!("Reader facts:\n{}\n",facts.join("\n"))};
        let state=format!("Owner request: {request}\nApps: {}\nHistory: {}\n{facts_block}Screen {} / {}:\n{}",json!(app_names),history.join("\n"),screen.package,screen.activity,screen_filter::render(&view));
        let mut messages=vec![message("system",format!("{RULES}\nAction schema: {}",actions::ACTIONS_SCHEMA)),message("user",state)];
        messages[0].content.push_str(&format!("\n{}", capabilities::planner_instructions(screen.capabilities.as_ref())));
        let mut parsed=None;
        for attempt in 0..2 {
            stop_if_cancelled!(step-1);
            let completion=host.complete(ModelRole::Planner,&messages,800)?;
            match actions::parse_reply(&completion.text).and_then(|r|{ actions::resolve(&r.action,&view,&screen,&apps)?;Ok(r) }) {
                Ok(reply)=> { parsed=Some(reply);break; },
                Err(e)=> {
                    if attempt==1 {
                        let category=match &e {
                            actions::ActionError::NoSuchElement(_)|actions::ActionError::UnknownApp(_)|actions::ActionError::Disabled(_)|actions::ActionError::NotActionable(_)|actions::ActionError::Unidentified(_)|actions::ActionError::NotEditable(_)|actions::ActionError::NotScrollable(_)=>"target_resolution",
                            _=>"planning",
                        };
                        return Ok(json!({"done":false,"reason":format!("invalid action: {e}"),"steps":step,"execution_status":execution_status,"task_status":"failed","verification":verification,"failure_category":category}));
                    }
                    messages.push(message("assistant",completion.text));
                    messages.push(message("user",format!("Invalid action: {e}. Return a corrected JSON action.")));
                }
            }
        }
        let reply=parsed.expect("validated reply");
        let action=reply.action;
        if !matches!(&action, Action::Read{..}) { read_queries.clear(); }
        let mut outcome="ok".to_string();
        let mut done=false;
        let mut terminal=false;
        let mut failure_category: Option<&str> = None;
        let mut step_execution_status = ExecutionStatus::NotExecuted;
        let mut readback_event: Option<Readback> = None;
        let mut reader_event: Option<Value> = None;
        match &action {
            Action::Done{..}=> {
                stop_if_cancelled!(step-1);
                let fresh=host.observe()?;
                stop_if_cancelled!(step-1);
                let launch_goal=launch_completion::recognize(request,&apps);
                let assessment=if let Some(goal)=launch_goal.as_ref() {
                    // One fresh readback, then stop even if unavailable. Neither repeated
                    // guesses nor a model verdict can substitute for this typed postcondition.
                    launch_completion::assess(goal,&screen,&fresh,foreground_high_water,request,&apps)
                } else {
                    let filtered=screen_filter::filter(&fresh,&FilterContext{request,typed:&typed});
                    stop_if_cancelled!(step-1);
                    let check=host.complete(ModelRole::Verifier,&[
                        message("system",r#"Assess completion from the current screen only. Return JSON {"status":"verified|failed|unknown","nodes":["e1"],"evidence_basis":"visible_ui","reason":"..."}. Check every requested field, value and constraint against current visible evidence, including exact names, dates and units. Verified requires all requested postconditions; failed requires a visible contradiction; otherwise use unknown. Cite distinct current eN ids with readable evidence. Hidden cN content, resource ids, an opened app, an acknowledged input or text merely typed in an editor do not prove a saved or sent result. A label identifies a control, not its hidden value. Do not infer missing values. This is a model assessment, never authoritative readback."#.into()),
                        message("user",format!("Request: {request}\nScreen {}:\n{}",fresh.package,screen_filter::render(&filtered)))],400)?;
                    stop_if_cancelled!(step-1);
                    verification::assess_with_feedback(&check.text,&filtered)
                };
                stop_if_cancelled!(step-1);
                verification=assessment.verification;
                done=verification.status == VerificationStatus::Verified;
                terminal=done || launch_goal.is_some();
                if done {task_status="completed";}
                else if launch_goal.is_some() {task_status="pending";}
                outcome=assessment.feedback.into();
                if !done {
                    failure_category=Some("verification");
                    // The next plan should inspect the same fresh evidence that was checked.
                    next_screen=Some(fresh);
                }
            },
            Action::GiveUp{..}=> {outcome="planner gave up".into();task_status="failed";failure_category=Some("planning");terminal=true;},
            Action::Read{targets,question,shape:ReadShape::Element}=> {
                // One extra model call, only when the planner asks. The reader sees the withheld
                // text; the planner gets an element id and a source tag, or nothing.
                let allowed=reader::candidates(&view,targets);
                let normalized_question=question.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
                let mut ordered_targets=targets.clone();
                ordered_targets.sort_unstable();
                ordered_targets.dedup();
                let reader_messages=reader::element_messages(&normalized_question,&view,&ordered_targets);
                let query=serde_json::to_string(&reader_messages).expect("serializable reader messages");
                if read_queries.contains(&query) || read_queries.len()>=3 {
                    // No new observation occurred between these reads. Another model call is
                    // not new evidence. Do not turn a no-progress stop into completion.
                    outcome="reader made no progress on unchanged screen; stopped".into();
                    failure_category=Some("target_resolution");
                    task_status="pending";
                    terminal=true;
                } else {
                read_queries.push(query);
                stop_if_cancelled!(step-1);
                let answer=host.complete(ModelRole::Reader,&reader_messages,64)?;
                picks.clear();
                outcome=match reader::parse_element(&answer.text,&allowed) {
                    ElementAnswer::Element(id)=> match ReaderPick::new(&view,id) {
                        Some(p)=> { let fact=format!("reader: element e{id} (source {})",reader::source_tag(&p.source)); picks.push(p); fact },
                        None=> "reader: element none".into(),
                    },
                    ElementAnswer::NoElement=> "reader: element none".into(),
                    ElementAnswer::Rejected=> {
                        reader_event=Some(json!({"rejected":true,"reply_chars":answer.text.chars().count()}));
                        "reader: answer rejected (not one candidate id)".into()
                    },
                };
                next_screen=Some(screen.clone());
                }
            },
            Action::Read{..}=> {outcome="only the reader's element shape is available".into();next_screen=Some(screen.clone());},
            Action::Answer{..}=> {outcome="answer provenance is not implemented".into();task_status="unsupported";failure_category=Some("planning");terminal=true;},
            _=> {
                let op=actions::resolve(&action,&view,&screen,&apps).map_err(|e|PortError::Malformed(e.to_string()))?.ok_or_else(||PortError::Malformed("no hands operation".into()))?;
                let app=capabilities::app_scope(&op,&screen.package);
                let mut reason=actions::confirmation_reason(&action,&view);
                let confirmation_required=reason.is_some();
                let key=reason.as_ref().and_then(|_|guard_key(&action,&view));
                let prior=key.as_ref().and_then(|k|acknowledged.iter().find(|(a,_)|a==k)).map_or(0,|(_,n)|*n);
                let mut authorization=None;
                if !capabilities::permitted(&op,app,screen.capabilities.as_ref()) {
                    outcome="host capability or app grant denied".into();
                    failure_category=Some("permission"); task_status="failed"; terminal=true;
                }
                if !terminal && prior>=GUARDED_REPEAT_LIMIT {
                    outcome="guarded input already acknowledged twice; not dispatched".into();
                    failure_category=Some("verification"); task_status="pending"; terminal=true;
                }
                if !terminal {
                    if let Some(r)=reason.as_mut() {
                        // Idempotency guard: the owner sees that this exact input may already
                        // have taken effect before approving it again.
                        if prior>0 { r.push_str("; the same input was already acknowledged in this run and may already have been sent"); }
                        else if !acknowledged.is_empty() { r.push_str("; another guarded input was acknowledged earlier in this run"); }
                    }
                    if let Some(reason)=reason {
                        stop_if_cancelled!(step-1);
                        let confirm=ConfirmRequest{operation:op.clone(),app:app.into(),verb:actions::describe(&action),target:serde_json::to_string(&op).unwrap(),values:vec![],hard:true,reason};
                        authorization=capabilities::authorize(host,&confirm)?;
                        if authorization.is_none() { outcome="owner denied confirmation".into();failure_category=Some("policy_denial");task_status="failed";terminal=true; }
                    }
                }
                if !terminal {
                    stop_if_cancelled!(step-1);
                    match capabilities::dispatch(host,&op,app,screen.capabilities.as_ref(),confirmation_required,authorization) {
                        Ok(HandsResult::Ok)=> {
                            execution_status=ExecutionStatus::Executed;
                            step_execution_status=ExecutionStatus::Executed;
                            task_status=if matches!(op,HandsOp::OpenApp{..}) {"opened_ui"} else {"pending"};
                            verification=Verification::default();
                            outcome=if matches!(op,HandsOp::OpenApp{..}) {"opened_ui"} else {"host acknowledged input"}.into();
                            if let Action::Type{text,..}=&action {typed.push(text.clone());}
                            if confirmation_required {
                                // An input acknowledgment is not an effect receipt. Read the screen
                                // back (no dispatch, no content in any prompt), count the input
                                // whatever the readback says, and let the planner continue.
                                let count=match key.as_ref().and_then(|k|acknowledged.iter_mut().find(|(a,_)|a==k)) {
                                    Some((_,n))=> {*n+=1;*n},
                                    None=> { if let Some(k)=key.clone() {acknowledged.push((k,1));} 1 },
                                };
                                stop_if_cancelled!(step);
                                let back=match host.observe() {
                                    Ok(fresh)=> { let b=readback::compare(&screen,&fresh,target_node(&action,&view)); next_screen=Some(fresh); b },
                                    Err(_)=> Readback::unreadable(),
                                };
                                outcome=format!("guarded input acknowledged; readback: {}",back.describe());
                                readback_event=Some(back);
                                if count>=GUARDED_REPEAT_LIMIT {
                                    outcome.push_str("; same guarded input acknowledged twice, stopped");
                                    failure_category=Some("verification");terminal=true;
                                }
                            }
                        },
                        Ok(HandsResult::NotExecuted{..})=> {outcome="host did not execute action".into();failure_category=Some("execution");},
                        Ok(HandsResult::Error{..})|Err(_)=> {
                            execution_status=ExecutionStatus::Unknown;
                            step_execution_status=ExecutionStatus::Unknown;
                            task_status="pending";
                            verification=Verification::default();
                            outcome="execution outcome unknown; stopped to prevent duplicate effects".into();
                            failure_category=Some("execution");terminal=true;
                        },
                    }
                }

            }
        }
        host.log(&json!({"step":step,"action":actions::to_json(&action),"why":reply.why,"outcome":outcome,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":failure_category,"step_execution_status":step_execution_status,"readback":readback_event,"reader":reader_event,"capability_mode":if screen.capabilities.is_some(){"advertised"}else{"missing_denied"},"seconds":start.elapsed().as_secs_f64()}))?;
        let line=format!("{}: {}",actions::describe(&action),outcome);
        if step_execution_status==ExecutionStatus::Executed && readback_event.is_none() && !terminal {
            pending_line=Some((line,screen,target_node(&action,&view)));
        } else {
            history.push(line);
            if history.len()>8 {history.remove(0);}
        }
        if terminal {return Ok(json!({"done":done,"reason":outcome,"steps":step,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":failure_category}));}
    }
    Ok(json!({"done":false,"reason":"step budget exhausted","steps":max_steps,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":if verification.evidence_basis=="model_assessment" {"verification"} else {"planning"}}))
}
