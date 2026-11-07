//! Unsupported tool requests stay inert even when a stale host advertises grants.
use ballistic_core::{agent, bridge::Bridge, tools::ToolRequest};
use serde_json::{json, Value};
use std::io::Cursor;

#[test]
fn host_grants_cannot_enable_unsupported_planner_tools() {
    let screen = json!({"package":"example.notes","activity":"Main","width":100,"height":100,
        "nodes":[],"capabilities":{"operations":[],"app_grants":[],
        "tool_grants":[{"tool":"example.lookup","scope":[{"id":"main"}]}]}});
    let action = json!({"action":"tool","tool":"example.lookup","args":{"resource_id":"main"}});
    let completion = json!({"text":action.to_string()});
    let input = [screen, completion.clone(), completion].into_iter().enumerate()
        .map(|(i, data)| json!({"id":i+1,"ok":true,"data":data}).to_string()+"\n")
        .collect::<String>();
    let mut output = Vec::new();
    let result = agent::run(&mut Bridge::new(Cursor::new(input), &mut output), "look up the item", 2).unwrap();
    assert_eq!(result["done"], false);
    assert_eq!(result["failure_category"], "planning");
    assert_eq!(result["execution_status"], "not_executed");
    assert!(result.get("tool_results").is_none());
    assert!(result.get("answer").is_none());
    for line in String::from_utf8(output).unwrap().lines() {
        let call: Value = serde_json::from_str(line).unwrap();
        assert!(!matches!(call["ask"].as_str(), Some("tool" | "act" | "confirm")));
        if call["ask"] == "complete" {
            assert!(!call["messages"][0]["content"].as_str().unwrap().contains("example.lookup"));
        }
    }
}

#[test]
fn arbitrary_json_cannot_construct_a_typed_tool_request() {
    for request in [json!({}), json!({"tool":"example.lookup","resource_id":"main"})] {
        assert!(serde_json::from_value::<ToolRequest>(request).is_err());
    }
}
