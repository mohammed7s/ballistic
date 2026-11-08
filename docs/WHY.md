# Why Ballistic exists

An assistant is useful when your hands are busy, a task spans several apps, or the same workflow
needs repeating. Ballistic explores whether that help can remain under the phone owner's control:
local models, explicit access to apps, inspectable actions and approval for sensitive operations.

It is not a promise to tap faster than a person. Model latency matters, and replaying a verified
workflow may be more useful than repeatedly asking a model to rediscover the interface.

The project separates the assistant from the platform that gives it access. GrapheneOS is the
first full-capability integration target because its source can be inspected and patched.
That choice does not make Ballistic an OS or limit the app architecture to Pixels. Future stock
Android support depends on usable APIs and permissions, not a change to the product's identity.

## Research context

Earlier research compared Gemini's screen automation and ByteDance's Doubao assistant, and
considered European interoperability requirements. Those product and regulatory claims are dated
research, not prerequisites or guaranteed availability dates. They are preserved with their
qualifiers in [FACTS.md](FACTS.md) and the [original README](log/2026-11-07-before-reorganization-readme.md).
Recheck primary sources before relying on them for a platform or distribution decision.

PhoneHarness informed our executable capability contracts, evidence-oriented completion,
progressive capability instructions and side-effect tests. It does not replace our Rust core or
the distinction between what an agent reads and what it is authorized to do.

See [the app specification](../SPEC.md) for the target design and [current context](CONTEXT.md)
for the implementation boundary. OS proposals belong in
[ballistic-os](https://github.com/mohammed7s/ballistic-os/blob/main/docs/PLAN.md).
