# 2026-11-07 — Narrow observation settling integrated; live compatibility pending

Reviewed and integrated candidate `d24a8df`: slight-change tolerance now permits only text variation on noninteractive text with passive ancestry, keeping the existing10%cap and rejecting changes to labels/bounds/flags/control captions. Comparison-only excluded-parent marker prevents ancestry loss; returned observation schema is unchanged. Exact matching, foreground bracketing, freshness guards, six acquisitions/30ssoft deadline and backoff remain. No action/model/core policy changes; parked history/numeric code stays excluded.

Parent independently reran all116Python tests with the child's freshly built unchanged-main Rust binary, zero failures/skips, plus the unchanged strict reproduction: all six changing-control sequences require4acquisitions instead of2, ticking text still2, count/resource controls3. Test stdout includes synthetic injection error/lock cases, not live execution or a live lock claim. Source review confirms linear ancestry propagation. Child local512-node comparison cost~1.54ms/~28KB scratch; no phone-performance claim. Details and exact commands: `docs/log/2026-11-07-observation-settling.md`.

Formerly tolerated animations/focused containers can wait longer or time out within existing budgets. Repeated identical stale/incomplete trees remain undetectable; Camera missing-Shutter cause is still unknown. No new benchmark result. Live compatibility check will be coordinated with the separate reader-operation feedback candidate once reviewed, not a full-suite rerun solely for this change. Last full score remains8/20 combined,11/20 state. This review made no device/model calls.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
