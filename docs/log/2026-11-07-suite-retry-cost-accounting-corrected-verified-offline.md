# 7 November 2026 — suite retry-cost accounting corrected (verified offline)

Suite TSV now sums known token usage across recorded retry attempts, reports unknown usage
counts separately, and distinguishes logical calls, client HTTP attempts, retries, pacing and
checker seconds. Its header comes from the same column definition as its rows. Reinspection of
all 20 Core 4 JSONs finds **197 logical calls / 198 recorded attempts**, including one 503 retry;
eight attempts have unknown usage. Known totals remain 410,050 prompt / 6,691 completion tokens.
The three caption cases reproduce 33 attempts, 82,330 / 1,278 tokens and 25.511 s pacing.
Parent checks: **85 Python tests pass, no skips**, plus shell syntax and diff checks. No model,
execution, confirmation, budget or score semantics changed.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
