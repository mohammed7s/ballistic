# Instructions for agents working in this directory

Ballistic is an Android assistant app with a Rust core and Python development harness.
A native prototype and emulator agent exist; general phone automation and the full safety design
remain unfinished. This repository owns the app, not the operating system.

Read README.md, SPEC.md, then docs/CONTEXT.md for current decisions and next work.
Read docs/PLATFORM.md for platform dependencies and docs/PLAN.md for the app roadmap.
Consult implementation docs beside the relevant code. Do not read the entire historical log
as a prerequisite for a narrow task.

- Keep the name Ballistic; no sub-brands or implied GrapheneOS endorsement.
- Put OS gate implementation, build/sign/flash/OTA work in ballistic-os. This repo owns platform
  requirements and client adapters; link to OS documentation instead of duplicating procedures.
- Keep planning, policy and models in the app. Host adapters must not contain agent decisions.
- Keep current decisions in docs/CONTEXT.md, dated findings in docs/log/, and source research in
  docs/FACTS.md. Preserve evidence qualifiers and distinguish implemented, tested and planned.
- Historical notes may be superseded. A successful build is not proof of runtime behavior;
  scripted approval is not biometric validation; a state pass is not a side-effect safety check.
- Update the affected guides when behavior changes. SPEC.md is authoritative over old HTML/PDF
  exports. Do not turn future platform access into a compatibility or delivery-date promise.
