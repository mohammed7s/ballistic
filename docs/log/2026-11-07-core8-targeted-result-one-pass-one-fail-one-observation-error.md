# 2026-11-07 — Core8 targeted result: one pass, one fail, one observation error

Exact77bc36b fixed three-task round finished1PASS/1FAIL/1ERROR,state1/3. MarkorDeleteNote got ReaderTapOnly feedback and then legally tapped the row, but later menu reads returned none and it failed; exact intended-note identification is unproven. Stopwatch passed with seven successful host observations. Wi-Fi initial observe failed foreground_not_settled before any model call; underlying samples were not recorded. Stricter settling is a possible contributor, not a demonstrated cause. Same Core6 cases2PASS/1FAIL,state2/3; differing initial state/order prevents causal comparison. No new full score;8/20combined,11/20state remains latestcomplete.

Parent verified73artifact hashes and rawoutcomes/usage:26calls/26HTTP200attempts,zero retries,58,806prompt+922output;143.3sagent+0.196schecker,64.883spacing already included. Freshremote296Rust/118Python gates passed. One acceptedDone,zero final falseDone,no sideeffect/security claim. Release11:45:55UTC independently verified11:46:28, visibleemulator/dirtymain/source/binary preserved. Detailed record `docs/BENCHMARK_RUNS.md`, ignored `ballistic_runs/core8_targeted_77bc36b/`.

Next: a separately bounded acquisition-only diagnostic with zero model calls and unchanged rules, capturing three observe attempts after ordinary Wi-Fi task setup under one exclusive lock (maximum18raw acquisitions). Replay both old/new predicates offline against the same captured sequences; no expected-control waits, runtime relaxation or outcome-driven extra attempts. A separate offline review can inspect the newly reached Markor menu failure. No full-suite rerun before the observation compatibility issue is understood.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
