//! `ballistic-core`: the agent as a process the harness drives over stdin/stdout
//! (JSON lines, see `bridge.rs`).
//!
//!   ballistic-core observe                      observe once, report counts (pipe smoke test)
//!   ballistic-core run --request "..." [--max-steps N]
//!                                               the agent loop (filled in as core/README.md §16 proceeds)
//!
//! The last line core writes is a log event with `"finished": true`.

use std::io::{self, BufReader};

use ballistic_core::bridge::Bridge;
use ballistic_core::ports::Screen;
use serde_json::json;

struct Args {
    mode: String,
    request: String,
    max_steps: u32,
}

fn parse_args() -> Result<Args, String> {
    let mut it = std::env::args().skip(1);
    let mode = it.next().ok_or("usage: ballistic-core observe | run --request TEXT [--max-steps N]")?;
    let mut request = String::new();
    let mut max_steps = 15;
    while let Some(a) = it.next() {
        match a.as_str() {
            "--request" => request = it.next().ok_or("--request needs a value")?,
            "--max-steps" => {
                max_steps = it
                    .next()
                    .ok_or("--max-steps needs a value")?
                    .parse()
                    .map_err(|_| "--max-steps must be a number")?
            }
            other => return Err(format!("unknown argument {other}")),
        }
    }
    if mode == "run" && request.is_empty() {
        return Err("run needs --request".into());
    }
    Ok(Args { mode, request, max_steps })
}

fn main() {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut host = Bridge::new(BufReader::new(stdin.lock()), stdout.lock());

    let result = match args.mode.as_str() {
        "observe" => observe_once(&mut host),
        "run" => Err(format!(
            "run is not implemented yet (request: {:?}, max_steps: {})",
            args.request, args.max_steps
        )),
        other => Err(format!("unknown mode {other}")),
    };
    let event = match &result {
        Ok(v) => json!({"finished": true, "report": v}),
        Err(e) => json!({"finished": true, "error": e}),
    };
    let _ = host.log(&event);
    if result.is_err() {
        std::process::exit(1);
    }
}

fn observe_once<R: io::BufRead, W: io::Write>(
    host: &mut Bridge<R, W>,
) -> Result<serde_json::Value, String> {
    let screen = host.observe().map_err(|e| e.to_string())?;
    let labelled = screen.nodes.iter().filter(|n| n.label.is_some()).count();
    let with_text = screen.nodes.iter().filter(|n| n.text.is_some()).count();
    let clickable = screen.nodes.iter().filter(|n| n.flags.clickable).count();
    Ok(json!({
        "package": screen.package,
        "activity": screen.activity,
        "size": [screen.width, screen.height],
        "nodes": screen.nodes.len(),
        "labelled": labelled,
        "with_text": with_text,
        "clickable": clickable,
        "installed_apps": screen.installed_apps.len(),
    }))
}
