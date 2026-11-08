//! `ballistic-core`: the agent as a process the harness drives over stdin/stdout
//! (JSON lines, see `bridge.rs`).
//!
//!   ballistic-core observe                      observe once, report counts (pipe smoke test)
//!   ballistic-core run --request "..." [--max-steps N] [--judge]
//!                                               the agent loop (filled in as core/README.md §16 proceeds)
//!   ballistic-core converse --message "..." [--history JSON] [--history-turns N]
//!                           [--max-steps N] [--judge]
//!                                               one conversation turn (`chat.rs`); a task turn
//!                                               runs the loop on the same pipe
//!
//! The last line core writes is a log event with `"finished": true` and a `report` (run), a
//! `turn` (converse) or an `error`. The judge is off unless `--judge` is given, so benchmark
//! runs stay comparable with earlier ones.

use std::io::{self, BufReader};

use ballistic_core::bridge::Bridge;
use ballistic_core::ports::Screen;
use ballistic_core::run::Options;
use serde_json::json;

const USAGE: &str = "usage: ballistic-core filter [--request TEXT] | observe | run --request TEXT [--max-steps N] [--judge] | converse --message TEXT [--history JSON] [--history-turns N] [--max-steps N] [--judge]";

struct Args {
    mode: String,
    request: String,
    message: String,
    history: String,
    options: Options,
}

fn parse_args() -> Result<Args, String> {
    let mut it = std::env::args().skip(1);
    let mode = it.next().ok_or(USAGE)?;
    let mut request = String::new();
    let mut message = String::new();
    let mut history = String::from("[]");
    let mut options = Options::harness();
    let number = |v: Option<String>, flag: &str| -> Result<u32, String> {
        v.ok_or(format!("{flag} needs a value"))?.parse().map_err(|_| format!("{flag} must be a number"))
    };
    while let Some(a) = it.next() {
        match a.as_str() {
            "--request" => request = it.next().ok_or("--request needs a value")?,
            "--message" => message = it.next().ok_or("--message needs a value")?,
            "--history" => history = it.next().ok_or("--history needs a value")?,
            "--max-steps" => options.max_steps = number(it.next(), "--max-steps")?,
            "--history-turns" => options.chat_history_turns = number(it.next(), "--history-turns")?,
            "--judge" => options.judge = true,
            other => return Err(format!("unknown argument {other}")),
        }
    }
    if mode == "run" && request.is_empty() {
        return Err("run needs --request".into());
    }
    if mode == "converse" && message.trim().is_empty() {
        return Err("converse needs --message".into());
    }
    Ok(Args { mode, request, message, history, options })
}

fn main() {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };
    if args.mode == "filter" {
        let result = serde_json::from_reader::<_, ballistic_core::types::ScreenState>(io::stdin().lock());
        match result {
            Ok(screen) => {
                let view = ballistic_core::screen_filter::filter(&screen,
                    &ballistic_core::screen_filter::FilterContext { request: &args.request, typed: &[] });
                println!("{}", ballistic_core::screen_filter::render(&view));
                return;
            }
            Err(e) => { eprintln!("invalid screen: {e}"); std::process::exit(1); }
        }
    }
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut host = Bridge::new(BufReader::new(stdin.lock()), stdout.lock());

    let result = match args.mode.as_str() {
        "observe" => observe_once(&mut host).map(|r| ("report", r)),
        "run" => ballistic_core::agent::run_with(&mut host, &args.request, args.options.max_steps, &args.options)
            .map(|r| ("report", r)).map_err(|e| e.to_string()),
        "converse" => converse(&mut host, &args).map(|t| ("turn", t)),
        other => Err(format!("unknown mode {other}")),
    };
    let event = match &result {
        Ok((key, v)) => json!({"finished": true, *key: v}),
        Err(e) => json!({"finished": true, "error": e}),
    };
    let _ = host.log(&event);
    if result.is_err() {
        std::process::exit(1);
    }
}

fn converse<R: io::BufRead, W: io::Write>(host: &mut Bridge<R, W>, args: &Args) -> Result<serde_json::Value, String> {
    let history: Vec<ballistic_core::chat::Turn> = serde_json::from_str(&args.history).map_err(|e| format!("--history: {e}"))?;
    let turn = ballistic_core::chat::converse(host, &history, &args.message, &args.options).map_err(|e| e.to_string())?;
    serde_json::to_value(turn).map_err(|e| e.to_string())
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
