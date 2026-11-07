//! The Tools port through the agent loop and the JSON-lines bridge, with scripted host replies.
//! No model, network or wallet adapter is involved.
use ballistic_core::{agent, bridge::Bridge};
use serde_json::{json, Value};
use std::io::Cursor;

const AMOUNT_WEI: &str = "1234500000000000000";
const ADDRESS: &str = "0x00000000000000000000000000000000000000a1";

fn screen(granted: bool) -> Value {
    let grants = if granted {
        json!([{"tool":"wallet.balance","scope":[{"id":"main","label":"Main wallet"}]}])
    } else {
        json!([])
    };
    json!({"package":"app.ballistic.assistant","activity":"Main","width":100,"height":100,"nodes":[],
        "capabilities":{"operations":["open_app"],"app_grants":[],"tool_grants":grants}})
}
fn completion(v: Value) -> Value { json!({"text": v.to_string()}) }
fn tool_action() -> Value {
    json!({"action":"tool","tool":"wallet.balance","args":{"wallet_id":"main","chain_id":1,"asset":"native"},"why":"owner asked"})
}
fn balance() -> Value {
    json!({"result":"balance","wallet_id":"main","address":ADDRESS,"chain_id":1,"balance_wei":AMOUNT_WEI,
        "balance_eth":"1.2345","block_number":21000000,"block_hash":format!("0x{}", "ab".repeat(32)),
        "observed_at":"2026-11-07T12:00:00Z","source":"rpc.example","transport":"tor","trust":"rpc_reported"})
}
fn run(replies: Vec<Value>, steps: u32) -> (Value, Vec<Value>) {
    let input = replies.into_iter().enumerate()
        .map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string() + "\n").collect::<String>();
    let mut output = Vec::new();
    let result = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), "what's my Ethereum balance?", steps).unwrap();
    let calls = String::from_utf8(output).unwrap().lines().map(|s| serde_json::from_str(s).unwrap()).collect();
    (result, calls)
}
fn asks<'a>(calls: &'a [Value], kind: &str) -> Vec<&'a Value> {
    calls.iter().filter(|c| c["ask"] == kind).collect()
}

#[test]
fn without_a_grant_no_tool_call_is_made() {
    let (result, calls) = run(vec![screen(false), completion(tool_action())], 3);
    assert_eq!(result["reason"], "not granted");
    assert_eq!(result["failure_category"], "permission");
    assert!(asks(&calls, "tool").is_empty());
    assert!(result.get("tool_results").is_none());
}

#[test]
fn granted_request_reaches_the_host_unchanged_and_the_answer_is_rendered_from_it() {
    let answer = json!({"action":"answer","text":"Main wallet has 1.2345 ETH."});
    let (result, calls) = run(vec![screen(true), completion(tool_action()), balance(), screen(true), completion(answer)], 3);
    let tool = asks(&calls, "tool");
    assert_eq!(tool.len(), 1);
    assert_eq!(tool[0]["request"], json!({"tool":"wallet.balance","wallet_id":"main","chain_id":1,"asset":"native"}));
    assert_eq!(result["done"], true);
    assert_eq!(result["task_status"], "answered");
    assert_eq!(result["answer"]["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.");
    assert_eq!(result["answer"]["model_text_check"], "consistent");
    let card = &result["tool_results"][0];
    assert_eq!(card["balance_wei"], AMOUNT_WEI);
    assert_eq!(card["balance_eth"], "1.2345");
    assert_eq!((card["source"].as_str(), card["transport"].as_str(), card["trust"].as_str()),
        (Some("rpc.example"), Some("tor"), Some("rpc_reported")));
    assert_eq!(result["answer"]["values"][0]["source"]["kind"], "rpc");
    // The second planner prompt carries the typed result with its source, never the address.
    let prompts = asks(&calls, "complete");
    let second = prompts[1]["messages"].to_string();
    assert!(second.contains("balance_eth=1.2345") && second.contains("[source: rpc rpc.example via tor"));
    assert!(!second.contains(ADDRESS));
    assert!(prompts[0]["messages"][0]["content"].as_str().unwrap().contains("wallet.balance for wallet_id main (\"Main wallet\")"));
}

#[test]
fn an_answer_that_alters_the_amount_is_not_shown() {
    let lie = json!({"action":"answer","text":"Main wallet has 12.345 ETH."});
    let (result, _) = run(vec![screen(true), completion(tool_action()), balance(), screen(true), completion(lie)], 3);
    assert_eq!(result["answer"]["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.");
    assert_eq!(result["answer"]["model_text"], Value::Null);
    assert_eq!(result["answer"]["model_text_check"], "rejected: 12.345 not in the tool result");
    assert!(result["reason"].as_str().unwrap().contains("model wording dropped"));
}

#[test]
fn adapter_failure_stops_without_a_balance() {
    let failed = json!({"result":"failed","code":"timeout","message":"no consistent answer within 60 s"});
    let (result, calls) = run(vec![screen(true), completion(tool_action()), failed], 3);
    assert_eq!(result["reason"], "balance could not be refreshed: timeout");
    assert_eq!(result["done"], false);
    assert!(result.get("tool_results").is_none());
    assert_eq!(asks(&calls, "complete").len(), 1);
}

#[test]
fn model_supplied_addresses_or_endpoints_are_rejected_before_the_host() {
    let bad = json!({"action":"tool","tool":"wallet.balance","args":{"wallet_id":"main","chain_id":1,"asset":"native","address":ADDRESS}});
    let worse = json!({"action":"tool","tool":"eth_sendRawTransaction","args":{}});
    let (result, calls) = run(vec![screen(true), completion(bad), completion(worse)], 3);
    assert_eq!(result["failure_category"], "planning");
    assert!(asks(&calls, "tool").is_empty());
}

#[test]
fn answer_without_tool_results_is_still_unsupported() {
    let (result, _) = run(vec![screen(true), completion(json!({"action":"answer","text":"You have 3 ETH"}))], 3);
    assert_eq!(result["task_status"], "unsupported");
    assert!(result.get("answer").is_none());
}
