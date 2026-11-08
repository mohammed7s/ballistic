# 2026-11-07 — Core 7 candidates integrated; live validation pending

Reviewed and integrated the Astra candidates as `a9ba063` (Done verifier display/draft distinction), `5f7d87f` (bounded reader correction), and `c5b1b06` (Markor extension investigation). The reader uses the existing second planner attempt when the first proposal repeats an unchanged element read or exceeds the three-read cap; it does not raise that cap, step limits, output caps, retries or action authority. The original private-query normalization/equality and hard terminal guard remain. Correction adds 326 characters plus the echoed planner reply and may increase actual calls by consuming a previously unused slot. The recorded MarkorDelete terminal path already consumed its correction slot on rejected long-press and therefore still stops. See `docs/log/2026-11-07-reader-correction.md`.

The verifier now distinguishes displayed text from editable drafts and treats disabled submission controls as inconclusive in isolation. Requested postconditions, fresh readable citations and unknown/failed semantics remain. Core6 SMSsend had three verifier misinterpretations; SMSreply had no verifier calls, so this change does not address its planner confusion. Missing recipient/status evidence can still require unknown. The verifier system prompt grows by 835 characters; live token cost and accuracy are unmeasured. See `docs/log/2026-11-07-verifier-display.md`.

No Markor extension projection was added: the recorded `.md` could be a current value or an empty field's hint, which the normalized observation does not distinguish. Source and a synthetic normalizer reproduction are documented in `docs/log/2026-11-07-markor-extension-observation.md`; installed APK identity and the precise saved-file mismatch remain unverified. A producer/host observation distinction is required before claiming a current value.

Parent validation of the combined `c5b1b06` tree: **285 Rust/UniFFI, 95 Python and 51 JVM tests passed, zero failures/ignored/skips**, with a freshly rebuilt core binary/library. Logs: `/tmp/ballistic-core7-integrated-{rust,build,python,jvm}.log`. These are offline checks, not new task passes. Next is a separately recorded targeted live round on the same original key, model, seed and limits, followed by review before another complete suite. The last measured full score remains **Core6 8/20 combined, 11/20 state, zero false Done**; the >=13/20 goal remains unmet.



Historical handoff entry. Later entries may supersede its status; see [current context](../CONTEXT.md).
