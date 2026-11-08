# 7 November 2026 — text-only response contract and optional request control (verified offline)

Integrated `a26f7c0` (agent `6d165fd`): both Python and Android reject native tool/function,
refusal and standard content-filter responses even when they contain accompanying text.
Native arguments never become Ballistic actions; no retry/redispatch or validation relaxation.
Android HTTP diagnostics no longer retain raw provider-body excerpts. An optional endpoint
flag adds `tool_choice: "none"`; it defaults off because retrieved primary documentation does
not establish this parameter without tool declarations at the Gemini Developer API endpoint.
No model, token/step budget, thinking setting, output format or fallback is changed.

Parent combined checks pass **94 Python and 51 JVM tests, zero skips/failures**, including
actual request serialization and real-core no-dispatch/no-redispatch paths. Rust source is
unchanged from the immediately preceding 206-test passing build. Live parameter acceptance
and effectiveness remain unmeasured. `docs/log/2026-11-07-model-text-transport.md` records primary
sources, exact scope and limitations. The running suite remains pinned to `7a9238e`.

Parent also verified all nine transferred checkpoint artifacts for Timer/Bluetooth/Brightness.
Timer failed after wrong numeric-keypad/backspace actions exhausted ten steps (48.5 s).
Bluetooth had visible e4 Bluetooth but repeatedly queried unrelated hidden content (63.3 s).
Brightness never reached Display: it queried the first visible homepage rows, got none, and
repeated the query without scrolling (18.7 s). Thus its current failure precedes the separately
known range-value observation gap. A general fixed reader-none/navigation feedback improvement
is being developed offline; guard and budget changes are outside its scope.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
