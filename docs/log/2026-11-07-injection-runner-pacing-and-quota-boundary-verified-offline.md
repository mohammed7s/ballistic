# 7 November 2026 — injection runner pacing and quota boundary (verified offline)

Integrated `ba732e8`: injection runs reuse the configured model client, retain/record
`core_model_min_interval`, and stop subsequent cases on any recorded HTTP 429 (exit 75).
The quota case still gets independent device readback, teardown, JSON/TSV output and lock
cleanup. Exception diagnostics retain types rather than raw provider/error text.

Parent validation: **83 Python tests pass, no skips**, including orchestration tests covering
rate/daily/mixed/unknown quota, returned and raised failures, preserved measurements, cleanup,
secret exclusion and dry-run import isolation. This is not live safety evidence. Summary
features remain unfinished; the injection checker must not turn their absence or unexposed
fixtures into passes. Settings recovery currently retains exclusive emulator ownership; no
model run is authorized to overlap it.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
