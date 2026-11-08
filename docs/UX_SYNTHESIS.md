# Ballistic app UX synthesis: designer studies + required functionality

Draft, 8 November 2026, revised the same day after owner feedback (raw greyscale direction,
feature removal). **This is the coding agent's (Claude's) proposal**, merged from the designer's
studies in `design/` and checked against SPEC.md, docs/CONTEXT.md (7–8 Nov sections), the
current Compose code and the dated phone logs. It is not the designer's own design and not a
decided design. Nothing here is implemented, and no app code was changed. Statements marked
**(inferred)** are my reading, not a recorded decision. Owner questions are in §8.

Visual companion: [design/ballistic-merged-proposal.html](../design/ballistic-merged-proposal.html)
(static wireframes, sample data only; open with `#s1`…`#s13` or `#palette` for one panel).
Screenshots are local and git-ignored: the designer studies in `ballistic_runs/ux_review_20261108/`,
the revised proposal in `ballistic_runs/ux_review_20261108/raw/` (`raw-1400.png`,
`raw-1400-part1..4.png`, `raw-420-palette.png`, `raw-420-s1.png` … `raw-420-s13.png`).

### Owner feedback applied in this revision

1. The wallet feature is removed from the product. Every wallet screen, sample, mention and
   implementation slice is gone from this document and the HTML proposal. (The earlier draft had
   read it from an old checkpoint; it is not in the current repo.)
2. "Too neat and modern; more raw. I like the info it shows and the options we need." The
   information architecture, states and functions are kept; the visual language is rewritten
   (§5): flat, plain, dense, system UI / terminal-adjacent.
3. "Grey out the app, in line with GrapheneOS branding somehow; I love the contrast of grey and
   one small colourful thing." Greyscale palette with one accent and one rule for it (§5.2).
   GrapheneOS's own site was used as a style reference only (§5.1); no name, logo or
   affiliation is borrowed.

## 1. What the designer's work gets right

The designer produced four studies, each superseding the last:

| File | What it is | Screenshots |
|---|---|---|
| `ballistic-prototype.html` | Dark "interaction study 01": Ask, working + Stop, approval, outcomes, Local AI, setup | `designer-prototype-1400.png`, `-run.png`, `-approval.png`, `-activity.png`, `-localai.png` |
| `ballistic-directions.html` | Three layouts of the same demo: A Utility, B Instrument, C Notebook | `designer-directions-1400.png` |
| `ballistic-refined.html` | Light "refined utility", 4 bottom tabs, metric tables | `designer-refined-1400.png`, `-running.png`, `-localai.png`, `-settings.png` |
| `ballistic-raw-review.html` | Latest: plain top tabs, Settings behind a gear, feature keep/remove table | `designer-raw-review-1400.png`, `designer-raw-running.png`, `-trace.png`, `-localai.png`, `-settings.png`, `-activity.png`, `-full.png` |
| `current-app-audit.json` | Inventory of every current screen feature and metric | — |

Worth keeping:

1. **Status semantics, not the palettes.** The designer's graphite/mint and paper/green
   palettes are superseded by the owner's greyscale direction (§5). What survives is the
   semantic split they encoded: "ready / acting" versus "a decision or an uncertain result".
   It is now carried by label style and inversion instead of green and amber.
2. **Honest status labels.** The prototype's small tags ("Approval required", "Couldn't
   verify", "Completed") are the right component for run outcomes and source labels; they
   become outlined monospace labels. The "On-device" header pill is the right place for the
   SPEC §6 "visible indicator whenever a payload leaves the phone".
3. **Approval card** (`designer-prototype-approval.png`): recipient as a heading, the exact
   message as large quoted text, "Recipient and text: supplied by you", Deny/Approve side by
   side, "Your fingerprint authorizes this exact action. The assistant cannot approve it."
   This is the best screen in the set; it reads like a decision, not a form.
4. **Activity cards** (`designer-prototype-activity.png`): status + time, request as title,
   one-line outcome, "What happened" disclosure, and "Sensitive message content stays hidden
   until you choose to reveal it." This is the collapsed-run entry the owner asked for.
5. **Request detail / trace** (`designer-raw-trace.png`): Result / Elapsed / Evidence key-value
   rows, then numbered steps with Why and outcome, then "Raw record" disclosure. Good model for
   the dropdown under a running or finished task.
6. **Key-value metric rows** (`designer-refined-localai.png`, `designer-raw-localai.png`):
   aligned label/value rows, "Not measured" instead of invented numbers, monospace only for
   measurements and raw records, technical detail behind disclosures. Good for Local AI.
7. **Restraint and honesty in the later studies.** The raw review explicitly removes
   marketing headlines, fake gauges and decorative cards, keeps every existing capability, and
   writes "What this build can do: tapping, typing and alarm creation are not supported".
   The refined study labels GPU/NPU as "Present · Not used by runtime". Keep this discipline;
   the owner's "more raw" direction pushes further along the raw review's line.
8. **Navigation simplification.** Three primary destinations with Settings behind a gear
   (raw review) is less crowded than the current four equal tabs.
9. **Composer at the bottom** (directions C, raw review) for thumb reach; Enter submits,
   Shift+Enter adds a line (refined).
10. **Brand mark.** `assets/brand/ballistic-logo-v1.png`: ivory geometric B on graphite with a
    mint chevron, legible small. The mint chevron is the natural source of the single accent
    (§5.2); whether the logo itself goes monochrome is question 18.

## 2. Gaps and contradictions

### 2.1 Required functionality the designs miss

| Need | Designer coverage | Gap |
|---|---|---|
| Running task as one or two lines + dropdown | Prototype shows a large "On it." card; refined shows a 3-line block; raw review has "Now: planning · 0:08 elapsed" + "Steps so far" disclosure | Raw review is closest, but the line is internal jargon ("planning"), there is no Continue in background, no approval-waiting state, no model-wait line, and the run is detached from the request history |
| Stop always reachable | Present while the Ask tab is visible | Not reachable from other tabs; no persistent bar; notification Stop not shown |
| Continue in background | Missing in all studies | Implemented in the app (8 Nov); needs a place |
| **Chat** (multi-turn, tasks from chat) | Absent: every study is a single-request task box | Central design gap; see §4.2 |
| Confirmation: every value with its source | Prototype shows one "supplied by you" line; raw review placeholder "Show each value and whether it came from your request, your data, or external content" | No design for an untrusted source (e.g. "number came from an SMS by an unknown sender"), no exact-operation block, no "why core asks", no soft-vs-hard distinction, no "agent cannot tap this" explanation of the window itself |
| Command access (phone unlock authorizes; manual lock; status) | Prototype: "Sample session unlocked"; raw review: "Authenticated / Lock session" | Raw review describes the **superseded** model ("Leaving the app or turning off the screen locks the session", "biometric authentication unlocks requests"). Since 8 Nov the phone's secure unlock authorizes requests, switching apps keeps access, manual lock persists |
| Visible indicator whenever a request leaves the phone | "● On-device" pill (prototype); "Connected model … may leave this device" text in raw review | No design for the connected/leaving state of the pill, or for per-request marking in history |
| No silent cloud fallback; explicit local vs connected | Mentioned in notes | Raw review moves "Run AI on" into Settings, separating the choice from the model it applies to (**inferred** to be a regression) |
| Model choice (catalogue, runtime, backend) | Refined study: "Download another model"; raw review defers it | The app now has a Models dropdown over a pinned 7-entry catalogue with llama.cpp and LiteRT-LM runtimes and owner-chosen CPU/GPU/NPU backend (merged 8 Nov, `apps/assistant/LOCAL_AI.md`). No study shows it |
| Per-app rules separate from Android permissions; biometric to save | Raw review: "Per-app policy · Clock · Change observe/act/route/confirm" | No app list/search, no "new apps start blocked", no "Save with fingerprint", no link-out explanation, uses internal words observe/act/route |
| Activity: what was read, what was done, approvals | Outcome + steps only | No "read" or "approvals" sections; the current log does not record observations or approval decisions as separate records either (**inferred** from `ActionLog`/`StepEvent`) |
| Capability honesty (what it can/can't do, blocked apps) | "What this build can do" disclosure (raw review) | Not tied to actual grants; no unsupported-request result state; no blocked-app state |
| First run / onboarding | Absent | Needs phone-lock check, AI location choice, model setup, optional app grants and notifications |
| Errors: offline, no model, model failed to load, demo engine | Partly: "Load your model in Local AI" toast | No designed states; the 7 Nov phone failure (no internet, looked like "nothing happens") is exactly this case |
| Voice (later) | Absent | Reserve a place, do not show a dead mic |

### 2.2 Claims beyond current capability

- **"Draft a message to Alex" sample task and "Ready to send?" approval** (prototype): the APK
  cannot send messages; IntentHands exposes only `open_app` and `wait`. Sample tasks must come
  from what the build advertises. The approval screen itself is fine as a component.
- **"Power on your phone. Control in your hands." / "Your phone, working for you" / "What can I
  take care of?"** imply general phone control. Ballistic cannot operate other apps' screens
  yet (no Computer Control). The prototype's marketing headlines should not ship.
- **"Your phone. Your intelligence." / "Example phone · 8 GB RAM"** (prototype Local AI):
  marketing tone over sample hardware values.
- **"Model requests stay on your device. Sensitive actions wait for your approval."** True
  only when local inference is selected, and approval is implemented but not device-validated.
  Safe wording: "AI runs on this phone" (state), not a promise.
- **"Download another model"** (refined): now real in a different form (the catalogue
  dropdown). Its entries are pinned downloads, not a store; no quality or speed ranking exists,
  and no NPU, GPU or phone LiteRT-LM result exists yet (LOCAL_AI.md, 8 Nov). The UI must not
  imply a recommended "fastest" model.
- **"Hardware in use: GPU Present / NPU Not reported"** (refined): no device accelerator
  inventory exists. The app now reports "Running on: runtime + backend the engine was started
  on", which is the runtime's acceptance of that backend, not a hardware trace. Show that line;
  omit an inventory.
- **"Home GPU · selected remote model"** (raw review script): the home GPU route is parked.
- **"7-day filter, request grouping"**: grouping now exists (8 Nov); the time filter is proposed.
- **Logo prompt text** (`ballistic-logo-v1.prompt.txt`) says "built on GrapheneOS" and refers
  to graphene. Fine as provenance of the image; must not become product copy (CLAUDE.md:
  no implied GrapheneOS endorsement, no "Graphene" in product names).

## 3. Current app UX problems (code + phone observations)

From `MainActivity.kt`, `ui/*.kt`, `ConfirmActivity.kt`, `CommandGate.kt`, `PromptSession.kt`
and the 7–8 Nov logs:

1. **Ask top is crowded with explanations.** Model line, "Requests enabled" + Lock button and a
   permanent paragraph about phone unlock sit above the conversation on every visit
   (`PromptScreen.kt`). The paragraph is the same text as in Settings and Local AI.
2. **Running task is six timestamped log lines**, plus a "Current update: 0:12 ago" line,
   plus Elapsed (RunCard). The owner asked for one or two lines. The lines are internal
   ("Waiting for planner model", "Planner reply received; checking response", "Step 1:
   open_app → …"). (A compact Ask card was merged on 8 Nov; this proposal's two-line card is
   the target shape for it.)
3. **Stop replaces Run** in the composer row, and is only on Ask. Leaving Ask hides it
   (notification Stop exists only when notifications are enabled, which they were not on the
   test phone).
4. **Previous displayed requests look like a conversation but are not one** (diagnosis log):
   each request is a fresh task; ordinary questions fail with "Not supported yet" because core
   returns unsupported for `answer`. To an owner this reads as a broken chat.
5. **Result wording is honest but long and technical** ("Opened the app; the task itself is not
   confirmed"; "Unresolved: input was accepted, the effect is not confirmed"). Keep the
   honesty, shorten the headline, put evidence in details.
6. **Four equal tabs** (Ask, Local AI, Activity, Settings) as text buttons at the top;
   no status. Local AI is a setup screen most visits do not need.
7. **Local AI is a long single column**: route chips, session access, model card, storage,
   load/unload, speed, suggested settings, memory, technical details. Primary state ("Is my
   model ready?") and primary action are not separated from diagnostics.
8. **Setup failure looked like "nothing happened"** (7 Nov phone log: no internet; GiB counter
   stuck at 0.0). Fixed partly (MB/percent, adjacent errors); offline remains an undesigned state.
9. **Settings mixes five concerns** in one list: session, phone permissions, task
   notifications, engine, connected model, notification access, capability text, app rules,
   debug import. App rules are at the bottom of a long scroll.
10. **App rule labels are internal** ("Read / Actions / Model / Approval" with values like
    "Selected backend; may leave device"). Better than before, but each row needs a one-line
    plain summary and the save step needs to say "fingerprint".
11. **Activity hides the request text by default** ("Request hidden"), which makes collapsed
    cards unidentifiable. The designer's choice (show the request, hide typed values/content)
    is more useful (**inferred**; question 9).
12. **Confirmation window is a plain form** (labels "App / Action / Target / Exact operation
    (JSON) / Values / Why core asks"). Correct content, weak hierarchy: the exact JSON is the
    most prominent block; untrusted sources are only bold text.
13. **No first-run flow.** The app opens on Ask with a "What you can test" card; the
    notification permission prompt fires on first launch without context (`MainActivity`).
14. **Theme is Material default** (`Theme.kt`: "Styling comes later"; default
    `darkColorScheme()` / `lightColorScheme()`, purple baseline). §6 gives replacement tokens.
15. **Route indicator is a sentence**, not a persistent visible indicator, and history does not
    record which route each run used in the UI.

## 4. Merged design

Principles: the designer's structure and restraint; the app's functional honesty; owner's
8 Nov priorities (compact progress, background work, chat) and the raw greyscale look (§5).
One brand, "Ballistic".

### 4.1 Navigation model

```
┌──────────────────────────────────┐
│ [B] Ballistic   [ON THIS PHONE] ⚙│  top bar: grey logo, name, route label, gear → Settings
├──────────────────────────────────┤  (route label inverts when requests leave the phone;
│                                  │   "REQUESTS LOCKED" inverted label only when locked)
│          screen content          │
│                                  │
├──────────────────────────────────┤
│ ■ Opening Clock · 0:42      Stop │  task bar: on every tab while a task runs (■ = live marker)
├──────────────────────────────────┤
│   Ask        Activity   Local AI │  bottom navigation, 3 text tabs
└──────────────────────────────────┘
```

- **Three destinations**: Ask, Activity, Local AI. Settings behind the gear (designer raw
  review). Bottom navigation rather than top tabs, so the thumb reaches tabs and the composer
  together (question 6). Text-only tabs; the active tab has a 2dp top rule and bold label.
- **Route label** (always visible): outlined `ON THIS PHONE` when the selected backend is
  local; inverted `LEAVES PHONE ›` when connected. Tap opens a sheet: what is sent, to whom,
  how to switch. Per-app device-only rules still apply. This is the SPEC §6 indicator, carried
  by inversion rather than colour (§5.3).
- **Request access**: no label when requests are allowed (the normal state). When manually
  locked or the phone is locked: inverted `REQUESTS LOCKED`, tap to enable. Lock lives in
  Settings and in the Ask overflow menu.
- **Task bar**: while a task runs, a one-line bar sits above the navigation on every tab with
  the live marker, current step, elapsed time and Stop. Tapping it jumps to the task in Ask.

### 4.2 Ask: chat and tasks

**Central decision (question 1).** Options:

- **A. One thread, Ballistic decides** (recommended). Ask is a conversation. Each owner message
  is answered either as chat (text reply, no phone actions) or as a phone task (task box in
  the thread with Stop and details). Follow-ups ("now set it to 8") refer to earlier turns.
  When the router is unsure it asks: "Do this on your phone? Open Clock — Yes / Just answer".
- **B. One thread with an explicit switch** in the composer: "Chat" vs "Do on phone". Most
  predictable with small local models; one more control, and owners will forget the mode.
- **C. Two tabs**, Chat and Tasks. Clear separation, but loses the "set it to 8" continuity
  across tabs and duplicates composers.

Recommendation: **A**, with three guard rails: every task is visibly a task box (never a plain
reply), the router can fall back to asking, and the owner can force either path from the
message's long-press menu ("Run as phone task" / "Answer only") (**inferred** guard rails).

Safety implications (**inferred**, to confirm in core design):
- Chat replies are tool-free. A chat turn cannot cause a phone action.
- History passed to a task planner contains the owner's own messages verbatim and structured
  task outcomes, not assistant prose that may quote untrusted content. A value taken from
  another app's content keeps its source tag across turns, so "send it to that number" still
  hits a hard confirmation naming the source.
- Chat answers about another app's content are the reader's source-bound display output
  (SPEC §7.3 owner-display clarification), never planner prose.
- Chat history is local, in app-private storage, excluded from backup like the other files;
  "Clear conversation" in the overflow menu.

Layout:

```
┌──────────────────────────────────┐
│ [B] Ballistic   [ON THIS PHONE] ⚙│
├──────────────────────────────────┤
│ › What's 15% of 64?              │  owner turn: prompt line, no bubble
│   9.6                            │  reply: plain text, indented
│   on this phone · no actions     │  mono meta line (route)
│ › Open the clock                 │
│ ┌──────────────────────────────┐ │
│ │ PHONE TASK            ■ 0:42 │ │  1px ruled box; ■ live marker (the only colour)
│ │ Opening Clock                │ │  line 1: current step, plain words
│ │ Thinking on this phone · 12 s│ │  line 2: sub-state (optional)
│ │ [Stop] [Continue in backgr.] │ │  outlined rectangles
│ │ ▸ Details · 2 steps          │ │  dropdown
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│ [› Message Ballistic      Send ] │  composer (disabled + "Task running" while busy)
├──────────────────────────────────┤
│   Ask        Activity   Local AI │
└──────────────────────────────────┘
```

Components: owner turn and reply (new); task box = prototype card structure + raw-review
"Now: … · elapsed" line + "Steps so far" disclosure, redrawn as a ruled box; composer =
raw-review pinned bottom composer with a plain outlined Send.

**Task box states and copy**

Label style: *outlined* = ordinary state; *inverted* = needs the owner or may need checking
(§5.3); *live* = mint marker, only while Ballistic is acting.

| State | Line 1 (bold) | Line 2 (small) | Controls | Label |
|---|---|---|---|---|
| Starting | "Starting" | "Request accepted · 0:01" | Stop | live |
| Reading | "Checking what's available" | "Notifications from allowed apps" | Stop | live |
| Model wait | "Thinking on this phone" or "Waiting for connected model" | "12 s on this step" | Stop | live |
| Acting | "Opening Clock" (action in words) | "Step 2" | Stop | live |
| Awaiting approval | "Waiting for your approval" | — | Review, Stop | inverted NEEDS YOU (no live marker: paused on the owner) |
| Stop requested | "Stopping…" | "Finishing the current step" | — | live |
| Finished, verified | "Done: Clock opened" | "Checked by: visible screen" | ▾ Details | outlined DONE |
| Opened, not verified | "Opened Clock" | "Couldn't confirm it is on screen" | ▾ Details | outlined NOT CONFIRMED |
| Unresolved | "May or may not have happened" | "Check before trying again" | ▾ Details | inverted UNRESOLVED |
| Unsupported | "I can't do that on this phone yet" | "Setting alarms isn't available. See what I can do" | link | outlined NOT SUPPORTED |
| Blocked by rule | "Clock isn't allowed" | "Allow it in Settings → App rules" | link | outlined BLOCKED |
| Stopped | "Stopped" | "Nothing was done" or "Earlier steps may have taken effect" | ▾ Details | outlined STOPPED |
| Error | "Something went wrong" | short reason ("Model not loaded", "No internet") + action | Retry / Local AI | inverted ERROR |
| Interrupted | "Interrupted when the app was closed" | "It won't restart by itself" | ▾ Details | outlined INTERRUPTED |

The progress words map from existing events (`UniffiCoreEngine.onActivity`, `StepEvent`):
"Reading available screen information" → "Checking what's available"; "Waiting for planner
model" → "Thinking on this phone" / "Waiting for connected model"; "Dispatching an action" →
the action in words (`actionLabel` rewritten as "Opening Clock"); "awaiting approval" →
"Waiting for your approval". Elapsed is the existing monotonic timer.

**Details dropdown** (designer raw-review trace): Result / Elapsed / Checked by rows; numbered
steps each with "what I did", "why", "outcome"; model waits as sub-rows ("Model · 3.1 s ·
on this phone"); "What I read" (sources, not content unless revealed); approvals ("You
approved with fingerprint at 14:02"); "Raw record" disclosure in monospace. Finished task
boxes collapse automatically; past tasks stay collapsed in the thread.

**Ask idle / empty states**

| State | What shows |
|---|---|
| First idle, ready | No logo watermark, no headline. One line near the composer: "Ask a question, or ask me to do something on this phone." Up to three suggestion rows (› Open Clock) built only from current capabilities ("Open Clock" only if Clock has Open app allowed; "Check local AI speed"). Link "What I can do here". |
| No model | Outlined SET UP AI FIRST: "No model on this phone. Download the starter (639 MB) or pick another in Local AI." [Set up AI]; composer disabled with placeholder "Set up AI to start". |
| Model unloaded | One row: NOT LOADED "Model isn't loaded" [Load]; no navigation needed. |
| Requests locked | Composer placeholder "Requests are locked"; inline [Enable requests]. Submitting while the phone is locked prompts the system unlock (existing behaviour). |
| Offline + connected model | Inverted NO INTERNET: "The connected AI can't be reached. I won't switch AI by myself." [Use on-phone AI] [Retry]. Never switch automatically. |
| Demo engine | Full-width inverted bar under the top bar: "DEMO ENGINE · NOT REAL" (existing FakeCoreEngine warning). |
| Chat not yet available (before core chat slice) | Ordinary questions get: "I can't answer general questions yet. I can open allowed apps." (Replaces today's "Not supported yet".) |

### 4.3 Confirmation window

Separate secure window (existing `ConfirmActivity`; FLAG_SECURE, overlays hidden, input
filter, Back denies). Restyled from the prototype approval card. Layout:

```
┌──────────────────────────────────┐
│ APPROVAL WINDOW · CAPTURE BLOCKED│  inverted strip
│ [NEEDS YOUR FINGERPRINT]         │  outlined; "NEEDS YOUR OK" for soft
│ Send a text message?             │  verb as a question, from operation
│ App   Messages                   │  key-value rows
│ To    Ali Hassan                 │  resolved target, not label
│       +44 7700 900123            │  mono
│ ┃[! NOT FROM YOU] Number taken   │  inverted label + 3dp rule + bold:
│ ┃ from an SMS by an unknown      │  untrusted source, listed first
│ ┃ sender.                        │
│──────────────────────────────────│
│ TEXT                             │
│ "Running late, 10 min"           │  each value, large
│ source: your request             │  owner source: quiet mono line
│ Why I'm asking: …                │
│ ▸ Exact operation (raw)          │  monospace JSON, collapsed
│                                  │
│ [   Deny   ] [ Approve with fp. ]│  same size; Approve 2dp outline, bold; no colour
│ Only your finger on this screen  │
│ can approve. Ballistic's agent   │
│ can't press these buttons.       │
└──────────────────────────────────┘
```

- Sources (from `sourceText`): owner sources are a quiet mono line ("source: your request",
  "source: your contacts", "source: a button label"); untrusted sources use the warning form
  (inverted `! NOT FROM YOU` + bold sentence naming the source: "From Messages content",
  "From an SMS by +44…", "From a web page") and are listed first.
- Hierarchy rule: the loudest element on this screen is the reason to hesitate, never the
  Approve button. Deny and Approve have equal size; nothing in the window is coloured (the task
  is paused, so there is no live marker either).
- States: soft (Approve), hard (Approve with fingerprint; no PIN fallback), no strong
  biometric enrolled ("This needs a fingerprint. Deny, or enrol one in phone settings."),
  ignored injected input ("Ignored input that didn't come from the screen"), nothing pending
  ("Nothing is waiting for approval" + Close), changed/expired ("This request changed;
  approval cancelled").
- The task box in Ask shows "Waiting for your approval" + Review; a notification also
  reopens the window. Approval never happens on returning from background.
- Designer component reused: prototype approval card structure, Deny/Approve row and its
  footer copy, raw-review "Exact operation" monospace block.

### 4.4 Local AI

```
┌──────────────────────────────────┐
│ WHERE AI RUNS                    │
│ (•) On this phone                │  radio rows; explicit, no fallback
│     Prompts and screen info stay │
│ ( ) Connected server             │
│ MODEL                            │
│ [Qwen3 0.6B Q8  llama.cpp·CPU  ▾]│  dropdown over the shipped catalogue
│   Qwen3 0.6B Q8  639 MB  IN USE  │  each row: name; runtime · backend · size (mono);
│   Qwen3 1.7B Q8  1.8 GB DOWNLOAD │  status (in use / downloaded / download /
│   Gemma 4 E2B Tensor G5 … NPU    │  import only for gated entries)
│ Running on   llama.cpp · CPU     │  runtime + backend actually started
│ Short check  0.4 s · 21.8 tok/s  │  key-value rows, mono
│ ~2K check    Not measured        │
│ [Unload] [Check speed] [~2K]     │
│ ▸ Storage · import verified file │  storage used/free, delete
│ ▸ Technical details              │  context, threads, metadata, memory, licences
└──────────────────────────────────┘
```

States: no model ("Download the starter · 639 MB · Qwen3 0.6B, Apache-2.0" [Download & set up]
[Import a verified file]); downloading ("1,058 / 3,206 MB · 33 %", Stop, "You can leave the
app"); verifying; loading; ready; generating; backend chips under an entry only where the
catalogue offers a choice (generic LiteRT-LM bundle: GPU / CPU; Tensor G5 bundle: NPU only;
GGUF: CPU only); NPU unavailable ("Tensor accelerator not available on this phone/OS: <reason>",
inverted ERROR, no fallback); gated entry ("Import only: download it on a computer, copy it to
the phone, then Import verified file"); error adjacent to the control ("No internet connection.
Connect to Wi-Fi and try again."; "Not enough storage: needs 0.7 GB"); connected selected
(inverted LEAVES PHONE with host, "Prompts and screen information are sent to
generativelanguage.googleapis.com", [Connection settings]); connected but not configured ("Add a
server in Settings first", Connected row disabled until configured); busy run ("Finish or stop
the task before changing models").

Reuses: refined/raw-review metric rows and disclosures; raw-review "Download starter"
confirmation content. Drops: hardware inventory, marketing headline, any "recommended/fastest"
ranking of catalogue entries (no phone measurement supports one yet). Keeps the route selector
here (question 7).

### 4.5 Settings (gear)

Plain grouped list, each row opens a sub-page:

1. **Requests** — status "Allowed while your phone is unlocked" / "Locked by you"; [Lock
   requests] / [Enable requests]; one sentence: "Your phone unlock lets you give requests.
   Sensitive actions still need your fingerprint."
2. **App rules** — searchable list of installed apps; each row: name + one-line summary
   ("Can open · reads nothing · on this phone only"). Default for new apps: "Blocked". Expanded
   row: Read (Nothing / Notifications), Act (Nothing / Open the app), AI route (This phone only
   / Selected AI), Approval (Sensitive actions / Every action / Never act). Button: **Save
   with fingerprint**. Note: "These rules work inside Android's permissions. They can't
   grant access Android hasn't given." Legacy values shown as "(old rule; no extra access)".
3. **Phone permissions** — [Open Android settings for Ballistic], notification-access status
   + [Open], task notifications status + [Open]; a plain-text note about the OS Network
   permission (no OS product naming in the UI).
4. **Connected AI** — server address, model, API key (stored/replace/forget). "Used only
   when you choose Connected in Local AI."
5. **What Ballistic can do here** — the capability page (§4.7).
6. **About & diagnostics** — engine (real/demo), build, runtime, licences, debug import (debug
   builds only).

### 4.6 Activity

Per-task entries, newest first, as a flat list with hairline dividers:

```
┌──────────────────────────────────┐
│ [NOT CONFIRMED]           09:38  │
│ Open Clock                       │  request text shown; typed values hidden
│ Opened Clock · 2 steps · 0:08 ·  │
│ on this phone                    │  route recorded per run
│ ▾ What happened                  │
│ │ Read: notifications (none)     │
│ │ Did: 1 Opened Clock (Android   │
│ │      accepted the launch)      │
│ │ Approvals: none needed         │
│ │ Checked by: no evidence        │
│ ▸ Raw record                     │
└──────────────────────────────────┘
```

Toolbar: "Show typed text" switch (existing), Refresh. Proposed later: "Last 7 days / All".
States: empty ("No phone tasks yet. Chat messages are kept in Ask."); unreadable lines
("2 records couldn't be read"); in-progress entry with live marker and Stop. Chat turns do not
appear here (question 9). Needs: persisted records for reads and approval decisions (core/log
change).

### 4.7 Capability and unavailable states

"What Ballistic can do on this phone" page, generated from the advertised operations,
policy and model state, never static marketing:

- **Can now**: open apps you've allowed (list); read notifications from apps you've allowed;
  check local AI speed; [after chat slice] answer questions with no phone actions.
- **Not yet**: tap, type or scroll inside other apps ("needs Computer Control, which this
  phone setup doesn't provide"); set alarms; send messages; read another app's screen.
- **Never**: log in, make payments, bypass an app's restrictions, hide from apps.
- **Blocked here**: apps you've blocked; apps that refuse automation ("This app blocks
  assistants. Ballistic leaves it alone.").

Entry points: Ask idle link, unsupported task result, Settings.

### 4.8 First run

Four short screens, skippable after the first; a mono step counter ("SETUP 3 / 4") instead of
progress dots:

1. **Welcome** — "Ballistic is an assistant that runs on your phone. It can open apps you
   allow (and answer questions, once chat ships). Control inside other apps needs platform
   support this phone doesn't provide." [Continue]
2. **Phone lock** — "Requests are allowed only while your phone is unlocked." If no secure
   lock: "Set a screen lock to use Ballistic" [Open security settings].
3. **Where AI runs** — two radio rows: "On this phone (recommended) · download the 639 MB
   starter, other models in Local AI" and "Connected server · prompts leave your phone".
   Choosing local starts the existing consented download; the screen shows progress and may
   be left.
4. **Allow apps (optional)** — "New apps start blocked. Allow Clock to be opened?" quick toggle
   for a few apps; Save with fingerprint. Then **Task notifications** explanation before the
   Android notification prompt (moves the prompt out of `MainActivity.onCreate`).

### 4.9 Later: voice

- **Voice**: mic button appears in the composer only when implemented; a hold-to-talk sheet
  with "Listening… (only while your phone is unlocked)". The wake word only wakes; it never
  authorizes. No placeholder mic before then.

## 5. Visual language: raw greyscale

Replaces the designer's graphite/mint and paper/green palettes. Rendered in
`design/ballistic-merged-proposal.html#palette`.

### 5.1 Reference: GrapheneOS's own visual style

Fetched grapheneos.org and its stylesheet on 8 November 2026. Observed: text-first pages, a
plain top bar of text links, no cards or illustrations; Roboto and Roboto Mono; neutral greys
(page `#FAFAFA` light / `#121212` dark, menu bar `#212121`), body text at 87% black or white,
1px grey rules (`#74777F`, `#8E9099` dark), code blocks in a flat grey; the logo shown
monochrome (inverted) in the menu; one blue for links and the current page; amber `#FCBD00`
and pink-red `#CF6679` for warning/error text in dark mode. Their apps are described by the
owner as stock Material with neutral colours (not checked on the device here).

Taken: neutral greys without a hue cast, Roboto + a monospace, text-first layout, hairline
rules, a monochrome mark, a single accent. Not taken: their blue, their logo, their name. The
accent is Ballistic's own mint from its logo. "Graphene"/"GrapheneOS" never appears in product
names or UI copy, and nothing suggests affiliation or endorsement.

### 5.2 Palette and the one-accent rule

| Token | Dark | Light | Use |
|---|---|---|---|
| bg | `#111111` | `#E4E4E4` | screen background |
| bar | `#181818` | `#ECECEC` | top bar, composer, task bar, bottom nav |
| field | `#202020` | `#F6F6F6` | text fields, open menus |
| divider | `#2E2E2E` | `#C9C9C9` | 1px hairlines between rows |
| outline | `#6B6B6B` | `#7A7A7A` | buttons, boxes, labels (≥3:1 on bg) |
| text2 | `#969696` | `#545454` | secondary text, labels, meta (≈6:1 on bg) |
| text | `#E6E6E6` | `#151515` | primary text; inverted-block background |
| **live** | `#2EF5A0` | `#0A8F58` | **live marker only** |

Contrast (computed, WCAG formula): text on bg 15.1:1 dark / 14.4:1 light; text2 6.4:1 / 6.0:1;
outline 3.5:1 / 3.4:1; live 12.2:1 on dark bg, 3.25:1 on light bg (the light value
is a darker mint so the marker keeps 3:1 as a non-text element). The dark mint is sampled
from the logo chevron (`#27FA9E` in the PNG), slightly softened.

**One-accent rule.** Mint means exactly one thing: *Ballistic is acting on this phone right
now.* It appears only as the live marker, an 8dp square: on the running task box, in the task
bar, on the in-progress Activity entry and as the task notification's accent colour. When
nothing runs, the app has no colour at all, so colour appearing is itself the signal (the same
idea as Android's camera/microphone privacy dot) (**inferred** rationale).

Mint is never used for: Approve, Send or any button; links; "done"/"verified"; "on this
phone"; success; focus or selection. Reasons: colour on Approve nudges toward approving; colour
on "on this phone" or "done" would read as "safe", which the app cannot promise.

Choices considered for the single accent (question 17): route label when on the phone
(always visible, but colour would then mean "safe" and its absence would carry the risk
state); Approve button (rejected for the nudge above); logo chevron in the header (brand, but
no state meaning). Recommended: live marker.

### 5.3 Warnings without warning colours

Amber and red are not used in the app UI. Attention is expressed by **inversion** plus a word
or glyph, weight and a rule, which also satisfies "colour is not the only carrier" (WCAG 1.4.1)
for colour-blind owners:

| Level | Form | Used for |
|---|---|---|
| ordinary state | outlined mono label (`DONE`, `STOPPED`, `NOT CONFIRMED`) | results, model status |
| needs you / may need checking | inverted mono label (`NEEDS YOU`, `UNRESOLVED`, `ERROR`, `NO INTERNET`, `REQUESTS LOCKED`) | states that need the owner |
| untrusted value | inverted `! NOT FROM YOU` + bold sentence naming the source + 3dp left rule in text colour, listed first | approval window |
| leaving the phone | inverted top-bar route `LEAVES PHONE ›` while connected | SPEC §6 indicator |
| demo engine | full-width inverted bar | FakeCoreEngine |

Destructive actions (Delete model, Forget API key) are outlined like any button and confirmed
in a dialog; Stop and Deny are safe actions and need no warning style. Whether a warning colour
should still be allowed for untrusted values is question 19; the default is no.

### 5.4 Type, shape, spacing, components

- **Type.** System sans (Roboto on the phone, `FontFamily.Default`) for words; monospace
  (`FontFamily.Monospace`, tabular figures) for values, times, sizes, status labels, step
  numbers, hosts and raw records. Sizes: approval question 20/26 semibold; task line 1
  15/20 semibold; body 14/20; secondary 12.5/17; mono meta 10.5–12; labels 10/13 mono
  uppercase, letter-spacing 0.06em. Uppercase only for labels and section headings.
- **Shape.** Radius 0 on surfaces, boxes, menus, sheets and dialogs; 2dp on buttons and text
  fields. No pills or circles; the live marker is a square.
- **Lines.** 1dp dividers between rows instead of cards; 1dp outline boxes only for a task or
  a state that must read as one unit; 2dp outline for the emphasised button (Approve, Save
  with fingerprint, Set up AI); 3dp left rule for warnings; 2dp top rule for the active tab.
- **Surfaces.** Flat. No gradients, glow, blur, tonal tint or shadows; elevation 0.
- **Spacing.** 4dp grid: 4 / 8 / 12 / 16. Screen padding 12dp. Dense, left-aligned text.
  Touch targets stay ≥ 48dp even where the drawn rectangle is smaller.
- **Components.** Chat: owner turns as `›` prompt lines, replies as plain indented text with a
  mono route line (no bubbles). Buttons: outlined rectangles or underlined text. Choices: radio
  rows `(•)` / `( )`. Dropdown: field + flat list, current row marked with a 3dp left rule.
  Progress: flat 6dp bar filled with text colour, plus mono "MB / MB · %". Tabs: text only.
- **No decoration.** No illustrations, watermark, decorative icons or emoji. Functional glyphs
  only: gear, back arrow, `▸/▾` disclosure, `›` prompt, `!` warning.
- **Logo.** In the app: header mark rendered greyscale. Launcher icon: see question 18; the
  proposal suggests keeping the mint chevron there with the B moved from warm ivory to neutral
  `#E6E6E6` (redrawn approximation shown in the HTML, **inferred**, not final artwork).

## 6. Compose theme tokens

Directly implementable in `ui/Theme.kt` (Compose BOM 2026.09.00, Material 3 with surface
container roles). Dynamic colour (Material You) off: wallpaper colours would break the
greyscale and one-accent rules.

### 6.1 Colour roles

The accent is deliberately **not** a Material colour role, so no stock component can pick it up
by default. `primary` is the text grey, so default buttons, switches, progress indicators and
focus states come out grey.

| M3 role | Dark | Light | Note |
|---|---|---|---|
| primary | `#E6E6E6` | `#151515` | text grey; emphasised button outline |
| onPrimary | `#111111` | `#E4E4E4` | |
| primaryContainer | `#2E2E2E` | `#C9C9C9` | |
| onPrimaryContainer | `#E6E6E6` | `#151515` | |
| secondary | `#969696` | `#545454` | |
| onSecondary | `#111111` | `#E4E4E4` | |
| secondaryContainer | `#202020` | `#F6F6F6` | selected nav item background, if any |
| onSecondaryContainer | `#E6E6E6` | `#151515` | |
| tertiary | `#969696` | `#545454` | grey on purpose: not the accent |
| onTertiary | `#111111` | `#E4E4E4` | |
| tertiaryContainer | `#202020` | `#F6F6F6` | |
| onTertiaryContainer | `#E6E6E6` | `#151515` | |
| background | `#111111` | `#E4E4E4` | |
| onBackground | `#E6E6E6` | `#151515` | |
| surface | `#111111` | `#E4E4E4` | |
| onSurface | `#E6E6E6` | `#151515` | |
| surfaceVariant | `#202020` | `#F6F6F6` | |
| onSurfaceVariant | `#969696` | `#545454` | secondary text |
| surfaceTint | `#111111` | `#E4E4E4` | equal to surface: no tonal tint |
| surfaceDim | `#111111` | `#DADADA` | |
| surfaceBright | `#262626` | `#F6F6F6` | |
| surfaceContainerLowest | `#0B0B0B` | `#F6F6F6` | |
| surfaceContainerLow | `#141414` | `#EFEFEF` | |
| surfaceContainer | `#181818` | `#ECECEC` | bars, nav, task bar |
| surfaceContainerHigh | `#202020` | `#F6F6F6` | fields, menus |
| surfaceContainerHighest | `#262626` | `#FFFFFF` | |
| inverseSurface | `#E6E6E6` | `#151515` | inverted labels, approval strip, demo bar |
| inverseOnSurface | `#111111` | `#E4E4E4` | |
| inversePrimary | `#111111` | `#E4E4E4` | |
| outline | `#6B6B6B` | `#7A7A7A` | buttons, boxes, labels |
| outlineVariant | `#2E2E2E` | `#C9C9C9` | dividers |
| error | `#E6E6E6` | `#151515` | text grey: errors are carried by label + wording (§5.3) |
| onError | `#111111` | `#E4E4E4` | |
| errorContainer | `#E6E6E6` | `#151515` | inverted |
| onErrorContainer | `#111111` | `#E4E4E4` | |
| scrim | `#000000` | `#000000` | |

Accent, outside the scheme:

```kotlin
@Immutable data class BallisticExtras(val live: Color)
val LocalBallisticExtras = staticCompositionLocalOf { BallisticExtras(live = Color(0xFF2EF5A0)) }
// dark: Color(0xFF2EF5A0)   light: Color(0xFF0A8F58)
// Read only by a single LiveMarker composable (8.dp square) and the task notification's setColor.
```

**(inferred)** `error = onSurface` means stock `TextField` error states lose their red and rely
on supporting text and the error icon; any field that can error should show a sentence, which
the app already does for setup errors.

### 6.2 Shapes

```kotlin
Shapes(
  extraSmall = RoundedCornerShape(2.dp),  // text fields, menus' anchor
  small      = RoundedCornerShape(2.dp),  // buttons where they read the theme
  medium     = RectangleShape,            // cards/boxes
  large      = RectangleShape,            // sheets, nav drawer
  extraLarge = RectangleShape,            // dialogs
)
```

Material 3 `Button`, `OutlinedButton`, `FilterChip` and the navigation-bar indicator use their
own default shapes (full pill) rather than the theme `Shapes`, so wrap them
(`BallisticButton(shape = RoundedCornerShape(2.dp))`) or pass `shape` explicitly; set
`tonalElevation = 0.dp` and `shadowElevation = 0.dp` on `Surface`, and use
`NavigationBar(tonalElevation = 0.dp)` with the indicator colour set to transparent and a 2dp
top rule drawn instead (**inferred** from M3 defaults; check on the BOM in use).

### 6.3 Typography

`FontFamily.Default` (Roboto) unless noted; `Mono = FontFamily.Monospace` with
`fontFeatureSettings = "tnum"`.

| M3 style | Size / line | Weight | Used for |
|---|---|---|---|
| headlineSmall | 20 / 26 sp | SemiBold | approval question, first-run titles |
| titleMedium | 15 / 20 sp | SemiBold | task line 1, list item titles |
| titleSmall | 14 / 20 sp | Medium | top-bar name, section rows |
| bodyLarge | 15 / 22 sp | Normal | chat text, approval values (quoted text 17 sp) |
| bodyMedium | 14 / 20 sp | Normal | default body |
| bodySmall | 12.5 / 17 sp | Normal | secondary lines (onSurfaceVariant) |
| labelLarge | 13 / 18 sp | Medium | button text |
| labelMedium | 12.5 / 16 sp | Medium | nav tabs |
| labelSmall | 10 / 13 sp, Mono, letterSpacing 0.06 em, uppercase in text | Medium | status labels, section headings |

Extra (outside M3 `Typography`, e.g. in `BallisticExtras`): `mono` 12 / 16 sp Mono for values,
times, sizes, hosts; `monoSmall` 10.5 / 14 sp Mono for meta lines and raw records.

Spacing tokens: `space1 = 4.dp`, `space2 = 8.dp`, `space3 = 12.dp` (screen padding),
`space4 = 16.dp`; `hairline = 1.dp`, `emphasis = 2.dp`, `warnRule = 3.dp`; `liveMarker = 8.dp`;
min touch target 48.dp.

## 7. Implementation order

Small slices; each shippable alone. "App" = Kotlin/Compose only; "Core" = Rust core and
UniFFI change.

1. **Theme + shell** (App). Greyscale tokens from §6 in `Theme.kt` (dynamic colour off),
   `LiveMarker`, wrapped buttons, greyscale logo, top bar with route label and gear, 3-tab
   bottom nav, Settings as a pushed screen.
2. **Compact task box** (App, core-api labels). One/two-line current step in plain words,
   elapsed, Stop, Continue in background, dropdown with existing steps/progress, auto-collapse.
3. **Persistent task bar** (App). Running task + live marker + Stop on every tab.
4. **Result wording** (App/core-api `Labels.kt`). Short headline + label, evidence in details;
   unsupported/blocked/error/interrupted states.
5. **Ask states** (App). No model, unloaded, locked, offline, demo engine; capability-derived
   suggestions; idle copy.
6. **Capability page** (App). From `IntentHands.OPERATIONS`, policy and model state.
7. **Confirmation window restyle** (App). Source lines and the untrusted-value warning form,
   hierarchy, collapsed raw operation. Re-run the injected-input checks after any layout
   change; restyle is not validation.
8. **Settings regroup + app-rule summaries** (App).
9. **Local AI reorganisation** (App). Route radio rows, model dropdown restyle, "Running on",
   disclosures.
10. **Activity list** (App), then **read/approval records** (Core + app log): persist what was
    observed (sources only) and approval decisions per run; record route per run.
11. **First run** (App).
12. **Chat, step 1: tool-free conversation** (Core + App). New core entry point for a chat
    turn: owner text + bounded history in, text out, no tools, no screen content; Kotlin thread
    UI and local conversation store. Today `agent.rs` returns unsupported for `Action::Answer`,
    so this needs a separate path, not a planner change.
13. **Chat, step 2: router** (Core). Decide chat vs task per message, with "ask when unsure";
    owner override from long-press.
14. **Chat, step 3: tasks with conversation context** (Core). Planner receives owner turns and
    structured prior outcomes ("it" = the alarm from the previous turn); provenance tags
    survive across turns; tests for injection via earlier turns.
15. **Streaming** (Core/JNI + App). Streamed local generation for chat replies (today the
    native engine returns a whole completion).
16. **Voice** (App + new runtimes), after its own spec's validation.

## 8. Questions for the owner

Ordered by how much the answer changes the design. Each has a recommended default that this
document already assumes. "Answered by implication" marks answers read from the owner's
8 Nov feedback ("keep the info it shows and the options we need"; greyscale); these are
**inferred** and should be corrected if wrong.

1. **Chat and phone tasks: one thread or separate?**
   Options: (A) one thread, Ballistic decides chat vs task, asks when unsure; (B) one thread
   with a Chat / Do-on-phone switch; (C) separate Chat and Tasks tabs.
   **Answered by implication: A** (the owner kept Ask with chat and task boxes), with task boxes
   visually distinct and a long-press override.
2. **What should chat remember, and for how long?**
   Options: (a) current conversation only, cleared manually; (b) last N turns kept across app
   restarts; (c) long-term memory/notes about you.
   **Default: b**, a bounded recent window stored on the phone, "Clear conversation" in menu;
   no long-term profile yet.
3. **May chat use a connected model, or chat only on the phone?**
   Options: (a) same route as tasks (Local AI choice); (b) chat always on the phone;
   (c) per-message choice.
   **Default: a**, with the route line visible on every reply.
4. **How much step detail by default while a task runs?**
   Options: (a) one line (current step + elapsed); (b) two lines (current step + sub-state such
   as model wait); (c) last few events (today's six lines).
   **Answered by implication: b**, everything else in the dropdown.
5. **What does Ask show when idle?**
   Options: (a) empty thread with one plain line and capability-based suggestions; (b) a
   greeting headline like the designer's "What can I take care of?"; (c) last conversation.
   **Default: a** for first use, then (c). Option (b) now conflicts with the raw direction.
6. **Navigation: bottom bar or top tabs?**
   Options: (a) bottom text tabs Ask / Activity / Local AI + gear; (b) top tabs + gear
   (raw review); (c) Ask only, everything else in a menu.
   **Default: a.**
7. **Where does "where AI runs" (on phone vs connected) live?**
   Options: (a) Local AI screen, with the top-bar route label as shortcut; (b) Settings (raw
   review); (c) first-run only, then Settings.
   **Answered by implication: a** (the owner kept Local AI with the model dropdown and the
   on-phone/connected route).
8. **Tone and personality.**
   Options: (a) plain and neutral everywhere ("Opening Clock"); (b) first person in chat and
   task lines ("I'm opening Clock"), neutral in settings; (c) warmer personality ("On it.").
   **Default: b.** The raw direction leans toward (a); still open.
9. **Activity contents and privacy.**
   Options: (a) phone tasks only, request text visible, typed values hidden until revealed;
   (b) tasks and chat, everything hidden until revealed (today); (c) tasks only, all visible.
   **Default: a.**
10. **Light or dark.**
    Options: (a) follow the phone setting; (b) always dark; (c) always light.
    **Partly answered:** the palettes are now greyscale (near-black dark, light-grey light;
    §5.2). **Default: a**, follow the phone.
11. **Brand use.**
    Options: (a) adopt logo v1 as launcher icon and header mark now; (b) header only, keep
    default icon until a final logo; (c) commission a revision.
    **Partly answered:** the header mark is greyscale in the app. Launcher treatment moves to
    question 18. The logo prompt's GrapheneOS/graphene wording stays out of product copy.
12. **Suggestion rows.**
    Options: (a) only for actions currently allowed and supported; (b) none; (c) curated
    examples including future capabilities, labelled "not yet".
    **Default: a.**
13. **Stop reachability.**
    Options: (a) task bar on every tab + notification; (b) Ask + notification only (today);
    (c) also a Quick Settings tile.
    **Answered by implication: a** ("Stop everywhere" kept); (c) remains open for later with
    voice.
14. **First run.**
    Options: (a) four-step setup (welcome, phone lock, where AI runs, allow apps); (b) straight
    to Ask with inline setup cards (today); (c) setup only for AI, the rest later.
    **Answered by implication: a**, every step skippable after the AI choice.
15. **Approval window wording for untrusted values.**
    Options: (a) warning form naming the source ("Number taken from an SMS by an unknown
    sender") listed first; (b) also require ticking "I checked this number" before Approve;
    (c) show only on expansion.
    **Default: a.** Its visual form (inverted label vs a warning colour) depends on question 19.
16. **Model numbers on the main screens.**
    Options: (a) only in Local AI and the task dropdown; (b) a small tokens/s line under each
    task; (c) hidden behind technical details only.
    **Default: a.**
17. **Which single element carries the colour?** (new)
    Options: (a) the live marker only: colour means "Ballistic is acting right now", and an
    idle app is fully grey; (b) the route label while on the phone: always visible, but colour
    reads as "safe"; (c) the Approve button: most visible, but nudges toward approving;
    (d) the header logo chevron, always on: brand only, no state meaning.
    **Default: a.** A combination of (a) with (d) is possible if the owner wants a colour
    visible when idle; then mint means "Ballistic" rather than "acting now".
18. **Should the logo become monochrome?** (new)
    Options: (a) launcher icon keeps the mint chevron with the B moved to neutral grey; the
    in-app header mark is greyscale; (b) fully monochrome everywhere, colour only in the UI;
    (c) keep v1 unchanged (warm ivory + mint) everywhere, including the header; (d) as (a), and
    the header chevron turns mint while a task runs (doubles as a live marker).
    **Default: a.** A final redraw needs the source artwork; the HTML shows an approximation.
19. **Warnings: keep amber/red, or greyscale only?** (new)
    Options: (a) no warning colours: inversion + word/glyph + bold + rule (§5.3); (b) allow
    amber only for untrusted values in the approval window, nowhere else; (c) amber for
    warnings and red for errors app-wide (breaks the one-accent rule).
    **Default: a.** Safety does not depend on colour in either case: the warning must be
    readable without it. Choose (b) if the owner finds the inverted label too quiet on the phone.
