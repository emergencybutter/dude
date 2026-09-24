# Battery

The design constraint that shaped everything else: **the app must be indistinguishable from not
being installed, on a day when you do not drive.**

## Where a parking app normally goes wrong

The obvious implementation subscribes to fused location at balanced power and watches for the
velocity to drop. That keeps GPS or at least Wi-Fi scanning warm all day, wakes the CPU every few
seconds to process fixes, and holds a foreground service notification to stay alive under Doze —
for a signal that is actually needed about twice a day.

Curbside inverts it. Nothing runs unless something happened.

## What actually runs

| When | What | Cost |
| --- | --- | --- |
| Always | One registered activity-transition request | Sensor hub only. No app process, no CPU wakeups between transitions |
| Always | Two manifest Bluetooth receivers | Zero until the system delivers a broadcast |
| A drive starts | Passive location subscription | Never causes a fix; only receives ones other apps already caused |
| A drive ends | One alarm (<60s), then one expedited worker | Seconds of CPU, at most one GPS session |
| A car is parked | Two alarms: a day before the cleaning window, and an hour before | Two wake-ups over a whole parking. The day-ahead one is inexact, so the system batches it |
| Weekly, on wifi, charged | Maintenance worker: suspension calendar, dataset check, share retries | One HTTP round trip, usually a 304-equivalent no-op |
| Monthly, on wifi | Curb dataset refresh, if a new version exists | A few MB, once |
| Map open | Viewport query + evaluation on camera idle, status recompute every 60s | Only while the user is looking at it |

Idle days do genuinely nothing. There is no periodic location work, no polling, and no persistent
foreground service.

## The passive breadcrumb is free

`PRIORITY_PASSIVE` is the one piece that looks expensive and is not. It never asks the system to
compute a location; it asks to be told about locations *other* apps have already caused to be
computed. Its marginal cost is the delivery.

During an Android Auto drive there is nearly always a navigation app requesting fixes at 1Hz, so a
passive subscription gets a continuous high-quality track for nothing. On a drive with no navigation
it may receive nothing at all, which is fine — it is a fallback, not the primary source. It is
subscribed only between the start and end of one drive.

## The one expensive thing

`getCurrentLocation(PRIORITY_HIGH_ACCURACY)`, capped at 20 seconds, at most once per drive — and
skipped entirely when the cached fused location is under 30 seconds old and better than 25m, which
after a navigated drive it usually is. A typical commuter pays for this twice a day, for a few
seconds each time.

## Measuring it

These are design targets, not measurements — nobody has run this on a phone yet. The way to check
them, in order of usefulness:

1. **Battery Historian** over a 24-hour trace with two drives. Look for: zero GPS sessions outside
   the two capture windows, zero partial wakelocks attributed to the app on idle hours, and the
   activity-transition subscription showing no `SensorService` CPU attributed to us.
2. **`adb shell dumpsys batterystats --charged nyc.curbside`** — the numbers that matter are
   `Wake lock` time and `GPS` time. A day with two drives should show single-digit seconds of each.
3. **`adb shell dumpsys jobscheduler | grep curbside`** — should show exactly one periodic job, and
   it should have unmetered and battery-not-low constraints.
4. **Standby buckets.** After a few no-drive days the app should land in `RARE` or `RESTRICTED` and
   keep working, because nothing it depends on requires a better bucket: PendingIntent-delivered
   transitions and exempt Bluetooth broadcasts both survive there. Force it with
   `adb shell am set-standby-bucket nyc.curbside restricted` and confirm a drive is still detected.

If any of those disagree with this document, this document is wrong.

## Things deliberately not done

- **No geofence around the parked car** to detect driving away. Geofences are cheap but not free,
  and the same three signals already cover it: the next drive's start is the previous parking's end.
- **No significant-motion sensor.** It duplicates what activity transitions already provide at the
  same power tier, with worse semantics.
- **No foreground service during drives.** Nothing needs to run during a drive except the passive
  subscription, which does not require one.
- **No "battery saver" mode setting.** A setting that trades accuracy for power implies the default
  is wasteful. Better not to be.
