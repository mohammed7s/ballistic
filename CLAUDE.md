# Instructions for agents working in this directory

This is the Ballistic project: a local-first, system-level AI assistant for GrapheneOS. Nothing is
built yet; the directory holds the design.

Read in this order before doing anything:
1. `docs/CONTEXT.md` — conclusions, verified facts and open questions from the design sessions.
2. `README.md` — goals and non-goals.
3. `SPEC.md` — the technical spec.
4. `docs/PLAN.md` — the ordered plan and intended repo layout.

Rules:
- Do not rename the project or add sub-brands. It is "Ballistic", one name for everything.
- Do not use "Graphene" or "GrapheneOS" in product names; say "Ballistic for GrapheneOS" /
  "GrapheneOS with Ballistic".
- OS-side changes must stay small, generic and user-facing (candidates for upstreaming).
  Anything that can be an app goes in the app.
- Facts in `docs/CONTEXT.md` marked "verified" were checked against the Android 17 AOSP source
  (`android17-release`); facts marked "reported" come from the press or docs and should be
  re-checked before relying on them.
- Keep `docs/CONTEXT.md` up to date when a decision changes; it is the hand-off document between
  sessions.
