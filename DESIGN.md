# Curbside — design

An Android app that remembers where you parked, warns you before street cleaning takes the spot,
shares the location with your partner, and shows New York's alternate side rules on a map.

This document covers the decisions. The reasoning behind each subsystem is in `docs/`, and the code
carries the details.

---

## The problem, stated precisely

Three separate problems wear the same coat.

**Where is the car.** Easy in isolation, annoying in practice: you have to remember to tell the app,
and the one time you forget is the time you need it. So detection has to be automatic, and automatic
detection is where parking apps traditionally destroy the battery.

**When do I have to move it.** In New York this is the question that costs money. Alternate side
cleaning runs one to four times a week per curb, on a schedule that differs between the two sides of
the same street, suspended about thirty days a year on a calendar nobody memorises. A missed sweeping
is $65.

**Does my wife know where it is.** Shared cars need shared state. The obvious implementation — put
every parking location on someone's server forever — builds a detailed movement history of two
people as a side effect of a convenience feature.

---

## Shape of the system

```
┌────────────────── phone, mostly asleep ───────────────────┐
│                                                            │
│  Android Auto ─┐                                           │
│  car stereo   ─┼─► DriveStateMachine ──► one location fix  │
│  motion sensor─┘      (pure Kotlin)         (once/drive)   │
│                                │                           │
│                                ▼                           │
│                        CurbMatcher ──► which curb          │
│                                │                           │
│                                ▼                           │
│                       SweepSchedule ──► when to move       │
│                          (pure Kotlin)      │              │
│                                             ├─► alarm      │
│                                             ├─► map colour │
│                                             └─► encrypted  │
│                                                  share     │
└────────────────────────────────────────────────────────────┘
        ▲                                    ▲
        │ monthly, on wifi                   │ weekly
   curb dataset                        suspension calendar
   (built offline by tools/asp_pipeline.py)  (NYC 311 API)
```

Four Gradle modules:

| Module | What it is | Why separate |
| --- | --- | --- |
| `:asp-core` | Sign parsing, the weekly schedule engine, geometry, curb matching | Pure JVM. The logic most likely to be wrong and most expensive to be wrong about, tested in milliseconds without an emulator |
| `:drive-core` | The drive/park state machine | Pure JVM. Otherwise only testable by driving around Brooklyn |
| `:app` | Android: detection wiring, storage, map, UI, sharing | — |
| `tools/` | The offline data pipeline (Python) | Runs monthly on a build machine, not on phones |

The two pure modules carry **65 unit tests** and no Android dependency. That is the deliberate centre
of gravity of this design: almost everything that can be got wrong has been pushed into code that can
be tested for free.

---

## Decision 1 — detection without a location subscription

**The app never subscribes to continuous location.** Not at low power, not "only while driving". It
asks for a position exactly once per drive, at the moment it concludes you have parked.

That is possible because three signals tell you a drive has ended, and none of them is location:

| Signal | Cost | Wakes a killed app? | Precision |
| --- | --- | --- | --- |
| Activity transitions (`IN_VEHICLE` / `ON_FOOT`) | Sensor-hub accelerometer; nothing of ours runs between events | **Yes**, via PendingIntent | Tens of seconds |
| Car stereo Bluetooth ACL | Free; the radio is connected anyway | **Yes**, exempt implicit broadcast | Ignition-accurate |
| Android Auto `CarConnection` | One observer on an existing broadcast | No | Second-accurate |

They are complementary rather than redundant. Activity transitions are the only one that works from a
dead process with no hardware in the loop, so they are the floor. Bluetooth is the sharpest signal
that still wakes the app, and for most cars it tracks the ignition exactly. Android Auto is the
sharpest signal of all but only observable while our process happens to be alive — so it is wired up
as a *refinement*, not a foundation.

`DriveStateMachine` fuses them. It is pure, it has no clock, and it makes the decisions that are easy
to get wrong:

- A drive-ended signal is **debounced per source** — zero for activity transitions (the detector
  already debounced internally), 20s for a head-unit detach, 45s for Bluetooth, which drops out at
  random. A stereo that reconnects inside the window cancels the pending park.
- Walking **short-circuits every debounce**. If the phone sees you on foot, the car is not moving,
  whatever the stereo thinks.
- A "drive" under 90 seconds **never drops a pin** — that is sitting in the car with the radio on.
- A flapping stereo **cannot defer parking indefinitely**: the first source to report the end owns
  the deadline.

See `docs/drive-detection.md`.

## Decision 2 — one fix, with a free fallback for garages

When the machine says "parked", `LocationFixer` runs a cascade from free to expensive:

1. **The cached fused location**, if under 30 seconds old and better than 25m. After an Android Auto
   drive with navigation running this is already a good GPS fix, and the cascade stops here having
   spent nothing.
2. **One `getCurrentLocation` at high accuracy**, capped at 20 seconds. The only time the app turns
   the GPS on.
3. **The passive breadcrumb.**

The breadcrumb is the interesting one. During a drive — and only during a drive — the app holds a
`PRIORITY_PASSIVE` location subscription, which never asks the system to compute a fix and only
receives ones other apps already caused. With navigation running on the head unit that is a free
high-quality track. It exists for exactly one case: the car goes into an underground garage, the live
fix is absent or a 300m guess, and the last passive fix is the garage entrance. That is the
difference between "somewhere in this neighbourhood" and "in this garage".

Only the most recent breadcrumb is kept. A track of your driving is a privacy liability and nobody
asked for one.

The capture runs as **expedited WorkManager work**, not a foreground service: since Android 12 an app
cannot generally start a foreground service from the background, and a broadcast receiver's ten
seconds is not enough for a cold fix.

See `docs/battery.md` for where the energy actually goes.

## Decision 3 — the schedule engine is shared and pure

`SweepSchedule` expands weekly rules into dated windows in `America/New_York` and grades the result
onto an eight-value status. The same function feeds the map colour, the home screen countdown, and
the reminder alarm — so those three can never disagree, which is a class of bug this design simply
does not have.

Things it gets right, each pinned by a test:

- **DST.** An 8:00am sweeping is 8:00am local on both sides of the March and November shifts, even
  though those two dates are 23 and 25 hours apart.
- **Suspensions apply to street cleaning and nothing else.** A no-standing rush hour rule is not
  lifted by a holiday.
- **A suspension never hides an imminent window.** If today's cleaning is cancelled but another rule
  bites within 12 hours, the map shows the real upcoming window rather than the reassuring badge.
- **An unparsed sign is `UNKNOWN`, never `CLEAR`.** A sign we could not read is not a sign that says
  "park here". This is the single most important rule in the codebase: every ambiguity resolves
  towards telling the user less rather than promising them more.
- **A curb with no data is `UNKNOWN` too**, drawn faintly so it reads as absence rather than
  permission.

## Decision 4 — sides of the street

Both curbs of a block share one centreline in the city's data. Their perpendicular distance from a
parked car is near-identical, and their cleaning schedules are usually different days. So:

- `CurbMatcher` decides the side by which hand of the centreline the fix falls on, using a cross
  product in a local metre frame.
- **A fix worse than 12m cannot resolve a side on a typical NYC street.** In that case the app does
  not guess: it reports the block, flags `needsSideConfirmation`, and asks the user in one tap. It
  only asks when the two sides actually disagree about the schedule.
- Correct side beats marginally-closer wrong side in the ranking. Being 3m from the curb you are not
  parked against is not a better answer than 4m from the one you are.

## Decision 5 — the map colours a time axis, not a taxonomy

The ramp answers one question — *how long until I must move* — and runs red → orange → amber →
lime → green over it. Two statuses sit deliberately off that axis because they are not points on it:
blue for "suspended today" (the rule exists but is lifted) and purple for "no parking anytime" (no
amount of waiting helps).

| Status | Light | Dark | Stroke | Meaning |
| --- | --- | --- | --- | --- |
| `RESTRICTED_NOW` | `#C81E3C` red | `#FF6B81` | dashed, 5.0dp | A window is open. You are being ticketed |
| `MOVE_WITHIN_HOUR` | `#E8590C` orange | `#FF9A52` | dashed, 4.5dp | Under an hour out |
| `MOVE_TODAY` | `#E0A100` amber | `#FFD24A` | solid, 4.0dp | Later today or overnight |
| `MOVE_IN_TWO_DAYS` | `#8CB33A` lime | `#B7E05A` | solid, 3.5dp | Within two days |
| `CLEAR` | `#2E8B57` green | `#4FD18B` | solid, 3.0dp | Nothing for 2+ days |
| `SUSPENDED_TODAY` | `#3D7EBB` blue | `#7FB8EE` | solid, 3.5dp | Alternate side lifted today |
| `ALWAYS_RESTRICTED` | `#6B4C9A` purple | `#B69AE0` | solid, 4.0dp | No standing at any hour |
| `UNKNOWN` | `#9AA0A6` grey | `#6E7479` | dotted, 2.5dp | No usable sign data |

Red and green are the deuteranopia collision, so **colour is never the only channel**: the two urgent
statuses get a dashed casing and unknown gets a dotted one, line width scales with urgency, and the
legend spells out the time bucket in words. A second palette lifts every hue for dark basemaps.

Two implementation notes that matter:

- **Status is computed in Kotlin, not in a style expression.** MapLibre expressions have no date
  arithmetic, no timezones and no suspension calendar, so encoding schedules as feature properties
  would mean reimplementing the rule engine badly in JSON. Instead each feature carries a status
  string and the style just maps string to colour. A viewport is a few thousand curbs; evaluating
  them is single-digit milliseconds off the main thread.
- **A time scrubber shifts the map's clock forward up to 48 hours.** "Where can I leave the car until
  Thursday" is the question New Yorkers actually ask, and it is unanswerable from a map that only
  shows the present.

## Decision 6 — sharing that the server cannot read

Auto-sharing would otherwise leave a server holding a timestamped record of where a couple parks,
for years — which reveals where they live, where they work, and where they were on any evening.
There is no product reason for anyone but the two of them to read it.

So: one AES-256-GCM key per household, generated on the first device and transferred to the second by
**QR code** — the air gap of holding one phone up to another, which needs no key agreement protocol
and beats anything that goes over the wire. Firestore stores `{from, at, envelope}`: two user ids, a
timestamp, and an opaque blob. The push notification carries only "record X exists", so the device
fetches and decrypts locally and the end-to-end property survives the notification path, which is
usually where such schemes quietly leak.

Firestore is doing three jobs — durable storage, real-time fan-out, and an offline write queue — and
the offline queue is what makes underground-garage sharing work with no retry logic of ours.

Deliberate limits: the server still sees *who* shares with *whom* and *when*, because it has to route
the push. Hiding that is a much larger project. And losing both phones loses the history, because
there is no server-side key to recover with. That is the correct trade.

Auto-share is **off by default**, there is a per-event suppress, and manual share is a plain Android
share sheet with a maps link — which works with anyone, needs no account, and is what you want when
you are texting a friend rather than your partner.

## Decision 7 — the city's data is preprocessed, not parsed on device

`tools/asp_pipeline.py` pulls the ~1M-row DOT sign inventory and the street centrelines, parses the
free-text sign copy, groups signs into per-side curbs, joins geometry, works out each curb's offset
sign, and emits a gzipped JSON-lines bundle with a content-addressed version. The app downloads it
once on wifi and refreshes monthly; the sign inventory changes slowly. Suspensions, which change
weekly, come from a separate and much smaller feed.

The pipeline's parser and `asp-core`'s parser are two implementations of the same rules in two
languages, so **both are tested against the same corpus with the same expected output**
(`tools/test_asp_pipeline.py` mirrors `SignParserTest.kt`). `--report-unparsed` prints the sign copy
a build could not read, which is the input to improving both.

Everything the app needs to answer "when do I move my car" is then local: the map works on the
subway, in a garage, and on the last five percent of a battery without a radio ever waking up.

---

## What this design does not do

- **No live parking availability.** Nobody has that data for New York and pretending otherwise is how
  parking apps lose trust.
- **No trip history.** One breadcrumb, overwritten.
- **No account required** for the core app. Sharing needs an anonymous Firebase identity; everything
  else works signed out.
- **No street sweeping predictions.** The app reports the posted schedule and the city's published
  suspensions. It does not guess whether the sweeper will actually show up.

## Open questions

- **Sign-to-block joining is the weakest link.** The DOT inventory keys signs by street name triples,
  not segment ids, and the current pipeline joins geometry by street label alone. A proper join
  against LION segment ids would materially improve coverage; `stats["no_geometry"]` in the pipeline
  output is the metric to watch.
- **Bluetooth-less cars.** Detection degrades to activity transitions only, which costs perhaps a
  minute of latency. Worth measuring before adding anything.
- **Reading the sign photos.** The DOT publishes sign images. OCR on the ones the text parser cannot
  read would close most of the remaining gap, offline, in the pipeline.
