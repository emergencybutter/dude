# Drive detection

How the app knows a drive started and, more importantly, ended.

## The three signals

### Activity transitions — the floor

`ActivityRecognition.requestActivityTransitionUpdates` with `IN_VEHICLE` enter/exit and `ON_FOOT`
enter. Delivered to a manifest `BroadcastReceiver` by PendingIntent, so it fires with the app killed.
The detector runs on the phone's sensor hub — the low-power core already awake counting steps —
reading only the accelerometer. Nothing of ours runs between events; the process does not exist.

This is the only signal that works with no hardware in the car and no app running, so everything
else is layered on top of it rather than replacing it.

Its weakness is latency: the detector needs tens of seconds of evidence before it will call a
transition, and it is conservative about `IN_VEHICLE` exit specifically because stopping at a light
looks a lot like arriving. That conservatism is a feature here — it means the app can trust an exit
transition without debouncing it further.

### Car stereo Bluetooth — the sharp edge that still wakes you

`ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the implicit-broadcast exemption list, so a
manifest receiver still starts a dead process for them. For most cars the stereo powers down with the
ignition, which makes this the most ignition-accurate signal available.

**Only the device the user nominated in Settings counts.** Without that filter every pair of
headphones in the city looks like a car, and walking to the subway with earbuds in would drop a
parking pin on the pavement.

It flaps. Cars drop and re-establish the link at random, which is why it gets the longest debounce.

### Android Auto — the sharpest, and the least available

`androidx.car.app.connection.CarConnection` exposes head-unit projection state as a LiveData. When
the user unplugs, we know within a second, with no heuristics.

The catch is structural: `CarConnection` is backed by a content provider and a context-registered
receiver, so it only reports **while our process is alive**. It cannot wake a killed app. It is
therefore wired as a refinement — whenever the app happens to be running it produces a sharper edge
than activity recognition would, and when it is not, the other two carry the load.

`CarConnectionMonitor` also ignores `CONNECTION_TYPE_NATIVE`. That means the app is running *on*
Android Automotive OS, where the phone is the car and there is no projection edge to detect.

## Fusion

`DriveStateMachine` (in `:drive-core`, pure Kotlin, no clock, no Android) takes signals and returns
actions. Three phases:

```
        DRIVE_STARTED                    DRIVE_ENDED (debounced)
IDLE ─────────────────► DRIVING ──────────────────────► CONFIRMING_PARK
 ▲                        │  ▲                                │
 │                        │  └────── DRIVE_STARTED ───────────┘
 │                        │          (cancel the check)
 │      WALKING_STARTED   │                                   │
 └────────────────────────┴───────────────────────────────────┘
              CapturePark / DiscardShortTrip
```

Per-source debounce on `DRIVE_ENDED`:

| Source | Debounce | Why |
| --- | --- | --- |
| Activity recognition | 0s | The detector already debounced internally; a transition is a considered opinion, not a sample |
| Manual | 0s | The user pressed a button |
| Android Auto | 20s | Unplugging is nearly always real, but people do reseat a cable |
| Car Bluetooth | 45s | Dropouts happen; the cost of waiting is a later notification, the cost of not waiting is a pin at a traffic light |

Four rules that only exist because the naive version gets them wrong:

1. **Walking short-circuits every debounce.** On foot means the car is stationary, whatever the
   stereo says. A pending confirmation fires immediately, and a drive we never saw end is closed out.
2. **A trip under 90 seconds never drops a pin.** That is sitting in the car with the radio on, or
   moving it ten feet.
3. **The first source to report the end owns the deadline.** A second `DRIVE_ENDED` does not push it
   back, so a flapping stereo cannot defer parking indefinitely.
4. **A drive over 12 hours is abandoned, with no pin.** The phone died, the receiver was dropped, the
   user walked off without their phone. We have no idea where the car was left, and a wrong pin is
   worse than none.

All four are pinned by tests in `DriveStateMachineTest`.

## Carrying out the decision

`DriveCoordinator` is the Android half. Every entry point runs from a broadcast receiver in a process
that may be three milliseconds old, so nothing is held in memory: the state round-trips through
DataStore each time.

- **The debounce** is one `AlarmManager` alarm per drive, lasting under a minute.
  `setExactAndAllowWhileIdle` when the user has granted exact alarms — the whole point of the
  debounce is that it expires at a known moment, and Doze deferring it by fifteen minutes would pin
  the car wherever the phone happened to be when the alarm finally landed. Without the permission,
  `setWindow` is close enough, since the walking transition usually arrives first anyway.
- **The capture** is expedited WorkManager work. Since Android 12 an app cannot generally start a
  foreground service from the background, and a receiver's ten seconds is not enough for a cold GPS
  fix. Expedited work is the sanctioned path: it runs immediately, promotes itself to a
  location-typed foreground service where the platform requires one, and degrades to ordinary work
  if the app has burned its quota.

## Failure modes, and what happens

| Failure | Result |
| --- | --- |
| Play Services missing or stale | Transitions unavailable; Bluetooth and manual capture still work. Settings shows the degraded state instead of pretending |
| Activity recognition permission denied | Same, and `ensureRegistered` records it so the UI can ask |
| No car stereo nominated | Bluetooth signal unused; detection falls back to transitions, costing perhaps a minute of latency |
| Phone rebooted mid-drive | `BootReceiver` re-registers transitions and calls `reconcile`, which flushes a due confirmation or abandons a stale drive |
| Alarm dropped by the system | `reconcile` runs on app launch and from the weekly maintenance worker, and `onTimer` re-checks whether the confirmation is actually due rather than trusting the caller |
| No location and no breadcrumb | A notification says so and invites the user to drop a pin. Retrying later would pin the car wherever the phone is by then, which is worse than nothing |
