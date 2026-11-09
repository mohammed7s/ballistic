# AndroidWorld: can Ballistic's core clear each task?

Read-only analysis, 9 November 2026. **Measured** marks facts read from source code or from
recorded runs. **Judgement** marks verdicts and predictions. No task was run for this document.
A verdict of *likely clear* is not a predicted pass: on the practice set, 9 tasks carry that
verdict and Core 6 passed 8 of them. Those 9 verdicts were assigned knowing the outcomes, so they
do not validate the method.

Sources:
- AndroidWorld at commit `55ddaf5` on the ThinkPad (`~/android_world`, read only): `registry.py`,
  `task_evals/**` (single, composite, information_retrieval incl. `proto/tasks.textproto`,
  common_validators) and `env/adb_utils.py`.
- Ballistic master `59b8a58`: `core/README.md`, `core/src/{actions,agent,reader,reader/facts,
  capabilities,screen_filter,values,typed_value}.rs`, `harness/{core_bridge,run_task,outcomes}.py`,
  `harness/run_suite.sh` (the practice 20), `docs/BENCHMARK_RUNS.md`, `docs/AGENT_SECURITY_SURVEY.md` §5.

## 1. Measured facts that decide clearability

- **Registry.** `ANDROID_WORLD_FAMILY` = 91 classes in `TaskRegistry._TASKS` + 25 information-retrieval
  tasks from `tasks.textproto` = **116**. The seven phone/call tasks are commented out ("flaky").
- **Two kinds of check.** 25 tasks check an **answer string** taken from `env.interaction_cache`.
  The answer is split on commas and the item count must match. STRING items are fuzzy-matched (difflib ratio
  >= 0.9, case-insensitive). NUMBER items must be exact or within a tolerance (10 m for the two distance
  tasks). DATE/TIME items are parsed with fixed formats, so a wrong format fails. The other
  91 tasks check **state**: SQLite rows, files, settings or the clipboard. Seven of these
  read the **final UI tree** (ClockTimerEntry, both stopwatch tasks, ContactsNewContactDraft, and the three
  Browser tasks, which need `Success!` with Chrome in the foreground). The SMS checks also require Simple SMS
  to be in the foreground.
- **Step budget** in the harness is `int(complexity x 10)` (`run_task.py`). The Budget column below is that value.
- **Answers do not reach the scorer today.** Nothing in `harness/` writes `env.interaction_cache`. A
  core `answer` ends the run with `done:false`, `task_status: pending` (`agent.rs`), and the suite's
  combined PASS needs `done` (`outcomes.py`). **All 25 answer tasks therefore score 0 in the
  core harness whatever the agent does.** The T3A baseline sets the cache through AndroidWorld's own
  `answer` action.
- **Action set** (`actions.rs`): open_app, tap, long_press, type, scroll, swipe, back, home, wait,
  read, answer, done, give_up. There is no clear-field, caret, select-all, clipboard, drag-path or
  coordinate action. The harness `type` taps the element centre and then types (`core_bridge.act`), so
  text lands wherever the caret falls and existing text is never replaced.
- **What the planner can learn from content** (`reader.rs`, `reader/facts.rs`, README §7). `element`
  returns one on-screen id. `facts` returns `sender` (shown only if it matches the request), `when`,
  `phone_numbers`, `amounts`, `urls`, plus `summary`, which goes to the owner only. The facts have no
  plain-number, duration, distance, address, name or title field. `summary` is terminal, single-screen,
  opt-in only for requests starting `summarize`/`summarise`, and limited to 64 reader tokens.
- **`answer`** (`actions::answer_values`): every number, phone number, amount or link must come from
  the request, a control label, a carried value or a reader fact. Words are not checked, but the
  planner never sees content words, so it cannot write a content title.
- **Long-press** works on labelled controls and on owner-matched titles. A reader pick authorizes
  **tap only** (`ReaderTapOnly`).
- **Recorded screens** (`core/tests/screens`) exist only for Settings, SystemUI, launcher, Contacts,
  Dialer, Clock, Camera, Pro Expense, Simple Calendar, Simple SMS and Markor. Code-owned labels
  (`KNOWN_CONTROLS`) cover only Expense, Calendar, SMS, Markor, the keyboards and two Quick Settings
  tiles. Files and Broccoli have practice-run traces but no fixture screens. Joplin, Tasks, OpenTracks,
  Retro Music, VLC, OsmAnd, Audio Recorder, Simple Draw Pro, Simple Gallery Pro, Clipper and Chrome
  have **never been observed** by core. Rows marked (U) depend on how their unseen trees filter.
- **Practice evidence** (`BENCHMARK_RUNS.md`): Core 6 (last complete gemini run) 8 PASS / 10 FAIL /
  2 ERROR, state 11/20. Haiku 2: 6 / 14 / 0, state 10/20. T3A baseline: 13/20. Zero false done in
  Core 6 and Haiku 2.

## 2. Legend

Requires (judgement): **N1/N2/N3** navigation depth (N1: up to 3 screens, N2: 4 to 8, N3: more than 8 or
repeated loops); **T** text entry; **R** read content and answer or decide from it; **X** retrieval across
screens; **M** multi-app; **D** date/time picker or date navigation; **S** slider or gesture; **F** file or
media; **G** settings toggle; **E** exact answer formatting. Check (measured) is the AndroidWorld
`is_successful` condition. Gaps are the codes defined in §4. **P:** in the evidence column marks the 20
practice tasks. (U) marks an app core has never observed.

## 3. Every task

| # | Task | App | Requires | Check | Budget | Verdict | Gaps | Reason (judgement) | Evidence |
|---:|---|---|---|---|---:|---|---|---|---|
| 1 | OpenAppTaskEval | Settings + any app | N1 | state: foreground activity | 10 | likely clear | - | Launch-only typed postcondition exists (launch_completion.rs). | P: PASS Core3/4/6; FAIL Core7, Haiku2 (state passed, done rejected) |
| 2 | SystemBluetoothTurnOff | Settings | N1 G | state: Bluetooth off | 10 | likely clear | - | Mirror of the practice Bluetooth task; Settings profiles recorded. | sibling of P task |
| 3 | SystemBluetoothTurnOffVerify | Settings | N1 G | state: Bluetooth off (already off at start) | 10 | likely clear | - | State already holds; needs an accepted done on the observed switch, no toggle. | sibling of P task |
| 4 | SystemBluetoothTurnOn | Settings | N1 G | state: Bluetooth on | 10 | likely clear | - | Passed in Core6 and Haiku2. | P: PASS Core6, Haiku2; FAIL Core3/4/5 |
| 5 | SystemBluetoothTurnOnVerify | Settings | N1 G | state: Bluetooth on (already on) | 10 | likely clear | - | As above, verify-only. | sibling of P task |
| 6 | SystemBrightnessMax | Settings / SystemUI | N2 S | state: brightness max | 10 | **needs** | SLIDER | Swipe reached the goal (state passed) but done needs a readable slider value. | P: FAIL all core runs; state passed Core4, Haiku2 |
| 7 | SystemBrightnessMaxVerify | Settings / SystemUI | N2 S | state: brightness max (already max) | 10 | **needs** | SLIDER | State holds at start; done cannot be established without a slider value. | sibling of P task |
| 8 | SystemBrightnessMin | Settings / SystemUI | N2 S | state: brightness min | 10 | **needs** | SLIDER | Same as Max. | sibling of P task |
| 9 | SystemBrightnessMinVerify | Settings / SystemUI | N2 S | state: brightness min (already min) | 10 | **needs** | SLIDER | Same as MaxVerify. | sibling of P task |
| 10 | SystemCopyToClipboard | Clipper | N1 T | state: clipboard fuzzy-matches text | 10 | likely clear | - | Text is the owner's; type into Clipper. Unrecorded app; if it has no set-clipboard control this needs EDIT (select/copy). | none (U) |
| 11 | SystemWifiTurnOff | Settings | N1 G | state: Wi-Fi off | 10 | likely clear | - | Passed Core5/6, Haiku2. | P: PASS Core5/6, Haiku2; FAIL Core3/4; ERROR Core8 |
| 12 | SystemWifiTurnOffVerify | Settings | N1 G | state: Wi-Fi off (already off) | 10 | likely clear | - | Verify-only. | sibling of P task |
| 13 | SystemWifiTurnOn | Settings | N1 G | state: Wi-Fi on | 10 | likely clear | - | Mirror of practice task. | sibling of P task |
| 14 | SystemWifiTurnOnVerify | Settings | N1 G | state: Wi-Fi on (already on) | 10 | likely clear | - | Verify-only. | sibling of P task |
| 15 | TurnOffWifiAndTurnOnBluetooth | Settings | N2 G | state: Wi-Fi off and Bluetooth on | 20 | likely clear | - | Two recorded toggles in sequence. | siblings of P tasks |
| 16 | TurnOnWifiAndOpenApp | Settings + app | N2 G M | state: Wi-Fi on and app foreground | 20 | likely clear | - | Passed Core5/6, Haiku1/2. | P: PASS Core5/6, Haiku1/2 |
| 17 | ClockStopWatchPausedVerify | Clock | N1 | UI tree: stopwatch paused (already paused at 00:00) | 10 | likely clear | - | Open the stopwatch tab and claim done on the visible Start control; Clock text is OS label. | sibling of P task |
| 18 | ClockStopWatchRunning | Clock | N1 | UI tree: stopwatch running | 10 | likely clear | - | Passed Core4/5/6/8, Haiku2. | P: PASS Core4/5/6/8, Haiku2 |
| 19 | ClockTimerEntry | Clock | N1 T E | UI tree: timer shows h/m/s, not started | 10 | **needs** | KEYPAD | Digit-pad entry sequence repeatedly wrong; display is readable OS text. | P: FAIL all runs incl. T3A |
| 20 | ContactsAddContact | Contacts | N2 T | state: contact row (name, number) | 12 | likely clear | - | Passed every complete core run except Haiku1 (token-cap error). | P: PASS Core3/4/5/6, Haiku2 |
| 21 | ContactsNewContactDraft | Contacts | N2 T | UI tree: draft fields incl. phone label, unsaved | 12 | likely clear | - | Owner values typed; label dropdown (Home/Work); done without Save. | sibling of P task |
| 22 | CameraTakePhoto | Camera | N1 F | state: exactly one new photo file | 10 | **needs** | MEDIA | Shutter tapped but done rejected and repeated taps; no capture evidence. | P: FAIL all core runs (T3A PASS) |
| 23 | CameraTakeVideo | Camera | N1 F | state: exactly one new video file | 10 | **needs** | MEDIA | Start/stop recording; same missing capture evidence. | sibling of P task |
| 24 | AudioRecorderRecordAudio | Audio Recorder | N1 F | state: new non-empty recording file | 12 | **needs** | MEDIA | Record, stop, save; capture evidence missing. Unrecorded app. | none (U) |
| 25 | AudioRecorderRecordAudioWithFileName | Audio Recorder | N2 F T | state: <name>.m4a exists | 20 | **needs** | MEDIA | As above plus owner-given file name in save dialog. | none (U) |
| 26 | BrowserDraw | Files + Chrome | N2 M S | UI tree: page shows 'Success!' | 20 | **blocked by design** | VIS, GEST | Target colours exist only as swatch styles; needs free-form canvas drawing. | none (U) |
| 27 | BrowserMaze | Files + Chrome | N2 M | UI tree: page shows 'Success!' | 20 | **blocked by design** | VIS | Maze walls are CSS-only divs, absent from the tree; route cannot be planned tree-only. | none (U) |
| 28 | BrowserMultiply | Files + Chrome | N2 M R T | UI tree: page shows 'Success!' | 22 | **needs** | NUM, XS | Five web-content numbers, one per click; product typed (taint-confirmed). Facts have no plain-number field. | none (U) |
| 29 | SimpleCalendarAddOneEvent | Simple Calendar Pro | N3 T D | state: exactly one matching event row | 34 | **needs** | DATE | Title/description typed; start/end time picker never set correctly; no Save. | P: FAIL all runs incl. T3A |
| 30 | SimpleCalendarAddOneEventInTwoWeeks | Simple Calendar Pro | N3 T D | state: event row | 34 | **needs** | DATE | Same pickers plus date arithmetic from the device date. | sibling of P task |
| 31 | SimpleCalendarAddOneEventRelativeDay | Simple Calendar Pro | N3 T D | state: event row | 34 | **needs** | DATE | Same plus 'this <weekday>' resolution. | sibling of P task |
| 32 | SimpleCalendarAddOneEventTomorrow | Simple Calendar Pro | N3 T D | state: event row | 34 | **needs** | DATE | Same plus 'tomorrow'. | sibling of P task |
| 33 | SimpleCalendarAddRepeatingEvent | Simple Calendar Pro | N3 T D | state: event row incl. repeat rule/interval | 34 | **needs** | DATE | Same plus repeat options (labels). | sibling of P task |
| 34 | SimpleCalendarDeleteEvents | Simple Calendar Pro | N2 D | state: all events on date removed, others intact | 14 | **needs** | DATE | Date navigation failed in practice; unnamed events need reader picks then Delete. | related P task failed date selection |
| 35 | SimpleCalendarDeleteEventsOnRelativeDay | Simple Calendar Pro | N2 D | state: events on that weekday removed | 12 | **needs** | DATE | As above plus weekday resolution. | related P task |
| 36 | SimpleCalendarDeleteOneEvent | Simple Calendar Pro | N2 D | state: one event removed | 12 | **needs** | DATE | Title is in the request (match-only); date navigation is the blocker. | related P task |
| 37 | ExpenseAddMultiple | Pro Expense | N3 T | state: 3 rows (name, amount, category, note) | 60 | **needs** | FIND | Owner values; category picker failed in practice (option offscreen). | related P task failed |
| 38 | ExpenseAddMultipleFromGallery | Gallery + Pro Expense | N3 M F T | state: rows from an image | 60 | **blocked by design** | VIS | Values exist only as pixels in expenses.jpg. | none |
| 39 | ExpenseAddMultipleFromMarkor | Markor + Pro Expense | N3 M R X T | state: reimbursable rows added | 60 | **needs** | XFER, PRED, XS | Names/notes come from file content the planner never sees; filter 'Reimbursable' among ~100 rows. | none |
| 40 | ExpenseAddSingle | Pro Expense | N2 T | state: 1 row | 12 | **needs** | FIND | Reader found no Transportation among visible category rows; never scrolled to it. | P: FAIL/ERROR all runs incl. T3A |
| 41 | ExpenseDeleteDuplicates | Pro Expense | N2 R | state: one of two exact duplicates removed | 12 | **needs** | DUP | Rows are content; needs an equality signal (element reader may suffice if all rows fit one screen). | none |
| 42 | ExpenseDeleteDuplicates2 | Pro Expense | N3 R X | state: as above, 40 noise rows | 18 | **needs** | DUP, XS | Duplicates can sit on different screens; near-duplicates differ only by amount. | none |
| 43 | ExpenseDeleteMultiple | Pro Expense | N2 | state: 3 named rows removed | 20 | likely clear | - | Names in request (match-only titles); delete flow reached in practice. | related P task: state passed Core4/6 |
| 44 | ExpenseDeleteMultiple2 | Pro Expense | N3 | state: 3 named rows removed among 50 noise | 34 | **needs** | FIND | Needs scroll-search for owner-named rows in a long list. | related P task |
| 45 | ExpenseDeleteSingle | Pro Expense | N2 | state: 1 named row removed | 10 | likely clear | - | State passed twice; failures were provider errors and one give-up. | P: state passed Core4/6; ERROR Core4/6, FAIL Core3, Haiku2 |
| 46 | FilesDeleteFile | Files | N3 F | state: file absent | 22 | **needs** | FIND | Never reached the owner-named storage/folder rows; reader-none loops. | P: FAIL/ERROR all core runs (T3A PASS) |
| 47 | FilesMoveFile | Files | N3 F | state: file in destination, not in source | 20 | **needs** | FIND | Same navigation, plus Move-to folder picker. | related P task |
| 48 | MarkorAddNoteHeader | Markor | N2 T E | state: exact content header+blank line+old; renamed | 12 | **needs** | EDIT | Needs caret at start of an existing note and a rename; exact match. | none |
| 49 | MarkorChangeNoteContent | Markor | N2 T | state: new name exists, old gone, content replaced | 12 | **needs** | EDIT | Needs replace-all in the editor (type only appends at the tap point). | none |
| 50 | MarkorCreateFolder | Markor | N1 T | state: folder exists | 10 | likely clear | - | Passed Core6, Haiku2. | P: PASS Core6, Haiku2; state passed Core3/4 |
| 51 | MarkorCreateNote | Markor | N2 T | state: file with fuzzy-matched content | 16 | **needs** | EDIT | Basename typed without the requested extension; prefilled extension field not readable/replaceable. | P: FAIL all runs incl. T3A |
| 52 | MarkorCreateNoteFromClipboard | Markor + clipboard | N2 T | state: file content = clipboard | 14 | **needs** | EDIT | Paste via long-press on the code-owned editor is allowed; same new-file dialog as MarkorCreateNote. | related P task |
| 53 | MarkorDeleteAllNotes | Markor | N2 | state: no files | 14 | **needs** | LP | Rows unnamed in request: reader picks authorize tap only, no long-press multi-select. | related P task |
| 54 | MarkorDeleteNewestNote | Markor | N2 R | state: newest file removed, others intact | 10 | **needs** | LP | Reader can pick by date; deletion needs long-press or an in-note menu that failed in practice. | related P task |
| 55 | MarkorDeleteNote | Markor | N2 | state: file absent | 10 | **needs** | LP | Traces: ReaderTapOnly rejection on long-press; in-note menu read returned none; opaque menu icon. | P: FAIL all core runs (T3A PASS) |
| 56 | MarkorEditNote | Markor | N2 T | state: content fuzzy-matches header/footer/replace | 12 | **needs** | EDIT | Needs caret start/end or replace-all on existing content. | none |
| 57 | MarkorMergeNotes | Markor | N3 X T | state: new file = three contents with blank lines | 78 | **needs** | XFER, EDIT | Contents are content; needs copy/paste route or opaque value handles across three notes. | none |
| 58 | MarkorMoveNote | Markor | N2 | state: file moved | 14 | **needs** | LP | Same long-press/menu path as MarkorDeleteNote, then folder picker. | related P task |
| 59 | MarkorTranscribeReceipt | Gallery + Markor | N3 M F T | state: receipt.md content | 18 | **blocked by design** | VIS | Transactions exist only as pixels in receipt.png. | none |
| 60 | MarkorTranscribeVideo | VLC + Markor | N3 M F T | state: note with the text shown in a video | 20 | **blocked by design** | VIS | Text appears only in video frames. | none |
| 61 | MarkorCreateNoteAndSms | Markor + SMS | N3 M T | state: note file and SMS with its text | 18 | **needs** | EDIT, SENDV | Text is owner-given; inherits the new-file dialog and send-verification gaps. | related P tasks |
| 62 | OsmAndFavorite | OsmAnd | N2 T | state: favourites file contains location | 13 | likely clear | - | Search by owner-given name/coordinates, then add favourite. Unrecorded app. | none (U) |
| 63 | OsmAndMarker | OsmAnd | N2 T | state: marker near location | 20 | likely clear | - | As above with a map marker. Unrecorded app. | none (U) |
| 64 | OsmAndTrack | OsmAnd | N3 T | state: saved track with waypoints in order | 120 | **needs** | MAP | Route planning places points on the map canvas; a search-then-centre route is unverified. | none (U) |
| 65 | RecipeAddMultipleRecipes | Broccoli | N3 T | state: 3 recipe rows | 60 | likely clear | - | Same form as the practice recipe task (passed Core3/4/6). | related P task |
| 66 | RecipeAddMultipleRecipesFromImage | Gallery + Broccoli | N3 M F T | state: rows from an image | 60 | **blocked by design** | VIS | Values exist only as pixels in recipes.jpg. | none |
| 67 | RecipeAddMultipleRecipesFromMarkor | Markor + Broccoli | N3 M R X T | state: rows from recipes.txt | 60 | **needs** | XFER, XS | Titles/ingredients/directions are file content the planner never sees. | none |
| 68 | RecipeAddMultipleRecipesFromMarkor2 | Markor + Broccoli | N3 M R X T | state: rows filtered by prep time | 60 | **needs** | XFER, PRED, XS | As above plus a prep-time filter among 43 recipes. | none |
| 69 | RecipeAddSingleRecipe | Broccoli | N3 T | state: 1 recipe row | 24 | likely clear | - | Passed Core3/4/6; Haiku2 failed scrolling to fields. | P: PASS Core3/4/6; FAIL Haiku2 |
| 70 | RecipeDeleteDuplicateRecipes | Broccoli | N2 R | state: one duplicate removed | 10 | **needs** | DUP | Variations share the title and differ in other fields: needs an equality signal. | none |
| 71 | RecipeDeleteDuplicateRecipes2 | Broccoli | N3 R X | state: as above, 10 noise | 24 | **needs** | DUP, XS | As above across screens. | none |
| 72 | RecipeDeleteDuplicateRecipes3 | Broccoli | N3 R X | state: as above, 30 noise | 34 | **needs** | DUP, XS | As above across screens. | none |
| 73 | RecipeDeleteMultipleRecipes | Broccoli | N2 | state: 3 named rows removed | 24 | likely clear | - | Titles in request (match-only); no noise. | none |
| 74 | RecipeDeleteMultipleRecipesWithConstraint | Broccoli | N3 R X | state: recipes whose directions contain X removed | 40 | **needs** | PRED, XS | Per-recipe predicate on directions text across ~32 recipes. | none |
| 75 | RecipeDeleteMultipleRecipesWithNoise | Broccoli | N3 | state: 3 named rows removed among 29 noise | 34 | **needs** | FIND | Scroll-search for owner-named rows. | none |
| 76 | RecipeDeleteSingleRecipe | Broccoli | N2 | state: 1 named row removed | 10 | likely clear | - | Title in request; no noise. | none |
| 77 | RecipeDeleteSingleWithRecipeWithNoise | Broccoli | N3 | state: 1 named row removed among 29 noise | 20 | **needs** | FIND | Scroll-search for an owner-named row. | none |
| 78 | RetroCreatePlaylist | Retro Music | N3 T | state: playlist with songs in order | 24 | likely clear | - | 2-5 owner-named songs among 15; ordered adds. Unrecorded app. | none (U) |
| 79 | RetroPlayingQueue | Retro Music | N3 | state: queue equals songs in order | 32 | likely clear | - | Ordered 'add to queue' on owner-named songs. Unrecorded app. | none (U) |
| 80 | RetroPlaylistDuration | Retro Music | N3 R | state: playlist total 45-50 min | 30 | **needs** | NUM | Needs song durations as values and a subset sum. | none (U) |
| 81 | RetroSavePlaylist | Retro Music | N3 T F | state: playlist + exported .m3u in Download | 50 | likely clear | - | Create playlist, export through the system file picker. Unrecorded app. | none (U) |
| 82 | SimpleDrawProCreateDrawing | Simple Draw Pro | N2 T F | state: file exists in Pictures | 18 | likely clear | - | Checker tests only the saved file name; drawing content not checked. Unrecorded app. | none (U) |
| 83 | SaveCopyOfReceiptTaskEval | Simple Gallery Pro | N2 F | state: same-name file in Download | 16 | likely clear | - | Only one image in DCIM; Copy-to and folder picker. Unrecorded app. | none (U) |
| 84 | SimpleSmsReply | Simple SMS | N2 T | state: SMS to number with body; SMS app foreground | 12 | **needs** | SENDV | Owner-given number/body; practice sends pass state but done is rejected. | related P tasks |
| 85 | SimpleSmsReplyMostRecent | Simple SMS | N2 R T | state: SMS to newest sender | 12 | **needs** | SENDV | Reply sent (state passed 4 times); done never accepted. | P: FAIL all core; state passed Core4/5/6, Haiku2 |
| 86 | SimpleSmsResend | Simple SMS | N2 R T | state: earlier body re-sent to contact | 12 | **needs** | XFER, LP, SENDV | Body is content: needs copy (long-press on a content bubble) or a value handle. | none |
| 87 | SimpleSmsSend | Simple SMS | N2 T | state: SMS to number with body | 12 | **needs** | SENDV | Sent (state passed 3 times); verifier rejected done each time. | P: FAIL/ERROR all core; state passed Core6/7, Haiku2 |
| 88 | SimpleSmsSendClipboardContent | Simple SMS + clipboard | N2 T | state: SMS body = clipboard | 12 | **needs** | SENDV | Paste into the labelled composer is allowed; send verification is the gap. | related P tasks |
| 89 | SimpleSmsSendReceivedAddress | Simple SMS | N2 R T | state: received address sent to contact | 18 | **needs** | XFER, SENDV | Address is message content; facts have no address field. | none |
| 90 | VlcCreatePlaylist | VLC | N3 T F | state: playlist with files in order | 28 | likely clear | - | 2-5 owner-named files plus as many noise files. Unrecorded app. | none (U) |
| 91 | VlcCreateTwoPlaylists | VLC | N3 T F | state: both playlists (scored 0, 0.5, 1) | 48 | likely clear | - | Two of the above in sequence. Unrecorded app. | none (U) |
| 92 | SimpleCalendarEventsOnDate | Simple Calendar Pro | N2 R D E | answer: titles, STRING fuzzy>=0.9 per item | 10 | **needs** | ANS, EXT, DATE | Titles are content not in the request; date selection failed in practice. | P: FAIL all runs incl. T3A |
| 93 | SimpleCalendarNextEvent | Simple Calendar Pro | N2 R E | answer: titles, STRING | 10 | **needs** | ANS, EXT | Next event relative to the device clock; titles are content. | related P task |
| 94 | SimpleCalendarEventOnDateAtTime | Simple Calendar Pro | N2 R D E | answer: titles, STRING | 10 | **needs** | ANS, EXT, DATE | Date navigation plus time filter; titles are content. | related P task |
| 95 | SimpleCalendarAnyEventsOnDate | Simple Calendar Pro | N2 R D E | answer: titles, STRING | 10 | **needs** | ANS, EXT, DATE | As EventsOnDate. | related P task |
| 96 | SimpleCalendarNextMeetingWithPerson | Simple Calendar Pro | N2 R E | answer: DATE+TIME '<Month> <d> <yyyy> HH:MM' | 10 | **needs** | ANS | Event found by reader pick; date/time numbers available as typed values/facts; month name is a word. | related P task |
| 97 | SimpleCalendarLocationOfEvent | Simple Calendar Pro | N2 R E | answer: location, STRING | 10 | **needs** | ANS, EXT | Event title is in the request; location is content. | related P task |
| 98 | SimpleCalendarEventsInNextWeek | Simple Calendar Pro | N3 R X E | answer: titles, STRING | 10 | **needs** | ANS, EXT, XS | Titles across seven days/screens. | related P task |
| 99 | SimpleCalendarFirstEventAfterStartTime | Simple Calendar Pro | N2 R D E | answer: title, STRING | 10 | **needs** | ANS, EXT, DATE | Date navigation plus time ordering; title is content. | related P task |
| 100 | SimpleCalendarEventsInTimeRange | Simple Calendar Pro | N2 R D E | answer: titles, STRING | 10 | **needs** | ANS, EXT, DATE | Date navigation plus time window. | related P task |
| 101 | TasksDueOnDate | Tasks | N2 R E | answer: titles, STRING | 10 | **needs** | ANS, EXT | Filter by due date shown in rows; titles are content. Unrecorded app. | none (U) |
| 102 | TasksHighPriorityTasks | Tasks | N3 R X E | answer: titles, STRING | 10 | **needs** | ANS, EXT, XS | Priority may be shown only as checkbox colour (unverified; would then need VIS). | none (U) |
| 103 | TasksHighPriorityTasksDueOnDate | Tasks | N3 R X E | answer: titles, STRING | 10 | **needs** | ANS, EXT, XS | As above plus date filter. | none (U) |
| 104 | TasksDueNextWeek | Tasks | N2 R E | answer: NUMBER (count) | 10 | **needs** | ANS, NUM | Count of rows with due dates in a week: a derived number. | none (U) |
| 105 | TasksCompletedTasksForDate | Tasks | N2 R E | answer: titles, STRING | 10 | **needs** | ANS, EXT | Completed tasks are hidden by default (menu toggle); titles are content. | none (U) |
| 106 | TasksIncompleteTasksOnDate | Tasks | N2 R E | answer: titles, STRING | 10 | **needs** | ANS, EXT | Filter by date and completion; titles are content. | none (U) |
| 107 | SportsTrackerActivitiesOnDate | OpenTracks | N2 R E | answer: activity categories, STRING | 20 | **needs** | ANS, EXT | Category strings are content. Unrecorded app. | none (U) |
| 108 | SportsTrackerActivitiesCountForWeek | OpenTracks | N2 R X E | answer: NUMBER (count) | 10 | **needs** | ANS, NUM, XS | Count of category matches this week. | none (U) |
| 109 | SportsTrackerActivityDuration | OpenTracks | N2 R E | answer: NUMBER minutes (exact) | 10 | **needs** | ANS, NUM | Displayed duration must be converted to whole minutes. | none (U) |
| 110 | SportsTrackerLongestDistanceActivity | OpenTracks | N2 R X E | answer: NUMBER metres (tolerance 10) | 10 | **needs** | ANS, NUM, XS | Max over the week plus km-to-m conversion. | none (U) |
| 111 | SportsTrackerTotalDurationForCategoryThisWeek | OpenTracks | N2 R X E | answer: NUMBER minutes (SUM) | 10 | **needs** | ANS, NUM, XS | Sum over the week. | none (U) |
| 112 | SportsTrackerTotalDistanceForCategoryOverInterval | OpenTracks | N3 R X E | answer: NUMBER metres (SUM, tolerance 10) | 20 | **needs** | ANS, NUM, XS | Sum over a date interval plus conversion. | none (U) |
| 113 | NotesRecipeIngredientCount | Joplin | N2 R E | answer: '<amount> <unit>' STRING | 10 | **needs** | ANS, EXT | Note title in request; quantity must be an exact excerpt of note text. | none (U) |
| 114 | NotesMeetingAttendeeCount | Joplin | N2 R E | answer: NUMBER | 10 | **needs** | ANS, NUM | Count of attendees listed in note text: a reader-derived count. | none (U) |
| 115 | NotesIsTodo | Joplin | N2 R E | answer: 'True'/'False' STRING | 10 | **needs** | ANS | Todo notes carry a checkbox (structure); True/False are words, not values. | none (U) |
| 116 | NotesTodoItemCount | Joplin | N2 R E | answer: NUMBER (COUNT) | 10 | **needs** | ANS, NUM | Count of checkbox rows in a folder: structural, code-countable. | none (U) |

## 4. Summary

### Verdict counts (judgement)

| Verdict | All 116 | State checks | Answer checks | Practice 20 |
|---|---:|---:|---:|---:|
| likely clear | 32 | 32 | 0 | 9 |
| needs | 78 | 53 | 25 | 11 |
| blocked | 6 | 6 | 0 | 0 |
| total | 116 | 91 | 25 | 20 |

Practice cross-check (measured outcomes, verdicts not independent): Core 6 passed 8 of the 9 practice
tasks marked *likely clear*. The ninth, ExpenseDeleteSingle, had its state passed and then a provider error.
Core 6 passed none of the 11 marked *needs*.

### Capability gaps ranked by tasks unlocked (judgement)

*Tasks needing it* counts every task whose path contains the gap. *Sole remaining gap* counts tasks
that would have no other gap once this one is closed, treating ANS as a shared prerequisite for answer
tasks. A task with several gaps clears only when all of them are closed.

| Rank | Gap | Capability | Tasks needing it | Sole remaining gap | Fits the safety design? How |
|---:|---|---|---:|---:|---|
| 1 | ANS | Answer delivery to the scorer (harness) | 25 | 2 | Compatible, harness only. After the run the host writes the raw planner `answer.text` (or the joined owner excerpts, below) to `env.interaction_cache`, and the suite counts an answered run as agent-complete. The display string must not be used: it adds `(from <source>)`, which breaks the checker's comma split and fuzzy match. The answer must never go back into a model prompt. The checker stays outside the agent. |
| 2 | EXT | Extractive owner answer (titles, locations, quantities) | 15 | 7 | Compatible. Extend the existing terminal owner `summary` channel (§7), which today needs a request starting `summarize` and allows 64 reader tokens. A question would then end with reader-selected exact excerpts, one item each, shown to the owner with sources and never put in a planner prompt. Contiguous-substring checks already exist. Needs a non-`summarize` trigger (a planner `answer_from_content` action on chosen cN, or a question form), a larger token cap, and multi-item output joined with `, `. |
| 3 | XS | Cross-screen accumulation | 15 | 0 | Compatible. Keep typed reader outputs (facts, excerpts, picks, equality classes) across scrolls and screens, deduplicated by node identity, so that a list longer than one screen can be aggregated. Planner-visible parts keep their shape rules. Owner excerpts build up in an owner-only buffer. |
| 4 | DATE | Date/time pickers and date navigation | 13 | 8 | Compatible. Typed values for picker dials, spinners and the date header (the existing typed calendar values cover form fields and month cells), plus relative dates resolved from a host-supplied device date (a new source such as `device:clock`, not content). Every value comes from the owner's request. |
| 5 | NUM | Numbers, durations, distances and derived values | 10 | 5 | Compatible. Add `numbers` / `durations` / `distances` fact fields (copied substrings, parsed in code), and code-derived values (count of reader picks or of structural rows, sum, max, unit conversion) with provenance `derived(op, sources)`. `answer` would accept derived values. Typing a derived value stays taint-confirmed. |
| 6 | FIND | Scroll-search for owner-named rows/options | 7 | 7 | Compatible. A bounded code loop (or planner action) scrolls an advertised scrollable area until a match-only title or label equal to the owner's words appears. It uses only the §5 owner-word match and shows no content. |
| 7 | EDIT | Editing existing and prefilled fields | 7 | 5 | Compatible. Hands operations for replace-all (`ACTION_SET_TEXT`) and caret to start/end (`ACTION_SET_SELECTION`), plus readback of prefilled control values such as Markor's extension. Text still passes the taint rule. Existing content is never shown, because insert and replace do not need it. |
| 8 | SENDV | Send-effect postcondition | 7 | 4 | Compatible, already called for in §10. A typed readback that the owner's body (owner text, so match-only can show it) appears in the thread as sent, accepted as done evidence instead of the model verifier. The measured cause is the verifier confusing the disabled Send button and composer with the sent message. |
| 9 | VIS | Vision: information only in pixels | 6 | 5 | Blocked by design (tree-only, no screenshots). |
| 10 | XFER | Content-to-field transfer without showing content | 6 | 0 | Compatible, but it adds confirmations. Option (a): opaque value handles. The reader returns typed fields (name, note, address, body) as handles; the planner types `{v1}`; core substitutes the value and hard-confirms it with its source (the taint rule already expects this). Option (b): a UI copy/paste route (long-press or select-all, Copy, Paste). Either way the planner never sees the text. Cost: one confirmation per transferred value. |
| 11 | LP | Long-press / selection mode on reader-picked rows | 5 | 4 | Compatible. Extend a reader pick's authority from tap to long-press and selection-mode taps. Deletion stays hard-confirmed and Rule of Two still applies. Also needs labels for overflow/menu icons (the Core8 trace hit an opaque SystemUI image). |
| 12 | DUP | Code-computed content equality | 5 | 2 | Compatible. Core compares the withheld strings of rows or detail screens in code and tells the planner only equality classes (`e4 = e9`). An equality bit cannot carry an instruction. |
| 13 | MEDIA | Capture-effect evidence | 4 | 4 | Compatible as a host capability: a receipt of a new media item (count before and after). On the phone this needs a permission. It is not the benchmark checker. |
| 14 | SLIDER | Slider value observation | 4 | 4 | Compatible: this is OS control state, not content. The host must expose the slider's range info (current/min/max) if the accessibility tree carries it (unverified for AndroidWorld's `UIElement`). The value is then accepted as done evidence. |
| 15 | PRED | Per-item yes/no predicate over content | 3 | 0 | Compatible. A boolean reader shape ("does this recipe's directions contain X"), a fixed-set answer like `element`. Usually needs XS to iterate. |
| 16 | KEYPAD | Reliable digit-pad entry | 1 | 1 | Compatible. Map the owner's value to keypad keys and verify against the display, which is OS label text. |
| 17 | MAP | Map-canvas interaction | 1 | 1 | Probably blocked (tree-only). Unverified whether OsmAnd can add route points through search plus 'add point at centre'. |
| 18 | GEST | Free-form drag path | 1 | 0 | A Hands op could be added, but its only task (BrowserDraw) also needs VIS. |

The first five by tasks needing them: **ANS 25** (every answer task), **EXT 15**, **XS 15**,
**DATE 13**, **NUM 10**. By sole-remaining-gap unlocks: DATE 8, FIND 7, EXT 7 (with ANS), EDIT 5,
NUM 5 (4 with ANS).

**Status after improvement loop 2 (9 Nov 2026; implemented and tested offline, live results in
`docs/BENCHMARK_RUNS.md`, "Loop 2").** The verdicts and counts above are unchanged; these notes say
which gaps have code now. **ANS**: implemented in the harness only (`run_task.py`
`--deliver_answers`): a sourced `answer.text`, or available owner excerpts joined with `, `, goes to
`env.interaction_cache` through AndroidWorld's ANSWER action; the PASS column counts it only for
`InformationRetrieval` tasks. **EXT**: partly, as the question form of the terminal owner excerpt
channel (interrogative sentence ending in `?`; one exact excerpt per chosen row; 160 reader
tokens); no cross-screen accumulation. **DATE**: partly, Simple Calendar's event-list section
headers carry a typed day/weekday; pickers unchanged. **FIND**: partly, a planner rule to `scroll …
until` the exact owner-named item, a tappable checkable chip the request names, and long press on
a clickable owner-named row; no code-driven search loop. **SENDV**: unchanged since b22da1f (the
reply row slack is confirmed on the recorded Opus trace by replay). **LP**: reader picks remain
tap-only (see core/README.md §5, loop 2 paragraph, for why); owner-named rows could already be
long-pressed. New typed postconditions for deleted named rows and a saved Markor note cover parts of
the FIND and EDIT task families' completion evidence, not their gaps.

### Cumulative unlock order (judgement, design-compatible gaps only)

Greedy order: at each step, close the gap that turns the most remaining *needs* tasks into tasks with
no gap left. VIS, GEST and MAP are left out. Gap-free is not the same as passing: it means no known
capability is missing, as with the 32 *likely clear* tasks. ANS shows up late only because, closed on
its own, it frees just 2 tasks. It is a prerequisite for all 25 answer tasks, so in practice it comes
first: ANS with EXT frees 9 answer tasks; ANS with NUM frees 6 answer tasks plus RetroPlaylistDuration (counted from §3).

| Step | Close | Newly gap-free tasks | Cumulative gap-free (of 116, incl. 32 likely clear) |
|---:|---|---|---:|
| 1 | DATE | 8: SimpleCalendarAddOneEvent, SimpleCalendarAddOneEventInTwoWeeks, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarAddOneEventTomorrow, SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents, SimpleCalendarDeleteEventsOnRelativeDay, SimpleCalendarDeleteOneEvent | 40 |
| 2 | FIND | 7: ExpenseAddMultiple, ExpenseAddSingle, ExpenseDeleteMultiple2, FilesDeleteFile, FilesMoveFile, RecipeDeleteMultipleRecipesWithNoise, RecipeDeleteSingleWithRecipeWithNoise | 47 |
| 3 | EDIT | 5: MarkorAddNoteHeader, MarkorChangeNoteContent, MarkorCreateNote, MarkorCreateNoteFromClipboard, MarkorEditNote | 52 |
| 4 | SENDV | 5: MarkorCreateNoteAndSms, SimpleSmsReply, SimpleSmsReplyMostRecent, SimpleSmsSend, SimpleSmsSendClipboardContent | 57 |
| 5 | LP | 4: MarkorDeleteAllNotes, MarkorDeleteNewestNote, MarkorDeleteNote, MarkorMoveNote | 61 |
| 6 | SLIDER | 4: SystemBrightnessMax, SystemBrightnessMaxVerify, SystemBrightnessMin, SystemBrightnessMinVerify | 65 |
| 7 | MEDIA | 4: AudioRecorderRecordAudio, AudioRecorderRecordAudioWithFileName, CameraTakePhoto, CameraTakeVideo | 69 |
| 8 | XFER | 3: MarkorMergeNotes, SimpleSmsResend, SimpleSmsSendReceivedAddress | 72 |
| 9 | ANS | 2: NotesIsTodo, SimpleCalendarNextMeetingWithPerson | 74 |
| 10 | EXT | 12: NotesRecipeIngredientCount, SimpleCalendarAnyEventsOnDate, SimpleCalendarEventOnDateAtTime, SimpleCalendarEventsInTimeRange, SimpleCalendarEventsOnDate, SimpleCalendarFirstEventAfterStartTime, SimpleCalendarLocationOfEvent, SimpleCalendarNextEvent, SportsTrackerActivitiesOnDate, TasksCompletedTasksForDate, TasksDueOnDate, TasksIncompleteTasksOnDate | 86 |
| 11 | NUM | 5: NotesMeetingAttendeeCount, NotesTodoItemCount, RetroPlaylistDuration, SportsTrackerActivityDuration, TasksDueNextWeek | 91 |
| 12 | XS | 9: BrowserMultiply, RecipeAddMultipleRecipesFromMarkor, SimpleCalendarEventsInNextWeek, SportsTrackerActivitiesCountForWeek, SportsTrackerLongestDistanceActivity, SportsTrackerTotalDistanceForCategoryOverInterval, SportsTrackerTotalDurationForCategoryThisWeek, TasksHighPriorityTasks, TasksHighPriorityTasksDueOnDate | 100 |
| 13 | DUP | 5: ExpenseDeleteDuplicates, ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3 | 105 |
| 14 | PRED | 3: ExpenseAddMultipleFromMarkor, RecipeAddMultipleRecipesFromMarkor2, RecipeDeleteMultipleRecipesWithConstraint | 108 |
| 15 | KEYPAD | 1: ClockTimerEntry | 109 |

Left after all compatible gaps: OsmAndTrack. The 6 tasks blocked by design are
excluded throughout.

### Tasks that are genuinely incompatible with the design

Judgement, based on measured setup code:

- **Information that exists only as pixels (VIS):** MarkorTranscribeReceipt (receipt.png),
  ExpenseAddMultipleFromGallery (expenses.jpg), RecipeAddMultipleRecipesFromImage (recipes.jpg),
  MarkorTranscribeVideo (text burned into video frames), BrowserMaze (walls are CSS classes on empty
  divs, absent from the accessibility tree), BrowserDraw (target colours are swatch backgrounds; it also
  needs free-form canvas drawing). The tree-only rule rules these out. Extending reader shapes cannot help,
  because the tree holds no text for the reader to read.
- **Probably incompatible, unverified:** OsmAndTrack (route points are placed on the map canvas;
  verdict *needs MAP*), and TasksHighPriorityTasks / TasksHighPriorityTasksDueOnDate *if* Tasks shows
  priority only as checkbox colour (then they would need VIS too). One observation-only capture of each
  app would settle this.
- **Not incompatible, though it may look so:** the content-transfer tasks (Expense/Recipe from Markor,
  MarkorMergeNotes, SMS resend/forward-address). The planner never needs to *see* the content. It needs a
  way to move content into a field (XFER), with each transferred value hard-confirmed under the existing
  taint rule. Likewise the answer tasks need the answer to come from the reader to the owner
  (EXT/NUM), never through the planner.

## 5. Recommended held-out set (25 tasks)

Chosen on 9 November 2026, before any tuning against them. The set is disjoint from the practice 20
and from the rotation below, and excludes the 6 tasks blocked by design. Rules: freeze the list and
its seeds (seed 0, plus one second fixed seed if a run is repeated); never read their traces while
changing prompts, filters or code; run them only at milestones; report them separately from the
practice score. A full 116-task score, if wanted, is a separate report that includes the blocked tasks.

| # | Task | Verdict | Gaps | Class covered |
|---:|---|---|---|---|
| 1 | SystemBrightnessMin | needs | SLIDER | slider (min direction) |
| 2 | SystemWifiTurnOffVerify | likely clear | - | verify-only (no action needed) |
| 3 | SystemCopyToClipboard | likely clear | - | unseen utility app, clipboard state |
| 4 | ContactsNewContactDraft | likely clear | - | form without save, dropdown, UI-tree check |
| 5 | CameraTakeVideo | needs | MEDIA | media capture (start/stop) |
| 6 | AudioRecorderRecordAudioWithFileName | needs | MEDIA | unseen app, capture + naming |
| 7 | SimpleCalendarAddOneEventTomorrow | needs | DATE | pickers + relative date |
| 8 | SimpleCalendarDeleteEvents | needs | DATE | date navigation + delete unnamed rows |
| 9 | SimpleCalendarLocationOfEvent | needs | ANS, EXT | answer: extractive string |
| 10 | TasksDueOnDate | needs | ANS, EXT | answer: title list, unseen app |
| 11 | TasksDueNextWeek | needs | ANS, NUM | answer: derived count |
| 12 | SportsTrackerActivityDuration | needs | ANS, NUM | answer: number with unit conversion |
| 13 | NotesTodoItemCount | needs | ANS, NUM | answer: structural count |
| 14 | MarkorChangeNoteContent | needs | EDIT | edit existing content + rename |
| 15 | MarkorDeleteNewestNote | needs | LP | reader pick + delete flow |
| 16 | MarkorCreateNoteAndSms | needs | EDIT, SENDV | multi-app, owner text, send |
| 17 | ExpenseAddMultiple | needs | FIND | long form, picker search |
| 18 | ExpenseDeleteDuplicates | needs | DUP | content equality |
| 19 | RecipeDeleteSingleWithRecipeWithNoise | needs | FIND | scroll-search in noise |
| 20 | FilesMoveFile | needs | FIND | file manager move |
| 21 | SimpleSmsSendClipboardContent | needs | SENDV | paste + send |
| 22 | SimpleSmsSendReceivedAddress | needs | XFER, SENDV | content transfer to a third party |
| 23 | VlcCreatePlaylist | likely clear | - | media app, ordered selection |
| 24 | OsmAndFavorite | likely clear | - | maps app via search |
| 25 | SaveCopyOfReceiptTaskEval | likely clear | - | gallery copy to folder |

Mix: 6 likely clear, 19 needs, 5 answer tasks, 9 in never-observed apps. Expected score today (judgement): low, because the answer tasks
score 0 until ANS lands. The set is meant to measure generalisation once gaps close, not to set today's baseline.

## 6. Regression rotation (10 tasks, every loop, with the practice 20)

These cover classes the practice set lacks. They are exposed to tuning by design, so they can never
serve as held-out tasks. Keep each one cheap (budget 30 or less except ExpenseDeleteMultiple2 and
RetroPlayingQueue).

| # | Task | Verdict | Gaps | Budget | Class the practice set misses |
|---:|---|---|---|---:|---|
| 1 | ClockStopWatchPausedVerify | likely clear | - | 10 | verify-only, no state change; done must be accepted without acting |
| 2 | MarkorEditNote | needs | EDIT | 12 | editing existing content (caret/replace) |
| 3 | MarkorCreateNoteFromClipboard | needs | EDIT | 14 | clipboard paste into an editor |
| 4 | MarkorMoveNote | needs | LP | 14 | long-press/menu move on a named file |
| 5 | ExpenseDeleteMultiple2 | needs | FIND | 34 | scroll-search for named rows among 50 noise rows |
| 6 | SimpleCalendarDeleteOneEvent | needs | DATE | 12 | date navigation for a delete (no pickers) |
| 7 | SimpleCalendarNextMeetingWithPerson | needs | ANS | 10 | answer built from sourced date/time values (needs ANS only) |
| 8 | NotesIsTodo | needs | ANS | 10 | answer from structure in an unseen app (needs ANS only) |
| 9 | RetroPlayingQueue | likely clear | - | 32 | unseen media app, ordered multi-item selection |
| 10 | SimpleSmsReply | needs | SENDV | 12 | reply chosen by an owner-given number (reader pick) |

NextMeetingWithPerson and NotesIsTodo score 0 until the harness writes answers to the scorer (ANS);
that harness change comes first. Once EXT exists, add one extractive answer task, for example
SimpleCalendarNextEvent, to the rotation, choosing from tasks outside the held-out set.

## 7. What would change these verdicts

- One observation-only capture (no model calls) per never-observed app, to check whether its
  controls filter to labels or turn opaque. This bears on every (U) row, and it is the cheapest test
  of the *likely clear* verdicts for VLC, Retro Music, OsmAnd, Gallery, Draw, Clipper and the answer apps.
- Whether AndroidWorld's tree carries slider range info (SLIDER) and Tasks' priority text (VIS or not).
- Practice failures blamed here on a gap (KEYPAD, FIND, LP, EDIT, SENDV, MEDIA, DATE) come from a few
  seed-0 runs per task. Other causes are not ruled out.

