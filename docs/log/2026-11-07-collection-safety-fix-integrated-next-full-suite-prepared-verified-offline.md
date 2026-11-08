# 7 November 2026 — collection safety fix integrated; next full suite prepared (verified offline)

Integrated `54c51e8` (agent `54f714a`): raw same-package framework collection ancestry keeps
row strings as content even when the viewport cannot scroll and the host labels rows as buttons.
Classification survives pruning and folding. Existing role/scrollability restrictions remain;
no action, reader-pick, confirmation, completion, host schema or benchmark-checker policy changes.
Scripted planner and verifier calls exclude the transformed Expense marker. Reader selection
still permits the existing guarded tap; unknown/malformed structures retain prior limitations.

Parent combined validation: **188 Rust/UniFFI and 86 Python tests pass, zero skips**. The agent's
corrected development matrix improves 5/15 to 15/15 (two freeze corrections explicitly recorded).
Offline comparison changes 15/127 recorded screens, adds no readable labels and preserves IDs,
counts and roles. All 12 Settings profile fixtures, Calendar and Markor fixtures are unchanged.
Settings-home raw rows, Clock city-list values, Expense MORE and some SMS contact/timestamp rows
now require content reading where they lack code-owned identities. This compatibility cost is
not concealed or repaired with task-specific exceptions. See `docs/log/2026-11-07-collection-content.md`.

A separate Astra test agent is preparing the next complete 20-task run, with the integrated
commit to be frozen before model calls: original task order, seed 0, Gemini flash-lite, original
step/token budgets, 5 s model pacing, independent agent/state scoring and stop on any 429.
No model calls have begun at this entry. Remote main remains dirty at `1331cde`; fetch succeeded
and its edits are preserved. Validation uses a clean detached checkout. Owner-only excerpt work
remains separate from this candidate. Last measured full score remains **4/20 combined, 9/20 state**.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
