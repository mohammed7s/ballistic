# 7 November 2026 — original Gemini key available after documented daily reset

At the owner's request, parent checked the original remote `BALLISTIC_API_KEY` at
**08:30:40 UTC**: one minimal `gemini-flash-lite-latest` request returned **HTTP 200**, nonempty
text, 5 prompt + 2 completion tokens, 0.977 s, no retries. No credential value left the host.
Google's [rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits) says
requests-per-day reset at midnight Pacific and limits apply per project, not per API key.
Today's documented reset was **08:00 UTC / 11:00 Amman**. Actual available quota count and
whether the two keys share a project remain unknown; success verifies this request only.

Prepared the next frozen round at **`05ab6eb57408fbf3951ed6b2906eaab21c010534`**, expected
270 Rust/94 Python passes, original key only, same model/seed/budgets and five-second pacing.
It includes the explicit optional `tool_choice: none` request; acceptance/effectiveness still
require live measurement. First HTTP 400 with that option stops without fallback; any 429
stops all further calls without key rotation. New availability supersedes the earlier quota
block for attempting this round; no billing change was made. Supervisor/authorization artifacts
are prepared locally in ignored `ballistic_runs/core6_prep/`; a new Astra task will perform
exclusive emulator preflight, freeze the build/manifest and run the original 20 tasks once.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
