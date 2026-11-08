# 7 November 2026 — bounded calendar observations integrated (verified offline)

Integrated `ca4044f`: dedicated Calendar date/time fields expose bounded numeric observations
with app/source-node tags, separately from labels and action authority. Missing years remain
unknown; malformed, localized or structurally ambiguous values stay opaque. Original strings
remain reader content. No model-supplied parsing, benchmark state access, external schema or
UniFFI change. On the recorded event task, 24 form and 16 list values now appear; 14 unsupported
row values remain hidden. These are repeated snapshot values, not 40 successful operations.

Parent combined checks pass **128 Rust/UniFFI and 79 Python tests, no skips**. Neither live
calendar success nor stronger semantic verification is established: a model can still cite a
readable title without proving every requested field. See `docs/log/2026-11-07-typed-values.md`.
Updated Android plan status to reflect the existing native APK and its measured Clock/Stop
smoke; its other-app observation/GUI adapter and latest-core rebuild remain unfinished.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
