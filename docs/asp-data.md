# Alternate side data

Where the rules come from and how they get onto the phone.

## Sources

| What | Dataset | Size | Refresh |
| --- | --- | --- | --- |
| Parking regulation signs | [`nfid-uabd`](https://data.cityofnewyork.us/Transportation/Parking-Regulation-Locations-and-Signs/nfid-uabd) on NYC Open Data | ~1M rows | Monthly |
| Street centrelines (CSCL) | [`inkn-q76z`](https://data.cityofnewyork.us/City-Government/Centerline/inkn-q76z) | ~120k segments | Monthly |
| Alternate side suspensions | [NYC 311 public API](https://api-portal.nyc.gov/), `GET https://api.nyc.gov/public/api/GetCalendar?fromdate=&todate=` with an `Ocp-Apim-Subscription-Key` header | ~90 days | Weekly |

The first two are preprocessed offline. Only the third is fetched by the app, and it is a few
kilobytes.

## Why preprocess

Parsing a million rows of transcribed sign copy and spatially joining them to street geometry is
minutes of CPU and tens of megabytes. Doing it per device, monthly, would be the largest single cost
in the app by a wide margin — larger than everything else combined. `tools/asp_pipeline.py` does it
once on a build machine and publishes a bundle.

## The bundle

Content-addressed, so rebuilding unchanged input produces an unchanged version and the app skips the
download:

```
manifest.json               {"version": "a1b2c3d4e5f6", "path": "segments-a1b2c3d4e5f6.jsonl.gz", ...}
segments-<version>.jsonl.gz one JSON object per line
```

Each line is one curb — one side of one block:

```json
{
  "id": "3f2a1b9c4d5e6f70",
  "on": "BERGEN ST", "from": "5 AV", "to": "6 AV",
  "side": "NORTH", "sign": -1,
  "bbox": [40.68210, -73.98010, 40.68212, -73.97880],
  "geom": "<encoded polyline, precision 6>",
  "rules": "[{\"k\":\"STREET_CLEANING\",\"d\":9,\"s\":480,\"e\":570}]"
}
```

Fields worth explaining:

- **`id`** hashes the street name, the two cross streets *in sorted order*, and the side. Sorting the
  cross streets matters because the city records the same block as "5 Av to 6 Av" in one row and
  "6 Av to 5 Av" in another, depending on which way the survey crew walked. A stable id means a saved
  parking spot keeps pointing at the same curb across dataset refreshes.
- **`sign`** is +1 if this curb is on the right hand of the geometry's direction of travel, -1 for
  the left, 0 when unknown. Both sides of a street share one centreline, so this is the only thing
  distinguishing them, and it depends on which way the city happened to draw the segment — hence
  computed per segment rather than assumed.
- **`rules`** is a compact DTO array: `k` kind, `d` weekday bitmask with bit 0 = Monday, `s`/`e`
  minutes past midnight. Repeated across ~150k rows, so terseness is worth the opacity. `RegulationCodec`
  on the app side is the matching decoder.

## Parser parity

The sign parser exists twice: `SignParser.kt` for anything the app ever needs to parse directly, and
the same rules in Python in the pipeline. They must agree, or the app's rule engine reasons about a
schedule the pipeline never intended.

So both are tested against the same corpus with the same expected output —
`tools/test_asp_pipeline.py` mirrors `SignParserTest.kt` case for case. When a new phrasing turns up,
add it to both files first.

What the parser handles today:

```
NO PARKING (SANITATION BROOM SYMBOL) 11:30AM-1PM TUES & FRI
NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS      ← meridiem inferred from the right end
NO PARKING (SANITATION BROOM SYMBOL) 11-12:30PM WED          ← and not inferred into a wrapped window
NO STANDING 7AM-10AM MON THRU FRI                            ← day ranges, hyphen or THRU
NO STANDING ANYTIME                                          ← all week, no window
2 HOUR PARKING 9AM-7PM EXCEPT SUNDAY                         ← EXCEPT inverts
1 HOUR METERED PARKING 9AM-10PM INCLUDING SUNDAY             ← INCLUDING is emphasis, not restriction
NO PARKING MIDNIGHT-4AM / NO STOPPING 7AM-MIDNIGHT           ← midnight means 00:00 or 23:59 by position
```

Anything else becomes `OTHER` with no days, which the rule engine reports as `UNKNOWN`. Run the
pipeline with `--report-unparsed` to see the most common phrasings a build could not read; that list
is the work queue.

## On-device storage

One Room table, `curb_segments`, with four indexed bounding-box columns. A map pan is a single
indexed range query rather than a scan over 150k rows. SQLite's R\*Tree module would be tidier but is
not enabled in every Android build, and four indexed doubles are fast enough for a viewport.

The query is an **overlap** test, not containment: a segment that merely crosses the viewport must
still be drawn, or long blocks vanish when you zoom in on their middle.

Refreshes replace the table wholesale inside one transaction. A half-updated curb table would show
new rules on some blocks and stale rules on others with no way to tell which.

## Suspensions

The city lifts alternate side about thirty days a year — major holidays, religious observances, snow.
Getting this wrong is the app's most embarrassing possible failure: telling someone to go out at 7am
on Yom Kippur to move a car that did not need moving.

`SuspensionCalendar` carries its coverage window, and `knows(date)` distinguishes "not suspended" from
"we have no idea". Anything past the fetched window is reported as unknown rather than assumed clear.

Suspensions apply to `STREET_CLEANING` and nothing else — a rush-hour no-standing rule is not lifted
by a holiday. And a suspension never hides an imminent window: if today's cleaning is cancelled but
another rule bites within twelve hours, the map shows the real upcoming window instead of the
reassuring blue badge.

## Known gaps

- **The geometry join is the weakest link.** The DOT inventory keys signs by street-name triples
  rather than segment ids, and the pipeline currently joins on street label alone. A proper join
  against LION segment ids would materially improve coverage. Watch `no_geometry` in the pipeline's
  output.
- **Sign photos are unused.** The DOT publishes images. OCR on the ones the text parser cannot read
  would close most of the remaining gap, offline, in the pipeline.
- **No per-block suspensions.** The 311 feed is citywide. Block-level suspensions (construction, film
  shoots) are posted physically and are not in any feed.
