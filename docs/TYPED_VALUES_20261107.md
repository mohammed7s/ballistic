# Dedicated calendar value observations — 7 November 2026

Implemented offline against parent `a89e2fe`. This adds bounded date/time observations, not a
calendar executor, reader-facts interface, new planner loop, or semantic completion oracle.
The last full-suite result remains **4/20 combined, 9/20 independent state**. Improvement is
unmeasured; neither 13/20 nor successful calendar completion is claimed.

## Evidence and primary sources inspected before implementation

The ignored recording is
`/home/wakeel/Desktop/ballistic/ballistic_runs/core4_20261107/20261107-025036_SimpleCalendarAddOneEvent.json`,
SHA-256 `1fe303a34ba1b8cd0d7366edbb25af72240b649494b4e1ca10dc77e1b49c3f78`.
Its request specifies October 27, 2023, 13:00, and 15 minutes. The form initially displays
`October 15 (Sun)` and `16:00` in both endpoints. After changing the date, it displays
`October 27 (Fri)` with both times still `16:00`. The saved item's dedicated time is `16:00`.
The recorded verifier cites `e17` and justifies success from the title and description without
covering the requested time/duration. This is both missing observation and incomplete reasoning.
Showing values does not itself repair the reasoning failure.

AndroidWorld's [app setup source](https://github.com/google-research/android_world/blob/55ddaf5edc6f6c25673aed343907d5b9dca66f84/android_world/env/setup_device/apps.py#L317)
requests `com.simplemobiletools.calendar.pro_238.apk`. Upstream tag **6.22.2**, commit
`f8959bcfd437adee2f89c2abb1272e680ed9c697`, declares versionCode 238 in
[app/build.gradle](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/build.gradle).
This maps the source version; it is not a measurement of the installed APK or its hash.

Primary source grammar:

- [EventActivity](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/src/main/kotlin/com/simplemobiletools/calendar/pro/activities/EventActivity.kt#L1392)
  assigns the four dedicated date/time TextViews from Formatter.
- [Formatter](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/src/main/kotlin/com/simplemobiletools/calendar/pro/helpers/Formatter.kt#L12)
  uses `HH:mm` or `hh:mm a`. Dates use localized month names, day, an optional displayed year,
  and abbreviated weekday in parentheses. Its year omission must not be reversed using the
  owner's requested year, host clock, benchmark time, or a neighboring heading.
- [EventListAdapter](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/src/main/kotlin/com/simplemobiletools/calendar/pro/adapters/EventListAdapter.kt#L132)
  assigns time separately from title and description. It can append an end time and a multi-day
  date suffix. Those suffixes are rejected whole, never stripped.
- [activity_event.xml](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/src/main/res/layout/activity_event.xml)
  and [event_list_item.xml](https://github.com/SimpleMobileTools/Simple-Calendar/blob/f8959bcfd437adee2f89c2abb1272e680ed9c697/app/src/main/res/layout/event_list_item.xml)
  establish dedicated resources. Recorded trees establish the host's flattened parent links,
  roles and accessibility classes; XML ancestry alone is not the host-tree envelope.

## Frozen development cases

Before changing implementation, `core/tests/cases/calendar_values.json` was written with 43
lexical cases (14 positive, 29 negative), 23 structural rejection cases, and eight integration
requirements. SHA-256 remains
`e57e6f41b2e97842d3f8234d41e3fc509863f80b909d4eda64f8231ee231a36b`.
These are development cases, not a held-out evaluation or a task score.

They vary months, weekdays, explicit/absent years, leap days, midnight/noon, 12/24-hour readings,
interval endpoints, hostile suffixes, whitespace, Unicode digits, unknown locales, ambiguous
numeric dates, impossible dates/times, optional descriptions, and package/page/role/parent spoofs.
The integration tests also exercise folding, content isolation, raw reader freshness and scripted
act/verifier calls. The button-shaped row regression discovered during raw comparison was added
as an additional test without rewriting the frozen case file.

## Implementation and trust limits

`core/src/typed_value.rs` is an internal module. Its types describe date components, clock time,
and two clock endpoints. There is no ScreenState, action schema, host port, or UniFFI change.
`screen_filter.rs` carries these observations separately from labels, matched content, typed
history and reader picks. No Settings logic, known-control definitions, or Markor FileKind
behavior changes.

Recognition requires the exact calendar package and activity, exact resource ID, Text role,
TextView class, compatible flags, visible nonempty bounds, and visible same-package ancestors.
The form requires the raw ScrollView parent `event_nested_scrollview` under `event_coordinator`.
The list requires a non-clickable time TextView under an explicit Item `event_item_holder`, under
`calendar_events_list` with RecyclerView class in MainActivity. Duplicate node IDs or duplicate
value slots under one parent reject observations. A list observation is emitted only if folding
attaches it to that exact item; large/unfolded rows cannot transfer data to an unrelated element.

Accepted lexical forms are complete ASCII `HH:mm`, complete English `hh:mm AM/PM`, two readings
of the same format separated by ` - `, and English month/day/optional-four-digit-year/weekday
forms. Component bounds, Gregorian month lengths, leap years and explicit-year weekday
consistency are checked. February 29 without a year remains a possible partial date; it proves
no particular year. Mixed interval formats, unsupported locale forms, extra text, descriptions,
whitespace, all-day labels and multi-day suffixes yield no typed observation. No inference is
made about time zone, date association, overnight duration or missing year.

Any content description on a dedicated value node, including an empty description, rejects its
value extraction. Its complete original label/text bytes remain reader content. Value slots
never become matched titles or typed-history values, even when their numbers match the request.
Titles and optional descriptions are never parsed for values or shortened into matches.
Their exact calendar item resources remain content through folding, including button-shaped
rows; whole owner-matched item strings retain the existing match-only behavior.

Each observation retains `Source::App` and the original raw node ID. The reader still receives
the original withheld strings, in order. Changed spelling/whitespace invalidates the existing
reader pick even if two spellings could describe the same clock time. Numeric metadata alone
cannot identify a row, enable a tap/long press, create a reader pick, or qualify an otherwise
opaque verifier citation. Existing code-owned form labels retain their existing control identity.
Neither metadata nor source tags grant an operation or upgrade evidence above model assessment.
Full action-argument provenance/taint enforcement remains unfinished as documented in core's
existing design; this change does not claim to implement it.

These envelopes assume trustworthy host observations and the identified app's format. Package
and resource IDs are not cryptographic proof, ScreenState has no app-version or locale field,
and this does not detect dishonest hosts or same-activity stale accessibility captures.
Unsupported structures fail closed for typed observations. Date/time picker internals, other
calendar apps, and other locales are outside this patch.

## Parent/final measurement on the same raw observations

Both builds were run only with `ballistic-core filter --request <recorded goal>`, feeding each
recorded `reply.data` ScreenState. Typed history was empty in both. All 13 recorded observations
were included; numbers below count node occurrences across snapshots, not unique events or
successful tasks. JSON `lines` indices are zero-based.

- Form snapshots at indices 22, 29, 36, 57, 64 and 71 contain 24 dedicated values. Parent exposes
  **0/24 values**; final exposes **24/24 numeric observations**, preserving all 12 dates as
  year-unknown. The 12 time observations are 16:00, never inferred to be the requested 13:00.
- Post-save list snapshots at 78 and 82 contain 16 dedicated item times. Parent exposes **0/16**;
  final exposes **16/16** attached numeric observations. The requested item's time appears twice,
  both as hour=16/minute=0. It supplies no date, end time, or duration assertion.
- Early list snapshots at 8 and 15 contain 14 times on button-shaped rows. Parent incorrectly
  folds those times plus unrelated titles/descriptions into button labels. Final emits **0/14
  typed observations** for these unsupported row roles and keeps all their strings as content.
  This prevents removing a time from a truncated label from exposing additional description text.
- The remaining three snapshots contain no dedicated values. Across all 54 raw slots, parent
  has zero structured observations (14 raw strings leaked through generic labels); final has
  **40 structured observations**, zero raw value strings promoted to labels, and 14 conservatively
  withheld unsupported values. No unrelated title or description gains visibility.

The outputs used for comparison are local temporary artifacts under
`/tmp/ballistic-calendar-observations/`; the parent binary was built from unmodified `a89e2fe`
and saved at `/tmp/ballistic-calendar-parent`. They are not required for the committed tests.
To reproduce, build the parent and this commit's `ballistic-core` separately, select each
`lines` entry whose `data` is a ScreenState with `nodes`, and pipe that data to each binary's
`filter` mode with the recorded goal. Count `[observed ` annotations and inspect the four form
controls and each `event_item_holder`. This is a filter comparison, not a replay of new model
choices, app actions or verification outcomes.

## Validation

`cargo test --offline --manifest-path core/Cargo.toml --features uniffi`: **124 passed, zero
failed, zero ignored**, including 12 new integration tests and the existing Markor, known-control,
launch-completion, reader, action and verifier regressions. Scripted protocol tests establish:

- A fresh verifier prompt receives the newly sampled numeric time; a numeric-only row's
  fabricated `verified` citation is rejected and dispatches no action.
- A numeric-only row tap, even with the time in the owner request, dispatches no action.
- An existing named form control still dispatches exactly one granted tap; values do not
  change its action or completion eligibility.

The verifier implementation and planner loop are unchanged. A known label or owner-matched
saved title can still make a citation eligible for model assessment without code proving that
all requested postconditions hold. The recorded false completion is therefore not claimed fixed.
No model, SSH, ADB, emulator, physical phone, or ballistic-os operation was used. Public source
files were fetched for grammar research; builds and tests used offline dependencies. No push or
merge was performed. `docs/CONTEXT.md` was not edited; parent integration/review remains pending.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
