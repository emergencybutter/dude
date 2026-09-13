# Curbside

An Android app that remembers where you parked, warns you before alternate side cleaning takes the
spot, shares the location with your partner, and shows New York's street cleaning rules on a map.

Three things shape the design:

- **It costs nothing when you are not driving.** No continuous location subscription, no foreground
  service, no polling. Detection rides on the phone's motion coprocessor, your car stereo's Bluetooth
  link, and Android Auto's projection state. The GPS turns on once per drive, for a few seconds.
- **It never guesses in your favour.** A sign it cannot read is reported as unknown, not as "you're
  fine". A GPS fix too rough to tell which side of the street you are on asks you rather than
  picking, because the two sides are cleaned on different days.
- **The server cannot read where your car is.** Household sharing is end-to-end encrypted with a key
  that travels between phones as a QR code and nowhere else.

Read [`DESIGN.md`](DESIGN.md) for the architecture and the reasoning. Subsystem detail is in
[`docs/`](docs/).

## Status

**It compiles. It has not run.** All four parts build and their tests pass, and `:app` packages a
debug APK. That is the whole of the claim: a green `assembleDebug` says the code compiles and
packages, not that a drive is detected, a reminder fires, or a map draws. Nothing here has been on a
phone.

| Part | State |
| --- | --- |
| `:asp-core` — sign parsing, schedule engine, geometry, curb matching | **49 tests passing** |
| `:drive-core` — drive/park state machine | **16 tests passing** |
| `tools/asp_pipeline.py` — offline data pipeline | **24 tests passing** |
| `:app` — detection, storage, map, UI, sharing | **Debug APK builds.** No unit tests of its own |
| Library versions in `gradle/libs.versions.toml` | Verified — every one resolved as written |

The first real build needed two fixes, both mechanical: the `@AndroidEntryPoint` receivers called
`super.onReceive`, which Kotlin rejects because `BroadcastReceiver.onReceive` is abstract (Hilt's
Gradle plugin splices the injection call in at bytecode level, so the call was never needed), and
three files used the reified `Json.encodeToString(value)` without importing it, so the call bound to
the two-arg overload instead. What has *not* been checked is everything a compiler cannot see: the
runtime behaviour of the detection wiring, the Room schema against a real database, the map.

## Layout

```
asp-core/     Pure JVM. Sign parsing, the weekly schedule engine, geometry, curb matching.
drive-core/   Pure JVM. The state machine that decides a drive has ended.
app/          Android. Detection wiring, Room storage, MapLibre map, Compose UI, sharing.
tools/        Python. The offline pipeline that builds the curb dataset from NYC open data.
docs/         Subsystem design notes.
firestore.rules
```

The two pure modules exist so the logic most likely to be wrong is testable in milliseconds without
an emulator. That is where 65 of the 89 tests live.

## Building

Requires JDK 17 and the Android SDK — `platforms;android-35` and `build-tools;35.0.0`. Point
`sdk.dir` in `local.properties` at it, or set `ANDROID_HOME`.

```bash
./gradlew :asp-core:test :drive-core:test    # the logic, no SDK needed
./gradlew :app:assembleDebug                 # -> app/build/outputs/apk/debug/app-debug.apk
python3 tools/test_asp_pipeline.py
```

### Configuration

Secrets stay out of the repository. Put them in `local.properties` (gitignored) or supply them as
environment variables in CI. All three are optional — missing keys degrade a feature rather than
failing the build, so a fresh clone compiles and runs.

| Key | What it does if missing |
| --- | --- |
| `MAP_STYLE_URL` | A MapLibre style URL for the basemap. Blank means a blank basemap; the curb overlay still draws. `https://tiles.openfreemap.org/styles/liberty` needs no key and has the street detail this is useless without |
| `NYC_311_API_KEY` | Free from the [NYC API portal](https://api-portal.nyc.gov/). Blank means no suspension calendar, so holidays are treated as ordinary days |
| `ASP_DATASET_BASE_URL` | Where `manifest.json` and the segment bundle are hosted. Blank means no curb data, so the map is empty and parking spots get no schedule |

Sharing additionally needs a Firebase project and `app/google-services.json`. Deploy
`firestore.rules` alongside it; the default rules will not do.

### Building the curb dataset

```bash
python3 tools/asp_pipeline.py --out dist --borough Brooklyn --report-unparsed
```

Publish `dist/` at whatever `ASP_DATASET_BASE_URL` points at. `--report-unparsed` lists the sign copy
the parser could not read, which is the work queue for improving it. See
[`docs/asp-data.md`](docs/asp-data.md).

## Permissions, and why

| Permission | Used for |
| --- | --- |
| `ACCESS_FINE_LOCATION` | The one fix taken per drive, and the passive breadcrumb |
| `ACCESS_BACKGROUND_LOCATION` | The fix happens after you have walked away from your phone's screen. Requested separately, after the app has demonstrably worked, because asking cold gets it denied |
| `ACTIVITY_RECOGNITION` | Vehicle and walking transitions — the signal that works with the app killed |
| `BLUETOOTH_CONNECT` | Recognising your car stereo specifically, so headphones are not mistaken for a car |
| `POST_NOTIFICATIONS` | The reminder before cleaning starts |
| `SCHEDULE_EXACT_ALARM` | The reminder lands on time. Degrades to a ten-minute-early window when not granted |

An explanation screen comes first, before Android's own dialogs, and covers the four that
detection depends on: activity recognition, location, background location and notifications.
They fail silently — the moment they are needed is the moment you have walked away from the
phone, so a denial shows up as no pin and no warning rather than as an error. The screen says
what each one buys and what breaks without it, then hands over to the system prompts. It
appears once and lives on under Settings -> Review permissions. Bluetooth is asked for where it
is used, when you pick your car stereo.

`ACCESS_BACKGROUND_LOCATION` is asked for on its own, after foreground location is held, and on
Android 11+ by sending the user to the settings page: the platform stopped offering "all the
time" in a dialog, so a request there would come straight back denied.
