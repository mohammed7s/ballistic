# 2026-11-07 — Observation diagnosis separates unknown Camera cause from reproducible settling weakness

Integrated the offline report/frozen extracts/reproduction in `docs/log/2026-11-07-observation-readiness-diagnosis.md`. Parent independently ran it against original A/B records and archived host sources: hashes/extracts match; A's Shutter is absent before Rust filtering, and its8→9-node difference cannot pass the current slight-change tolerance. Original producer/acquisition sequence is unavailable; cause remains unknown. The lone A accessibility warning predates task setup. Missing Camera snapshots and unequal observations prevent equal-state claims.

Separately reproduced six generic cases where changing one of ten nodes' button enabled/visible/clickable flags, text, label or bounds is accepted as settled after two acquisitions. This follows the current permissive rule, but is a weakness relative to its intended ticking-value tolerance. Parent reproduced all six, plus count/resource/small-tree rejection and ticking-text controls. No runtime/device/model changes or score claim in diagnosis. A separate offline implementation round will narrow tolerance to noninteractive text-only changes, preserving deadlines, acquisition limits, freshness checks and later-sample return. Compatibility/timeouts must be tested; this is not a proven Camera fix. Numeric-constraint prototype continues separately. Last full score8/20 combined,11/20 state.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
