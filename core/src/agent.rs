//! Initial tree-only loop with the reader's `element` shape and guarded-input readback.
//! Terminal owner excerpts are separate from the planner; facts/provenance/skills remain unfinished.
//! The harness Bridge and the UniFFI host share this cancellable loop.
use std::io::{BufRead, Write};
use std::time::Instant;
use serde_json::{json, Value};
use crate::{capabilities, context::{self, Room, Sent, Trim}, verification, launch_completion, actions, bridge::Bridge, ports::*, run::{Host, Options},
    readback::{self, Readback}, reader::{self, summary, ElementAnswer, ReaderPick}, judge::{self, Verdict}, values, skills,
    screen_filter::{self, FilterContext, FilteredScreen}, types::*};

const RULES: &str = include_str!("../prompts/planner.txt");
fn message(role: &str, content: String) -> ChatMessage { ChatMessage {role:role.into(),content} }

// Fixed code-owned correction: never include reader prose or the private serialized query.
const READ_NO_PROGRESS: &str = "Invalid action: this element read repeats an unchanged query or exceeds the consecutive-read limit. No reader call was made. Use current labels or a current reader pick for a legal action, or navigate or scroll an advertised scrollable area to obtain new evidence. Do not guess hidden controls. Return a corrected JSON action.";

fn element_request(view: &FilteredScreen, targets: &[u32], question: &str) -> (Vec<ChatMessage>, String) {
    let normalized_question=question.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    let mut ordered_targets=targets.to_vec();
    ordered_targets.sort_unstable();
    ordered_targets.dedup();
    let messages=reader::element_messages(&normalized_question,view,&ordered_targets);
    let query=serde_json::to_string(&messages).expect("serializable reader messages");
    (messages,query)
}

fn read_exhausted(queries: &[String], query: &str) -> bool {
    queries.iter().any(|prior| prior==query) || queries.len()>=3
}


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

/// [`run`] with explicit options (the judge), for the harness binary.
pub fn run_with<R: BufRead,W:Write>(host: &mut Bridge<R,W>, request:&str, max_steps:u32, options:&Options) -> PortResult<Value> {
    drive_task(host, &Task::owner(request), max_steps, options)
}

/// One request for the loop. `request` is treated as the owner's words; `tainted` lists values
/// in it that the owner did not say (a request resolved by the chat model can repeat a value
/// from an earlier task result), each with its source. The taint rule applies to them.
pub struct Task<'a> {
    pub request: &'a str,
    pub tainted: &'a [Tagged],
}

impl<'a> Task<'a> {
    pub fn owner(request: &'a str) -> Self {
        Task { request, tainted: &[] }
    }
}

/// What a run returns when the owner pressed Stop: nothing further was asked of a model or
/// dispatched. Effects already executed stay reported in `execution_status`.
fn stopped(steps: u32, task_status: &str, execution_status: ExecutionStatus, verification: &Verification) -> Value {
    json!({"done":false,"reason":"stopped by owner","steps":steps,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":null,"stopped":true})
}

pub(crate) fn drive<H: Host>(host: &mut H, request:&str, max_steps:u32) -> PortResult<Value> {
    drive_task(host, &Task::owner(request), max_steps, &Options::harness())
}

pub(crate) fn drive_task<H: Host>(host: &mut H, task:&Task, max_steps:u32, options:&Options) -> PortResult<Value> {
    let request=task.request;
    // Typed reader-fact values seen in this run, each with its source (reader facts, §7).
    let mut facts_values: Vec<Tagged> = Vec::new();
    let mut fact_sets: Vec<reader::facts::Facts> = Vec::new();
    // The step each fact set was recorded in, for the context guard's reduction (b).
    let mut fact_steps: Vec<u32> = Vec::new();
    // A facts read repeats only when its content or question changed.
    let mut facts_queries = Vec::<String>::new();
    // Skills (§11): replay an exact-request skill instead of planning; record a verified run.
    // A request carrying values the owner did not say neither replays nor records.
    let mut replay = skills::Replay::find(&options.skills, request).filter(|_| task.tainted.is_empty());
    let mut recorder = skills::Recorder::default();
    if !task.tainted.is_empty() { recorder.spoil(); }
    let mut replayed_steps = 0usize;
    let mut replay_stopped: Option<usize> = None;
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
    // Per step: what the context guard checked and the usage the host reported for every call.
    let mut guard_log: Vec<context::Check> = Vec::new();
    let mut step_usage = Usage::default();

    // Every model call goes through the context guard (context.rs). With no limit advertised
    // it is the plain call. A refusal ends the run: nothing was sent, the report says why.
    macro_rules! guarded { ($step:expr, $role:expr, $max:expr, $room:expr, $build:expr) => {
        match context::complete(host, $role, $max, $room, $build) {
            Ok((Sent::Completion(c), check)) => {
                step_usage.prompt_tokens = step_usage.prompt_tokens.saturating_add(c.usage.prompt_tokens);
                step_usage.completion_tokens = step_usage.completion_tokens.saturating_add(c.usage.completion_tokens);
                guard_log.extend(check);
                Ok(c)
            },
            Ok((Sent::Refused, check)) => {
                let check = check.expect("a refusal carries its check");
                let reason = format!("{}; the model was not asked", check.refusal());
                guard_log.push(check);
                return Ok(json!({"done":false,"reason":reason,"steps":$step,"task_status":"failed","execution_status":execution_status,"verification":verification,"failure_category":"model_context","context_guard":guard_log}));
            },
            Err(e) => Err(e),
        }
    } }

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
        let mut pick_lines=Vec::new();
        for p in &picks {
            if let Some(id)=p.relocate(&view) {
                view.reader_picks.push(id);
                pick_lines.push(format!("reader: element e{id} (source {})",reader::source_tag(&p.source)));
            }
        }
        // Each fact line, and whether it was recorded before the previous step (reduction (b)).
        let fact_lines:Vec<(bool,String)>=fact_sets.iter().enumerate()
            .map(|(i,f)|(fact_steps[i]+1<step,f.planner_line(i+1,request))).collect();
        let app_names:Vec<_>=apps.iter().map(|a|a.label.as_str()).collect();
        // A replayed step re-finds its screen and target; otherwise the planner takes over here.
        let mut replayed: Option<actions::PlannerReply> = None;
        if let Some(r)=replay.as_mut() {
            match r.next(&view,&screen) {
                skills::Next::Act(a) if actions::resolve(&a,&view,&screen,&apps).is_ok() => {
                    replayed_steps+=1;
                    replayed=Some(actions::PlannerReply{action:a,why:"skill replay".into()});
                },
                skills::Next::Done => {
                    replay=None;
                    replayed=Some(actions::PlannerReply{action:Action::Done{evidence:"skill replay finished".into()},why:"skill replay".into()});
                },
                _ => {
                    let n=replayed_steps+1;
                    replay=None; replay_stopped=Some(n);
                    history.push(format!("skill replay stopped at step {n}: screen or control differs; planning from here"));
                    if history.len()>8 {history.remove(0);}
                },
            }
        }
        let request_values=if task.tainted.is_empty() {String::new()} else {
            let named:Vec<String>=task.tainted.iter().map(|v|format!("{:?} (source {})",v.value,reader::source_tag(&v.source))).collect();
            format!("Request values not from the owner: {}\n",named.join(", "))
        };
        let system=format!("{RULES}\nAction schema: {}\n{}",actions::ACTIONS_SCHEMA,capabilities::planner_instructions(screen.capabilities.as_ref()));
        let (apps_line,rendered)=(json!(app_names).to_string(),screen_filter::render(&view));
        // The planner prompt for a context-guard trim: only history lines and old fact sets can
        // be left out; rules, schema, request, screen and this step's corrections never are.
        let planner_prompt=|t:&Trim| {
            let facts:Vec<&str>=pick_lines.iter().map(String::as_str)
                .chain(fact_lines.iter().filter(|(old,_)|!(t.old_facts && *old)).map(|(_,l)|l.as_str())).collect();
            let facts_block=if facts.is_empty() {String::new()} else {format!("Reader facts:\n{}\n",facts.join("\n"))};
            let kept=&history[t.history_lines.min(history.len())..];
            let state=format!("Owner request: {request}\n{request_values}Apps: {apps_line}\nHistory: {}\n{facts_block}Screen {} / {}:\n{rendered}",kept.join("\n"),screen.package,screen.activity);
            vec![message("system",system.clone()),message("user",state)]
        };
        let planner_room=Room{history_lines:history.len().saturating_sub(context::HISTORY_FLOOR),old_facts:fact_lines.iter().any(|(old,_)|*old),chat_turns:0};
        let mut corrections:Vec<ChatMessage>=Vec::new();
        let mut parsed=replayed;
        for attempt in 0..2 {
            if parsed.is_some() { break; }
            stop_if_cancelled!(step-1);
            let completion=guarded!(step,ModelRole::Planner,800,planner_room,|t:&Trim|{ let mut m=planner_prompt(t); m.extend(corrections.iter().cloned()); m })?;
            match actions::parse_reply(&completion.text).and_then(|r|{
                actions::resolve(&r.action,&view,&screen,&apps)?;
                if let Action::Answer{text}=&r.action { actions::answer_values(text,&view,request,task.tainted,&facts_values)?; }
                Ok(r)
            }) {
                Ok(reply)=> {
                    if attempt==0 {
                        if let Action::Read{targets,question,shape:ReadShape::Element}=&reply.action {
                            let (_,query)=element_request(&view,targets,question);
                            if read_exhausted(&read_queries,&query) {
                                // Consume only this step's existing correction slot. If it was
                                // already used, or correction repeats the read, the hard guard
                                // below still stops before any reader call or state mutation.
                                corrections.push(message("assistant",completion.text));
                                corrections.push(message("user",READ_NO_PROGRESS.into()));
                                continue;
                            }
                        }
                    }
                    parsed=Some(reply);break;
                },
                Err(e)=> {
                    if attempt==1 {
                        let category=match &e {
                            actions::ActionError::NoSuchElement(_)|actions::ActionError::UnknownApp(_)|actions::ActionError::Disabled(_)|actions::ActionError::NotActionable(_)|actions::ActionError::Unidentified(_)|actions::ActionError::ReaderTapOnly(_)|actions::ActionError::NotEditable(_)|actions::ActionError::NotScrollable(_)=>"target_resolution",
                            _=>"planning",
                        };
                        return Ok(json!({"done":false,"reason":format!("invalid action: {e}"),"steps":step,"execution_status":execution_status,"task_status":"failed","verification":verification,"failure_category":category}));
                    }
                    corrections.push(message("assistant",completion.text));
                    corrections.push(message("user",format!("Invalid action: {e}. Return a corrected JSON action.")));
                }
            }
        }
        let reply=parsed.expect("validated reply");
        let action=reply.action;
        if !matches!(&action, Action::Read{..}) { read_queries.clear(); }
        // A run that used content to choose or type cannot become a skill.
        if matches!(&action, Action::Read{..}) { recorder.spoil(); }
        let mut outcome="ok".to_string();
        let mut done=false;
        let mut terminal=false;
        let mut failure_category: Option<&str> = None;
        let mut step_execution_status = ExecutionStatus::NotExecuted;
        let mut readback_event: Option<Readback> = None;
        let mut reader_event: Option<Value> = None;
        // Only final report publication may consume this. Never history, events or prompts.
        let mut owner_output: Option<summary::Output> = None;
        let mut answer_out: Option<Value> = None;
        let mut judge_event: Option<&str> = None;
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
                    let rendered=screen_filter::render(&filtered);
                    let check=guarded!(step,ModelRole::Verifier,400,Room::default(),|_:&Trim| vec![
                        message("system",include_str!("../prompts/verifier.txt").into()),
                        message("user",format!("Request: {request}\nScreen {}:\n{rendered}",fresh.package))])?;
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
                // text; the planner gets an element id and a source tag, or fixed feedback.
                const NO_MATCH: &str = "reader: element none; no match among requested candidates only. This does not establish absence elsewhere. Inspect current labels; use relevant visible navigation or scroll an advertised scrollable area before reading new candidates. Do not repeat the same read or guess hidden controls.";
                let allowed=reader::candidates(&view,targets);
                let (reader_messages,query)=element_request(&view,targets,question);
                if read_exhausted(&read_queries,&query) {
                    // No new observation occurred between these reads. Another model call is
                    // not new evidence. Do not turn a no-progress stop into completion.
                    outcome="reader made no progress on unchanged screen; stopped".into();
                    failure_category=Some("target_resolution");
                    task_status="pending";
                    terminal=true;
                } else {
                read_queries.push(query);
                stop_if_cancelled!(step-1);
                let answer=guarded!(step,ModelRole::Reader,64,Room::default(),|_:&Trim| reader_messages.clone())?;
                stop_if_cancelled!(step-1);
                picks.clear();
                outcome=match reader::parse_element(&answer.text,&allowed) {
                    ElementAnswer::Element(id)=> match ReaderPick::new(&view,id) {
                        Some(p)=> { let fact=format!("reader: element e{id} (source {})",reader::source_tag(&p.source)); picks.push(p); fact },
                        None=> NO_MATCH.into(),
                    },
                    ElementAnswer::NoElement=> NO_MATCH.into(),
                    ElementAnswer::Rejected=> {
                        reader_event=Some(json!({"rejected":true,"reply_chars":answer.text.chars().count()}));
                        "reader: answer rejected (not one candidate id)".into()
                    },
                };
                next_screen=Some(screen.clone());
                }
            },
            Action::Read{targets,shape:ReadShape::Summary,..}=> {
                terminal=true;
                task_status="pending";
                verification=Verification::default();
                stop_if_cancelled!(step-1);
                let input=summary::requested(request).then(|| summary::Input::new(&view,targets,&screen)).flatten();
                let result=if let Some(input)=input {
                    // Use the owner's request, not a planner-authored instruction. One existing
                    // 64-token reader port call; no core retry/correction or planner continuation.
                    // The host retains its existing bounded transport retry policy.
                    stop_if_cancelled!(step-1);
                    let answer=guarded!(step,ModelRole::Reader,64,Room::default(),|_:&Trim| input.messages(request));
                    stop_if_cancelled!(step-1);
                    match answer {
                        Err(_)=>summary::Output::unavailable(summary::Status::Failed),
                        Ok(answer)=>match input.parse(&answer.text) {
                            None=>summary::Output::unavailable(summary::Status::Rejected),
                            Some(items)=> {
                                stop_if_cancelled!(step-1);
                                let fresh=host.observe();
                                stop_if_cancelled!(step-1);
                                match fresh {
                                    Ok(fresh) if input.fresh(&screen,&fresh)=>summary::Output::available(items),
                                    Ok(_)=>summary::Output::unavailable(summary::Status::Stale),
                                    Err(_)=>summary::Output::unavailable(summary::Status::Failed),
                                }
                            },
                        },
                    }
                } else { summary::Output::unavailable(summary::Status::Unsupported) };
                if result.is_available() {
                    verification.evidence_basis="model_selected_source_excerpt".into();
                    outcome="owner excerpts available; task completion is not established".into();
                } else {
                    failure_category=Some("reader_summary");
                    task_status=if matches!(result.status,summary::Status::Unsupported) {"unsupported"} else {"failed"};
                    outcome="owner summary unavailable; stopped".into();
                }
                // Status only in the step log. The source text and excerpts stay local until
                // after the last cancellation check and are never added to the action history.
                reader_event=Some(json!({"summary_status":result.status}));
                owner_output=Some(result);
            },
            Action::Read{targets,question,shape:ReadShape::Facts}=> {
                // One extra reader call, only when the planner asks. The planner then sees
                // typed fields with the content's source tag (reader/facts.rs), never prose.
                match reader::facts::Input::new(&view,targets,&screen) {
                    None=> { outcome="reader facts unavailable: use one to four current cN from one granted app, each at most 4096 characters; no reader call was made".into(); },
                    Some(input)=> {
                        let reader_messages=input.messages(question);
                        let query=serde_json::to_string(&reader_messages).expect("serializable reader messages");
                        if facts_queries.contains(&query) {
                            outcome="these reader facts are already listed; no reader call was made".into();
                        } else {
                            facts_queries.push(query);
                            stop_if_cancelled!(step-1);
                            let answer=guarded!(step,ModelRole::Reader,reader::facts::FACTS_TOKENS,Room::default(),|_:&Trim| reader_messages.clone())?;
                            stop_if_cancelled!(step-1);
                            outcome=match input.parse(&answer.text) {
                                Some(f)=> {
                                    let line=format!("reader facts f{} recorded (source {})",fact_sets.len()+1,reader::source_tag(&f.source));
                                    facts_values.extend(f.values()); fact_sets.push(f); fact_steps.push(step); line
                                },
                                None=> {
                                    reader_event=Some(json!({"rejected":true,"reply_chars":answer.text.chars().count()}));
                                    "reader: facts rejected (not the fixed shape)".into()
                                },
                            };
                        }
                    },
                }
                next_screen=Some(screen.clone());
            },
            Action::Answer{text}=> {
                // Every value was checked against its source when the reply was validated. The
                // answer goes to the owner with those sources; it is not verified.
                let sourced=actions::answer_values(text,&view,request,task.tainted,&facts_values).unwrap_or_default();
                answer_out=Some(json!({"text":text,"display":values::annotate(text,&sourced),"values":sourced}));
                outcome="answered; each value was checked against its source, the answer itself is not verified".into();
                task_status="pending";
                verification=Verification{evidence_basis:"answer_values_sourced".into(),..Verification::default()};
                terminal=true;
            },
            _=> {
                let op=actions::resolve(&action,&view,&screen,&apps).map_err(|e|PortError::Malformed(e.to_string()))?.ok_or_else(||PortError::Malformed("no hands operation".into()))?;
                let app=capabilities::app_scope(&op,&screen.package);
                let mut reason=actions::confirmation_reason(&action,&view);
                // Taint rule: a value the owner did not give needs a hard confirmation naming
                // the value and its source, whatever the target.
                let taint=actions::tainted_values(&action,&view,request,task.tainted,&facts_values);
                if !taint.is_empty() {
                    let named=actions::taint_reason(&taint);
                    reason=Some(match reason { Some(r)=>format!("{r}; {named}"), None=>named });
                }
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
                if !terminal && options.judge && judge::state_changing(&action) {
                    // Deny-only (§8): sees the request, the action in plain words, value sources
                    // and core's history lines, never content. Allow changes nothing below.
                    let sourced:Vec<Tagged>=match &action {
                        Action::Type{text,..}=> {
                            let labels:Vec<&str>=view.elements.iter().filter_map(|e|e.label.as_deref()).collect();
                            values::Provenance::new(&[request],&labels,&facts_values)
                                .tag_with(text,task.tainted,&Source::Model("planner".into())).into_iter()
                                .map(|(f,source)|Tagged{value:f.raw,source}).collect()
                        },
                        _=>vec![],
                    };
                    let words=judge::plain_words(&action,&view,&apps,&screen.package);
                    stop_if_cancelled!(step-1);
                    let reply=guarded!(step,ModelRole::Judge,judge::JUDGE_TOKENS,Room::default(),|_:&Trim| judge::messages(request,&words,&sourced,&history))?;
                    stop_if_cancelled!(step-1);
                    judge_event=Some("allow");
                    if let Verdict::Block(why)=judge::parse(&reply.text) {
                        judge_event=Some("block");
                        outcome=format!("blocked by the safety check: {why}");
                        failure_category=Some("policy_denial"); task_status="failed"; terminal=true;
                    }
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
                        let confirm=ConfirmRequest{operation:op.clone(),app:app.into(),verb:actions::describe(&action),target:serde_json::to_string(&op).unwrap(),values:taint.clone(),hard:true,reason};
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
                            recorder.executed(&action,&op,&view,&screen,taint.is_empty());
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
        stop_if_cancelled!(step-1);
        let mut event=json!({"step":step,"action":actions::to_json(&action),"why":reply.why,"outcome":outcome,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":failure_category,"step_execution_status":step_execution_status,"readback":readback_event,"reader":reader_event,"judge":judge_event,"capability_mode":if screen.capabilities.is_some(){"advertised"}else{"missing_denied"},"seconds":start.elapsed().as_secs_f64()});
        // Usage the host reported for this step's model calls (zero means not reported).
        let (usage,checks)=(std::mem::take(&mut step_usage),std::mem::take(&mut guard_log));
        if usage.prompt_tokens>0 { event["prompt_tokens"]=json!(usage.prompt_tokens); }
        if usage.completion_tokens>0 { event["completion_tokens"]=json!(usage.completion_tokens); }
        if !checks.is_empty() { event["context_guard"]=json!(checks); }
        host.log(&event)?;
        if let Some(output)=owner_output {
            let reason=if output.is_available() {output.display()} else {outcome};
            let report=json!({"done":false,"reason":reason,"steps":step,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":failure_category,"owner_output":output});
            stop_if_cancelled!(step);
            return Ok(report);
        }
        let line=format!("{}: {}",actions::describe(&action),outcome);
        if step_execution_status==ExecutionStatus::Executed && readback_event.is_none() && !terminal {
            pending_line=Some((line,screen,target_node(&action,&view)));
        } else {
            history.push(line);
            if history.len()>8 {history.remove(0);}
        }
        if terminal {
            let mut report=json!({"done":done,"reason":outcome,"steps":step,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":failure_category});
            if let Some(answer)=answer_out {
                // Display for the owner: the answer with its value sources, and the facts it
                // drew on. Hosts must not feed either back into a model prompt.
                report["reason"]=answer["display"].clone();
                report["answer"]=answer;
                report["owner_facts"]=json!(fact_sets);
            }
            if judge_event==Some("block") { report["blocked_by"]=json!("judge"); }
            if done {
                if let Some(skill)=std::mem::take(&mut recorder).finish(request) { report["skill"]=json!(skill); }
            }
            if replayed_steps>0 || replay_stopped.is_some() {
                report["skill_replay"]=json!({"steps":replayed_steps,"stopped_at":replay_stopped});
            }
            return Ok(report);
        }
    }
    Ok(json!({"done":false,"reason":"step budget exhausted","steps":max_steps,"task_status":task_status,"execution_status":execution_status,"verification":verification,"failure_category":if verification.evidence_basis=="model_assessment" {"verification"} else {"planning"}}))
}
