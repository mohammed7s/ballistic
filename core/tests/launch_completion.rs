//! Development regressions, scripted ports only; not held-out task evaluation.
use ballistic_core::{agent, bridge::Bridge, launch_completion::{self, LaunchGoal}, types::*, verification, screen_filter::{self, FilterContext}};
use serde_json::{json, Value};
use std::io::Cursor;

fn apps() -> Vec<AppEntry> {
    serde_json::from_value(json!([
        {"label":"Orchard Notes","package":"org.example.orchard"},
        {"label":"Compass","package":"org.example.compass"},
        {"label":"Daily Ledger","package":"org.example.ledger"}
    ])).unwrap()
}
fn screen(package: &str, sequence: u64) -> ScreenState {
    serde_json::from_value(json!({"package":package,"activity":"Main","width":400,"height":800,
        "nodes":[{"id":1,"role":"text","text":"unreadable arbitrary app content",
            "bounds":{"x1":0,"y1":0,"x2":100,"y2":100},"flags":{"visible":true}}],
        "installed_apps":apps(),
        "foreground":{"package":package,"activity":"Main","sequence":sequence},
        "capabilities":{"operations":["open_app"],"app_grants":apps().iter().map(|a| &a.package).collect::<Vec<_>>(),
            "observations":["foreground_component_v1"]}})).unwrap()
}
fn completion(action: Value) -> Value { json!({"text": action.to_string()}) }
fn done() -> Value { completion(json!({"action":"done","evidence":"arbitrary model claim"})) }
fn run(request: &str, replies: Vec<Value>, budget: u32) -> (Value, Vec<Value>) {
    let input=replies.into_iter().enumerate().map(|(i,data)|json!({"id":i+1,"ok":true,"data":data}).to_string()+"\n").collect::<String>();
    let mut output=Vec::new();
    let report=agent::run(&mut Bridge::new(Cursor::new(input),&mut output),request,budget).unwrap();
    let calls=String::from_utf8(output).unwrap().lines().map(|s|serde_json::from_str(s).unwrap()).collect();
    (report,calls)
}
fn assess(before: &ScreenState, fresh: &ScreenState) -> Verification {
    launch_completion::assess(&LaunchGoal::Target("org.example.compass".into()),before,fresh,
        before.foreground.as_ref().map_or(0,|f|f.sequence),"open Compass",&apps()).verification
}

#[test]
fn exact_multiple_labels_packages_and_wrappers_are_generic() {
    for app in apps() {
        for name in [&app.label, &app.package] {
            for request in [format!("open {name}"),format!("launch the {name} app."),format!("Please OPEN {name}"),format!(" open {name} ")] {
                assert_eq!(launch_completion::recognize(&request,&apps()),Some(LaunchGoal::Target(app.package.clone())),"{request}");
            }
        }
    }
}

#[test]
fn no_truncation_compounds_instructions_or_unrecognized_goal_forms() {
    for request in ["open Compass and send a message", "open Compass, delete history", "open Compass; delete history",
        "open Compass. Delete history", "open Compass. Ring alarm", "open Compass - Ring alarm",
        "open Compass in background", "open Compass tomorrow", "open Compass\ndelete history", "open Compass\0and delete",
        "open Compass then close it", "open Compass to change settings", "open Compass without changing anything",
        "open Compass / Daily Ledger", "open Compass & Daily Ledger", "open Compass + Daily Ledger",
        "open Compass delete history", "open Compass take a photo", "open Compass erase history", "open Compass before launching Daily Ledger", "open Compass or Daily Ledger",
        "turn on wifi and open Compass", "can you open Compass?", "open Compass… send a message"] {
        // Even a malicious installed label matching the full suffix cannot authorize this path.
        let mut installed=apps();
        installed.push(AppEntry{label:request.strip_prefix("open ").unwrap_or(request).into(),package:"org.example.evil".into(),activity:None});
        assert_eq!(launch_completion::recognize(request,&installed),None,"{request}");
    }
}

#[test]
fn unknown_aliases_and_all_label_package_wrapper_collisions_are_unresolved() {
    for request in ["open Compas", "open Maps", "open Orchard", "open Compass now", "open the Compass app please"] {
        assert_eq!(launch_completion::recognize(request,&apps()),Some(LaunchGoal::Unresolved),"{request}");
    }
    for (label,request) in [("Compass","open Compass"),("compass","open COMPASS"),
        ("the Compass","open the Compass"),("Compass app","open Compass app"),
        ("org.example.compass","open org.example.compass")] {
        let mut installed=apps();
        installed.push(AppEntry{label:label.into(),package:"org.example.other".into(),activity:None});
        assert_eq!(launch_completion::recognize(request,&installed),Some(LaunchGoal::Unresolved),"{request}");
    }
    // Multiple launcher entries in the same package are one app identity.
    let mut installed=apps(); installed.push(installed[1].clone());
    assert_eq!(launch_completion::recognize("open Compass",&installed),Some(LaunchGoal::Target("org.example.compass".into())));
}

#[test]
fn actual_launch_then_done_uses_fresh_host_evidence_without_verifier_or_extra_mutations() {
    for app in apps() {
        let (report,calls)=run(&format!("open {}",app.label),vec![json!(screen("org.example.launcher",1)),
            completion(json!({"action":"open_app","app":app.label})),json!({"result":"ok"}),
            json!(screen(&app.package,2)),done(),json!(screen(&app.package,3))],4);
        assert_eq!(report["done"],true);
        assert_eq!(report["execution_status"],"executed");
        assert_eq!(report["verification"]["evidence_basis"],"code_validated_host_foreground");
        assert_eq!(report["verification"]["foreground"]["package"],app.package);
        assert_eq!(report["verification"]["foreground"]["sequence"],3);
        assert_eq!(calls.iter().filter(|v|v["ask"]=="act").count(),1);
        assert_eq!(calls.iter().filter(|v|v["role"]=="planner").count(),2);
        assert!(!calls.iter().any(|v|v["role"]=="verifier"));
    }
}

#[test]
fn already_foreground_requires_new_readback_but_no_launch() {
    let (report,calls)=run("open Compass",vec![json!(screen("org.example.compass",1)),done(),json!(screen("org.example.compass",2))],3);
    assert_eq!(report["done"],true);
    assert_eq!(report["execution_status"],"not_executed");
    assert!(!calls.iter().any(|v|v["ask"]=="act"));
    assert_eq!(calls.iter().filter(|v|v["ask"]=="observe").count(),2);
}

#[test]
fn ack_opened_ui_and_model_claim_cannot_complete_without_observed_target() {
    let (report,calls)=run("open Compass",vec![json!(screen("org.example.ledger",1)),
        completion(json!({"action":"open_app","app":"Compass"})),json!({"result":"ok"}),
        json!(screen("org.example.ledger",2)),done(),json!(screen("org.example.ledger",3))],8);
    assert_eq!(report["done"],false);
    assert_eq!(report["task_status"],"pending");
    assert_eq!(report["execution_status"],"executed");
    assert_eq!(report["verification"]["status"],"unknown");
    assert_eq!(calls.iter().filter(|v|v["ask"]=="act").count(),1);
    assert_eq!(calls.iter().filter(|v|v["role"]=="planner").count(),2);
}

#[test]
fn missing_grants_capability_evidence_and_contradictory_or_stale_host_data_fail_closed() {
    let before=screen("org.example.compass",8);
    for variant in 0..13 {
        let mut fresh=screen("org.example.compass",9);
        match variant {
            0=>fresh.capabilities=None,
            1=>fresh.capabilities.as_mut().unwrap().app_grants.clear(),
            2=>fresh.capabilities.as_mut().unwrap().observations.clear(),
            3=>fresh.capabilities.as_mut().unwrap().operations.clear(),
            4=>fresh.foreground=None,
            5=>fresh.foreground.as_mut().unwrap().sequence=8,
            6=>fresh.foreground.as_mut().unwrap().sequence=7,
            7=>fresh.foreground.as_mut().unwrap().sequence=0,
            8=>fresh.foreground.as_mut().unwrap().package="org.example.ledger".into(),
            9=>fresh.foreground.as_mut().unwrap().activity="NotMain".into(),
            10=>{fresh.foreground.as_mut().unwrap().activity.clear();fresh.activity.clear();},
            11=>{fresh.package="org.example.ledger".into();fresh.foreground.as_mut().unwrap().package=fresh.package.clone();},
            12=>fresh.installed_apps.retain(|a|a.package!="org.example.compass"),
            _=>unreachable!(),
        }
        assert_eq!(assess(&before,&fresh).status,VerificationStatus::Unknown,"case {variant}");
        let (report,calls)=run("open Compass",vec![json!(before),done(),json!(fresh)],8);
        assert_eq!(report["done"],false,"case {variant}");
        assert!(!calls.iter().any(|v|v["role"]=="verifier" || v["ask"]=="act"));
        assert_eq!(report["steps"],1); // bounded instead of repeated done guesses
    }
}

#[test]
fn notification_only_host_and_missing_initial_freshness_cannot_complete() {
    let mut before=screen("com.android.systemui",1);
    before.activity="notification_listener".into(); before.foreground=None;
    before.capabilities.as_mut().unwrap().observations.clear();
    let mut fresh=before.clone();
    // Notification node ownership and ordinary package headers never prove foreground.
    fresh.nodes[0].package=Some("org.example.compass".into());
    assert_eq!(assess(&before,&fresh).status,VerificationStatus::Unknown);
    let actual=screen("org.example.compass",2);
    assert_eq!(assess(&before,&actual).status,VerificationStatus::Unknown);
}

#[test]
fn unknown_and_ambiguous_launches_do_not_ask_a_model_to_supply_identity() {
    for request in ["open Unknown", "open Compas", "open Compass"] {
        let mut before=screen("org.example.compass",1);
        if request=="open Compass" {
            before.installed_apps.push(AppEntry{label:"Compass".into(),package:"org.other.compass".into(),activity:None});
        }
        let mut fresh=before.clone(); fresh.foreground.as_mut().unwrap().sequence=2;
        let (report,calls)=run(request,vec![json!(before),done(),json!(fresh)],3);
        assert_eq!(report["done"],false);
        assert!(!calls.iter().any(|v|v["role"]=="verifier"));
    }
}

#[test]
fn compounds_stay_on_ordinary_verification_path_and_foreground_does_not_satisfy_them() {
    for request in ["open Compass and delete history", "open Orchard Notes and save a note", "enable wifi and open Compass"] {
        let (report,calls)=run(request,vec![json!(screen("org.example.compass",1)),done(),json!(screen("org.example.compass",2)),
            completion(json!({"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}))],1);
        assert_eq!(report["done"],false);
        assert!(calls.iter().any(|v|v["role"]=="verifier"));
        assert_ne!(report["verification"]["evidence_basis"],"code_validated_host_foreground");
    }
}

#[test]
fn model_cannot_mint_host_evidence() {
    let s=screen("org.example.compass",1);
    let view=screen_filter::filter(&s,&FilterContext{request:"open Compass",typed:&[]});
    let verdict=json!({"status":"verified","nodes":[],"evidence_basis":"code_validated_host_foreground"});
    assert_eq!(verification::assess(&verdict.to_string(),&view).status,VerificationStatus::Unknown);
}

#[test]
fn old_json_defaults_absent_and_new_contract_round_trips() {
    let old:ScreenState=serde_json::from_value(json!({"package":"p","activity":"a","width":1,"height":1,"nodes":[],
        "capabilities":{"operations":[],"app_grants":[]}})).unwrap();
    assert!(old.foreground.is_none());
    assert!(old.capabilities.unwrap().observations.is_empty());
    let fresh=screen("org.example.compass",2);
    assert_eq!(serde_json::from_value::<ScreenState>(serde_json::to_value(&fresh).unwrap()).unwrap(),fresh);
    let proof=assess(&screen("org.example.compass",1),&fresh);
    assert_eq!(serde_json::from_value::<Verification>(serde_json::to_value(&proof).unwrap()).unwrap(),proof);
}

#[test]
fn cancellation_after_foreground_readback_prevents_completion_and_any_followup() {
    use ballistic_core::{ports::*,run};
    use std::cell::Cell;
    struct Screens<'a>{stop:&'a Cell<bool>,reads:u64}
    impl Screen for Screens<'_> {fn observe(&mut self)->PortResult<ScreenState>{
        self.reads+=1; if self.reads==2 {self.stop.set(true);}
        Ok(screen("org.example.compass",self.reads))
    }}
    struct NoHands;
    impl Hands for NoHands {fn act(&mut self,_:&HandsOp)->PortResult<HandsResult>{panic!("no dispatch")}}
    struct NoConfirm;
    impl Confirm for NoConfirm {fn ask(&mut self,_:&ConfirmRequest)->PortResult<ConfirmResult>{panic!("no approval")}}
    struct Model(usize);
    impl Models for Model {fn complete(&mut self,role:ModelRole,_:&[ChatMessage],_:u32)->PortResult<Completion>{
        assert_eq!(role,ModelRole::Planner);self.0+=1;
        Ok(Completion{text:r#"{"action":"done","evidence":"claimed"}"#.into(),usage:Usage::default()})
    }}
    let stop=Cell::new(false);let mut screens=Screens{stop:&stop,reads:0};let mut model=Model(0);
    let report=run::run("open Compass",5,&mut screens,&mut NoHands,&mut model,&mut NoConfirm,&mut |_|{},&||stop.get()).unwrap();
    assert_eq!(report["done"],false);assert_eq!(report["stopped"],true);
    assert_eq!(report["verification"]["status"],"unknown");
    assert_eq!(screens.reads,2);assert_eq!(model.0,1);
}

#[test]
fn regressed_intermediate_observation_cannot_reset_freshness() {
    let (report,_)=run("open Compass",vec![json!(screen("org.example.ledger",9)),
        completion(json!({"action":"open_app","app":"Compass"})),json!({"result":"ok"}),
        json!(screen("org.example.compass",2)),done(),json!(screen("org.example.compass",3))],5);
    assert_eq!(report["done"],false);
    assert_eq!(report["reason"],"foreground observation is stale; completion not established");
}

#[test]
fn notification_observation_cannot_be_upgraded_by_a_launch_ack() {
    let mut s=screen("com.android.systemui",1);
    s.activity="notification_listener".into();s.foreground=None;
    s.capabilities.as_mut().unwrap().observations.clear();
    let (report,calls)=run("open Compass",vec![json!(s),
        completion(json!({"action":"open_app","app":"Compass"})),json!({"result":"ok"}),
        json!(s),done(),json!(s)],6);
    assert_eq!(report["done"],false);
    assert_eq!(report["execution_status"],"executed");
    assert_eq!(report["verification"]["status"],"unknown");
    assert!(!calls.iter().any(|v|v["role"]=="verifier"));
    assert_eq!(calls.iter().filter(|v|v["ask"]=="act").count(),1);
}
