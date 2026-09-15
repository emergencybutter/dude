#!/usr/bin/env python3
"""Build the on-device curb dataset from NYC open data.

The app never touches NYC Open Data directly. Doing it on the phone would mean pulling roughly a
million sign rows, parsing free-text sign copy, and spatially joining the result to street
centrelines — minutes of CPU and tens of megabytes, repeated on every device that installs the app.

This script does that work once. It emits a gzipped JSON-lines bundle of pre-parsed curb segments
plus a manifest, which the app downloads on first run and refreshes monthly. See
``docs/asp-data.md`` for the data model and ``AspDatasetInstaller.kt`` for the consuming side.

Inputs (both public, no key required):

* Parking Regulation Locations and Signs — ``nfid-uabd`` on data.cityofnewyork.us. One row per
  sign, carrying the sign copy, the block it is on, and which side of the street.
* NYC Street Centerline (CSCL) — ``inkn-q76z``. One LineString per street segment.

Output::

    dist/manifest.json          {"version": "...", "path": "...", "segment_count": N}
    dist/segments-<version>.jsonl.gz

Usage::

    python3 tools/asp_pipeline.py --out dist
    python3 tools/asp_pipeline.py --out dist --borough Brooklyn --limit 5000
    python3 tools/asp_pipeline.py --out dist --signs cached/signs.json --centerlines cached/cscl.json
    python3 tools/asp_pipeline.py --out app/src/main/assets/asp --seed

The parsing rules mirror ``asp-core``'s ``SignParser`` exactly; ``--report-unparsed`` prints the
sign descriptions this build could not read, which is the input to improving both.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from typing import Iterable, Iterator, Sequence

SOCRATA_HOST = "https://data.cityofnewyork.us"

# Deliberately no .gz: see write_bundle.
SEED_FILENAME = "segments.bundle"
SIGNS_DATASET = "nfid-uabd"
CENTERLINE_DATASET = "inkn-q76z"
PAGE_SIZE = 50_000

# ---------------------------------------------------------------------------
# Sign parsing. Kept deliberately in step with asp-core/SignParser.kt; the test
# corpus in tests/test_asp_pipeline.py uses the same strings as SignParserTest.
# ---------------------------------------------------------------------------

MONDAY, SUNDAY = 0, 6
ALL_DAYS = frozenset(range(7))
WEEKDAYS = frozenset(range(5))
END_OF_DAY = 23 * 60 + 59

DAY_TOKENS: list[tuple[re.Pattern[str], int]] = [
    (re.compile(r"\bMONDAYS?\b|\bMONS?\b"), 0),
    (re.compile(r"\bTUESDAYS?\b|\bTUES\b|\bTUE\b|\bTU\b"), 1),
    (re.compile(r"\bWEDNESDAYS?\b|\bWEDS\b|\bWED\b"), 2),
    (re.compile(r"\bTHURSDAYS?\b|\bTHURS\b|\bTHUR\b|\bTHU\b|\bTH\b"), 3),
    (re.compile(r"\bFRIDAYS?\b|\bFRI\b"), 4),
    (re.compile(r"\bSATURDAYS?\b|\bSATS?\b"), 5),
    (re.compile(r"\bSUNDAYS?\b|\bSUNS?\b"), 6),
]

ANY_DAY = (
    r"MON(?:DAYS?|S)?|TUES?(?:DAYS?)?|TU|WED(?:NESDAYS?|S)?|"
    r"THU(?:RS?|RSDAYS?)?|TH|FRI(?:DAYS?)?|SAT(?:URDAYS?|S)?|SUN(?:DAYS?|S)?"
)
DAY_RANGE = re.compile(rf"\b({ANY_DAY})\s*(?:-|–|—|THRU|THROUGH|TO)\s*({ANY_DAY})\b")

ENDPOINT = r"(?:\d{1,2}(?::\d{2})?\s*(?:AM|PM)?|MIDNIGHT|NOON)"
RANGE = re.compile(rf"\b({ENDPOINT})\s*(?:-|–|—|\bTO\b|\bTILL?\b)\s*({ENDPOINT})\b")
ENDPOINT_PARTS = re.compile(r"^(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?$")

BROOM = re.compile(r"SANITATION\s+BROOM|BROOM\s+SYMBOL|STREET\s+CLEANING")
CLAUSE_SPLIT = re.compile(r"\s*(?:/|\r?\n)\s*")
TIME_LIMIT = re.compile(r"\b\d+\s*(?:HOUR|HR|MINUTE|MIN)\b")


@dataclass(frozen=True)
class Regulation:
    kind: str
    days: frozenset[int]
    start_minute: int | None
    end_minute: int | None
    raw: str
    days_inferred: bool = False
    #: Metres along the block's polyline this rule governs, or None for the whole curb. Set late,
    #: in extents_for_block, once the block's geometry and its signs' positions are both known.
    extent: tuple[float, float] | None = None

    def to_dto(self) -> dict:
        """The compact shape ``RegulationCodec`` on the app side expects.

        The original sign copy is deliberately not carried. It is a third of the uncompressed
        bundle — 13 MB of "(SUPERSEDES SP-369CA)" across 174k rules for the whole city — and Room
        stores it as text, so it was the largest single thing on disk. Nothing reads it: the app
        renders a status and a window, never the sign. Should a "why is this curb marked this way"
        screen ever want it, it comes back from the pipeline, not from every phone's database.
        """
        dto: dict = {
            "k": self.kind,
            "d": sum(1 << day for day in self.days),
        }
        if self.start_minute is not None and self.end_minute is not None:
            dto["s"] = self.start_minute
            dto["e"] = self.end_minute
        if self.days_inferred:
            dto["i"] = True
        if self.extent is not None:
            # Whole metres. A sign's own position is good to a few metres at best, so decimals here
            # would be false precision on top of survey error.
            dto["a"] = int(round(self.extent[0]))
            dto["b"] = int(round(self.extent[1]))
        return dto


def normalize(raw: str) -> str:
    return re.sub(r"[ \t]+", " ", raw.upper().replace("–", "-").replace("—", "-")).strip()


def parse_endpoint(token: str, assumed_pm: bool | None) -> int | None:
    token = token.strip()
    if token == "MIDNIGHT":
        return 0
    if token == "NOON":
        return 12 * 60

    match = ENDPOINT_PARTS.match(token)
    if not match:
        return None
    hour12 = int(match.group(1))
    if not 1 <= hour12 <= 12:
        return None
    minute = int(match.group(2) or 0)
    if not 0 <= minute <= 59:
        return None

    meridiem = match.group(3)
    pm = True if meridiem == "PM" else False if meridiem == "AM" else bool(assumed_pm)
    if hour12 == 12:
        hour24 = 12 if pm else 0
    else:
        hour24 = hour12 + 12 if pm else hour12
    return hour24 * 60 + minute


def has_meridiem(token: str) -> bool:
    """True when the endpoint states its own half of the day rather than borrowing one."""
    token = token.strip()
    if token in ("MIDNIGHT", "NOON"):
        return True
    match = ENDPOINT_PARTS.match(token)
    return bool(match and match.group(3))


def parse_window(clause: str) -> tuple[int, int] | None:
    """The window as written. ``start > end`` means it runs past midnight; see parse_sign."""
    match = RANGE.search(clause)
    if not match:
        return None
    left_raw, right_raw = match.group(1), match.group(2)

    # "7AM-MIDNIGHT" runs to the end of the day, not back round to zero.
    if right_raw.strip() == "MIDNIGHT":
        right = END_OF_DAY
    else:
        right = parse_endpoint(right_raw, None)
    if right is None:
        return None

    left = parse_endpoint(left_raw, assumed_pm=right >= 12 * 60)
    if left is None:
        return None
    if left >= right:
        # Two different things look identical here. "10PM-4AM" says PM outright and means a real
        # overnight window. "11-12:30PM" borrowed the right endpoint's PM and only looks like one;
        # the sign meant 11 in the morning. The sign's own meridiem decides which.
        if has_meridiem(left_raw):
            return left, right
        left = parse_endpoint(left_raw, assumed_pm=False)
    if left is None or left >= right:
        return None
    return left, right


def scan_days(text: str) -> frozenset[int]:
    return frozenset(day for pattern, day in DAY_TOKENS if pattern.search(text))


def expand_range(start: int, end: int) -> frozenset[int]:
    out: list[int] = []
    cursor = start
    while True:
        out.append(cursor)
        if cursor == end or len(out) > 7:
            break
        cursor = (cursor + 1) % 7
    return frozenset(out)


def single_day(token: str) -> int | None:
    for pattern, day in DAY_TOKENS:
        if pattern.search(token):
            return day
    return None


def next_day(days: frozenset[int]) -> frozenset[int]:
    """The days an overnight window spills into: Monday 10PM-4AM restricts Tuesday morning."""
    return frozenset((day + 1) % 7 for day in days)


def parse_days(clause: str) -> frozenset[int] | None:
    if "INCLUDING" in clause or "ALL DAYS" in clause:
        return ALL_DAYS

    except_match = re.search(r"\bEXCEPT\b(.*)$", clause)
    if except_match:
        excluded = scan_days(except_match.group(1))
        if excluded:
            return ALL_DAYS - excluded

    ranged: set[int] = set()
    remainder = clause
    for match in DAY_RANGE.finditer(clause):
        start, end = single_day(match.group(1)), single_day(match.group(2))
        if start is not None and end is not None:
            ranged |= expand_range(start, end)
            remainder = remainder.replace(match.group(0), " ")

    days = ranged | scan_days(remainder)
    if days:
        return frozenset(days)
    if "SCHOOL DAYS" in clause:
        return WEEKDAYS
    return None


def classify(clause: str) -> str:
    if BROOM.search(clause):
        return "STREET_CLEANING"
    if "NO STOPPING" in clause:
        return "NO_STOPPING"
    if "NO STANDING" in clause:
        return "NO_STANDING"
    if "NO PARKING" in clause:
        return "NO_PARKING"
    if TIME_LIMIT.search(clause) or "METERED" in clause or "MUNI-METER" in clause:
        return "TIME_LIMITED"
    return "OTHER"


def parse_sign(description: str) -> list[Regulation]:
    normalized = normalize(description)
    if not normalized:
        return []

    out: list[Regulation] = []
    for clause in CLAUSE_SPLIT.split(normalized):
        if not clause.strip():
            continue
        kind = classify(clause)
        window = parse_window(clause)

        if "ANYTIME" in clause or "ALL TIMES" in clause:
            out.append(Regulation(kind, ALL_DAYS, None, None, description, days_inferred=True))
            continue

        explicit = parse_days(clause)
        days = explicit if explicit is not None else (ALL_DAYS if window or kind != "OTHER" else frozenset())
        if not days:
            out.append(Regulation("OTHER", frozenset(), None, None, description))
            continue

        inferred = explicit is None

        # A window that runs past midnight becomes two, because a schedule is keyed by weekday and
        # "10PM Monday" and "4AM Tuesday" are different days. Left whole, the pair would either be
        # dropped or — worse, and what this used to do — collapse to a rule with no window at all,
        # which the engine reads as restricted around the clock. A seven-hour overnight ban is not
        # a permanent one.
        if window and window[0] > window[1]:
            out.append(Regulation(kind, days, window[0], END_OF_DAY, description, inferred))
            if window[1] > 0:
                out.append(Regulation(kind, next_day(days), 0, window[1], description, inferred))
            continue

        out.append(
            Regulation(
                kind=kind,
                days=days,
                start_minute=window[0] if window else None,
                end_minute=window[1] if window else None,
                raw=description,
                days_inferred=inferred,
            )
        )
    return out


# ---------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------

EARTH_RADIUS_M = 6_371_008.8

# How far a sign may stand from the centreline it is assigned to. Wide enough for a boulevard and a
# few metres of survey error, narrow enough that a same-named street in another borough never wins.
MAX_SIGN_TO_CURB_M = 250.0
METERS_PER_DEGREE_LAT = math.pi * EARTH_RADIUS_M / 180.0


def encode_polyline(points: Sequence[tuple[float, float]], precision: int = 6) -> str:
    """Google encoded polyline, matching ``PolylineCodec`` on the app side."""
    factor = 10**precision
    out: list[str] = []
    prev_lat = prev_lon = 0

    def encode_value(value: int) -> None:
        v = ~(value << 1) if value < 0 else (value << 1)
        while v >= 0x20:
            out.append(chr((0x20 | (v & 0x1F)) + 63))
            v >>= 5
        out.append(chr(v + 63))

    for lat, lon in points:
        lat_i, lon_i = round(lat * factor), round(lon * factor)
        encode_value(lat_i - prev_lat)
        encode_value(lon_i - prev_lon)
        prev_lat, prev_lon = lat_i, lon_i
    return "".join(out)


def side_sign(points: Sequence[tuple[float, float]], side: str) -> int:
    """Which hand of the digitised line the named side of the street falls on.

    Both curbs share one centreline, so the app distinguishes them by pushing each one off the line
    in opposite directions. The sign depends on which way the city happened to draw the segment, so
    it has to be worked out per segment rather than assumed.

    Returns +1 for the right hand of the direction of travel, -1 for the left, 0 when the side is
    unknown (the app then draws the curb on the centreline itself).
    """
    if side not in {"N", "S", "E", "W"} or len(points) < 2:
        return 0

    (lat1, lon1), (lat2, lon2) = points[0], points[-1]
    east = (lon2 - lon1) * METERS_PER_DEGREE_LAT * math.cos(math.radians(lat1))
    north = (lat2 - lat1) * METERS_PER_DEGREE_LAT

    # Outward normal for the named compass side, in the same local east/north frame.
    normal = {"N": (0.0, 1.0), "S": (0.0, -1.0), "E": (1.0, 0.0), "W": (-1.0, 0.0)}[side]

    # Cross product of the direction with the normal: negative means the normal points right.
    cross = east * normal[1] - north * normal[0]
    if abs(cross) < 1e-9:
        return 0
    return -1 if cross > 0 else 1


# The sign inventory positions every sign in NY State Plane Long Island (EPSG:2263), US survey
# feet. Converting is a Lambert Conformal Conic inverse, which is a page of arithmetic and avoids
# making pyproj a dependency of a script that otherwise needs nothing but the standard library.
_GRS80_A = 6_378_137.0
_GRS80_E = math.sqrt(2 * (1 / 298.257222101) - (1 / 298.257222101) ** 2)
_US_FOOT = 1200.0 / 3937.0
_LCC_LAT0 = math.radians(40 + 10 / 60.0)
_LCC_LON0 = math.radians(-74.0)
_LCC_SP1 = math.radians(41 + 2 / 60.0)
_LCC_SP2 = math.radians(40 + 40 / 60.0)
_LCC_FALSE_EASTING_M = 300_000.0


def _lcc_m(lat: float) -> float:
    return math.cos(lat) / math.sqrt(1 - _GRS80_E**2 * math.sin(lat) ** 2)


def _lcc_t(lat: float) -> float:
    sin_lat = _GRS80_E * math.sin(lat)
    return math.tan(math.pi / 4 - lat / 2) / ((1 - sin_lat) / (1 + sin_lat)) ** (_GRS80_E / 2)


_LCC_N = (math.log(_lcc_m(_LCC_SP1)) - math.log(_lcc_m(_LCC_SP2))) / (
    math.log(_lcc_t(_LCC_SP1)) - math.log(_lcc_t(_LCC_SP2))
)
_LCC_F = _lcc_m(_LCC_SP1) / (_LCC_N * _lcc_t(_LCC_SP1) ** _LCC_N)
_LCC_RF = _GRS80_A * _LCC_F * _lcc_t(_LCC_LAT0) ** _LCC_N


def state_plane_to_wgs84(x_feet: float, y_feet: float) -> tuple[float, float]:
    """EPSG:2263 to (lat, lon). Accurate to a few metres, which is far finer than a city block."""
    x = x_feet * _US_FOOT - _LCC_FALSE_EASTING_M
    y = y_feet * _US_FOOT
    radius = math.copysign(math.hypot(x, _LCC_RF - y), _LCC_N)
    t = (radius / (_GRS80_A * _LCC_F)) ** (1 / _LCC_N)
    lon = math.atan2(x, _LCC_RF - y) / _LCC_N + _LCC_LON0

    lat = math.pi / 2 - 2 * math.atan(t)
    for _ in range(12):
        sin_lat = _GRS80_E * math.sin(lat)
        lat = math.pi / 2 - 2 * math.atan(t * ((1 - sin_lat) / (1 + sin_lat)) ** (_GRS80_E / 2))
    return math.degrees(lat), math.degrees(lon)


def distance_to_polyline(point: tuple[float, float], points: Sequence[tuple[float, float]]) -> float:
    """Metres from a point to the nearest place on a polyline, flat-earth over a few hundred."""
    lat, lon = point
    scale = math.cos(math.radians(lat))
    px, py = lon * scale, lat
    best = float("inf")
    for (alat, alon), (blat, blon) in zip(points, points[1:]):
        ax, ay = alon * scale, alat
        bx, by = blon * scale, blat
        dx, dy = bx - ax, by - ay
        span = dx * dx + dy * dy
        t = 0.0 if span == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / span))
        ddx, ddy = px - (ax + t * dx), py - (ay + t * dy)
        best = min(best, math.hypot(ddx, ddy))
    return best * METERS_PER_DEGREE_LAT


def project_onto_polyline(
    point: tuple[float, float], points: Sequence[tuple[float, float]]
) -> tuple[float, float]:
    """(metres off the line, metres along it from its first vertex) for the closest approach."""
    lat, lon = point
    scale = math.cos(math.radians(lat))
    px, py = lon * scale, lat

    best_distance, best_along = float("inf"), 0.0
    travelled = 0.0
    for (alat, alon), (blat, blon) in zip(points, points[1:]):
        ax, ay = alon * scale, alat
        bx, by = blon * scale, blat
        dx, dy = bx - ax, by - ay
        span = dx * dx + dy * dy
        length = math.hypot(dx, dy)
        t = 0.0 if span == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / span))
        ddx, ddy = px - (ax + t * dx), py - (ay + t * dy)
        distance = math.hypot(ddx, ddy)
        if distance < best_distance:
            best_distance, best_along = distance, travelled + t * length
        travelled += length
    return best_distance * METERS_PER_DEGREE_LAT, best_along * METERS_PER_DEGREE_LAT


def polyline_length_m(points: Sequence[tuple[float, float]]) -> float:
    total = 0.0
    for (alat, alon), (blat, blon) in zip(points, points[1:]):
        scale = math.cos(math.radians(alat))
        total += math.hypot((blon - alon) * scale, blat - alat)
    return total * METERS_PER_DEGREE_LAT


# A sign's arrow is how DOT writes down how far its rule reaches: "<->" both ways from the sign,
# "-->" onward towards the to-street, "<--" back towards the from-street. Without one the sign
# speaks only for itself and this pipeline leaves the rule covering the whole block.
ARROW_BOTH = re.compile(r"<-+>")
ARROW_FORWARD = re.compile(r"(?<!<)-+>")
ARROW_BACK = re.compile(r"<-+(?!>)")


def parse_arrow(description: str) -> str | None:
    text = normalize(description)
    if ARROW_BOTH.search(text):
        return "both"
    if ARROW_FORWARD.search(text):
        return "forward"
    if ARROW_BACK.search(text):
        return "back"
    return None


def block_orientation(samples: Sequence[tuple[float, float]]) -> int:
    """+1 when the polyline is drawn from-street to to-street, -1 when it is drawn the other way.

    Each sample is (metres along the polyline, feet from the from-street intersection). The two
    should rise together; if they fall against each other the city drew the centreline backwards
    relative to how it names the block. Returns 0 when the signs cannot say, which is the signal
    to leave every rule covering the whole curb rather than guess at an extent.
    """
    usable = [s for s in samples if s[1] is not None]
    if len(usable) < 2:
        return 0

    mean_along = sum(a for a, _ in usable) / len(usable)
    mean_feet = sum(f for _, f in usable) / len(usable)
    covariance = sum((a - mean_along) * (f - mean_feet) for a, f in usable)
    if abs(covariance) < 1e-6:
        return 0
    return 1 if covariance > 0 else -1


def _merge_spans(spans: list[tuple[float, float]]) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for start, end in sorted(spans):
        if out and start <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], end))
        else:
            out.append((start, end))
    return out


#: A rule reaching within this much of both block ends is called block-wide and loses its extent.
#: Signs stand a few metres in from the corner, so an exact comparison would never fire.
BLOCK_WIDE_SLACK_M = 12.0

#: How far a lone arrowed sign reaches when nothing stands beyond it to stop it. A hydrant zone is
#: about this long, and it is the failure that matters least: too short and the map under-warns.
UNBOUNDED_REACH_M = 60.0


def extents_for_block(
    signs: Sequence["SignRecord"], points: Sequence[tuple[float, float]]
) -> list[Regulation]:
    """Every rule posted on a block, each narrowed to the stretch of curb its signs claim.

    A curb here is a whole block-side, but the city's data is per sign, so a single "NO STANDING
    ANYTIME" posted at a hydrant used to condemn the entire block. The arrows say otherwise: on
    Union St's south side that sign stands 70 feet in with "-->", and the next sign is at 87 feet,
    so it speaks for about five metres of kerb out of a hundred and seventy.

    A rule keeps the whole block whenever the data cannot narrow it — no arrow, no second sign to
    orient the block by, a span that reaches both ends anyway. Guessing an extent too small would
    paint a bus stop green, so every uncertainty here resolves outwards.
    """
    length = polyline_length_m(points)
    if length <= 0:
        return _deduplicate(r for sign in signs for r in sign.regulations)

    placed = []
    for sign in signs:
        _, along = project_onto_polyline(sign.position, points)
        placed.append((sign, along))

    orientation = block_orientation([(along, sign.feet) for sign, along in placed])
    if orientation == 0:
        return _deduplicate(r for sign in signs for r in sign.regulations)

    # Work in "u": metres from the from-street end, whichever end of the polyline that is.
    def to_u(along: float) -> float:
        return along if orientation > 0 else length - along

    positioned = [(sign, to_u(along)) for sign, along in placed]
    boundaries = sorted(
        u for sign, u in positioned if any(r.kind != "OTHER" for r in sign.regulations)
    )

    by_rule: dict[tuple, list[tuple["SignRecord", float]]] = defaultdict(list)
    first_seen: dict[tuple, Regulation] = {}
    for sign, u in positioned:
        for regulation in sign.regulations:
            key = (regulation.kind, regulation.days, regulation.start_minute, regulation.end_minute)
            by_rule[key].append((sign, u))
            first_seen.setdefault(key, regulation)

    out: list[Regulation] = []
    for key, carriers in by_rule.items():
        spans: list[tuple[float, float]] = []
        whole_block = False

        for sign, u in carriers:
            # A sign reaches as far as the next sign and no further, whatever that one says. Signs
            # of the same rule bound each other too — a chain of cleaning signs down a block each
            # covers its own stretch, and the union of them covers the block.
            #
            # Informational signs are not boundaries: "PAY-BY-CELL LOCATOR NUMBER" standing between
            # a hydrant sign and the kerb it protects would otherwise cut the zone in half.
            after = min((o for o in boundaries if o > u + 1e-6), default=None)
            before = max((o for o in boundaries if o < u - 1e-6), default=None)

            if sign.arrow == "forward":
                spans.append((u, after if after is not None else min(length, u + UNBOUNDED_REACH_M)))
            elif sign.arrow == "back":
                spans.append((before if before is not None else max(0.0, u - UNBOUNDED_REACH_M), u))
            elif sign.arrow == "both":
                spans.append((before if before is not None else 0.0, after if after is not None else length))
            else:
                whole_block = True
                break

        regulation = first_seen[key]
        if whole_block or not spans:
            out.append(regulation)
            continue

        merged = _merge_spans(spans)
        start, end = merged[0][0], merged[-1][1]
        if start <= BLOCK_WIDE_SLACK_M and end >= length - BLOCK_WIDE_SLACK_M:
            out.append(regulation)
            continue

        # Back into polyline metres, which is the frame the app projects a car or a tap into.
        extent = (start, end) if orientation > 0 else (length - end, length - start)
        out.append(replace(regulation, extent=extent))

    return out


def _deduplicate(regulations: Iterable[Regulation]) -> list[Regulation]:
    """One entry per distinct rule. The same sign copy is posted many times down a block, and the
    supersedes note differs between them, so equality on the whole record keeps every repeat."""
    seen: dict[tuple, Regulation] = {}
    for regulation in regulations:
        key = (regulation.kind, regulation.days, regulation.start_minute, regulation.end_minute)
        seen.setdefault(key, regulation)
    return list(seen.values())


def bbox(points: Sequence[tuple[float, float]]) -> list[float]:
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    return [min(lats), min(lons), max(lats), max(lons)]


# ---------------------------------------------------------------------------
# Socrata access
# ---------------------------------------------------------------------------


def socrata_pages(dataset: str, select: str, where: str | None, limit: int | None) -> Iterator[dict]:
    """Yields rows, paging through the Socrata API.

    No app token is used: the anonymous rate limit is generous enough for a build that runs monthly,
    and requiring a secret to build public data would be a poor trade.
    """
    fetched = 0
    offset = 0
    while True:
        page = PAGE_SIZE if limit is None else min(PAGE_SIZE, limit - fetched)
        if page <= 0:
            return

        params = {"$select": select, "$limit": page, "$offset": offset, "$order": ":id"}
        if where:
            params["$where"] = where
        url = f"{SOCRATA_HOST}/resource/{dataset}.json?{urllib.parse.urlencode(params)}"

        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=120) as response:
            rows = json.loads(response.read())

        if not rows:
            return
        yield from rows

        fetched += len(rows)
        offset += len(rows)
        if len(rows) < page:
            return
        if limit is not None and fetched >= limit:
            return


def load_rows(path: str | None, dataset: str, select: str, where: str | None, limit: int | None) -> list[dict]:
    if path:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    return list(socrata_pages(dataset, select, where, limit))


# ---------------------------------------------------------------------------
# Join and emit
# ---------------------------------------------------------------------------


@dataclass
class SegmentBuild:
    on_street: str
    from_street: str
    to_street: str
    side: str
    geometry: list[tuple[float, float]] = field(default_factory=list)
    #: Every sign posted on this curb, kept apart rather than pooled: which stretch each one
    #: governs is the difference between a bus stop and a block.
    signs: list["SignRecord"] = field(default_factory=list)

    @property
    def positions(self) -> list[tuple[float, float]]:
        """Where this curb's signs physically stand, in WGS84. Picks the block out of the street."""
        return [sign.position for sign in self.signs]


@dataclass(frozen=True)
class SignRecord:
    """One sign: what it says, where it stands, and how far it says the rule reaches."""

    position: tuple[float, float]
    #: Feet from the from-street intersection, as the city measured it. Used only to work out
    #: which way round the centreline was drawn; the position does the real work.
    feet: float | None
    arrow: str | None
    regulations: tuple[Regulation, ...]


# The two datasets spell the same street differently: the sign inventory writes "STERLING STREET"
# and "AVENUE N", the centreline file writes "STERLING ST" and "AVE N". Joining on the raw label
# therefore matches almost nothing — roughly six sign rows in seven end an abbreviated suffix apart
# from their geometry. Canonicalising both sides to the centreline's short form is the cheap half of
# the join problem in `docs/asp-data.md`; the other half, keying on LION segment ids instead of
# street names, is still open.
STREET_TYPES = {
    "STREET": "ST", "AVENUE": "AVE", "PLACE": "PL", "ROAD": "RD", "DRIVE": "DR",
    "BOULEVARD": "BLVD", "PARKWAY": "PKWY", "HIGHWAY": "HWY", "COURT": "CT", "LANE": "LN",
    "TERRACE": "TER", "PLAZA": "PLZ", "SQUARE": "SQ", "EXPRESSWAY": "EXPY", "TURNPIKE": "TPKE",
    "CIRCLE": "CIR", "EXTENSION": "EXT",
}

DIRECTIONS = {"NORTH": "N", "SOUTH": "S", "EAST": "E", "WEST": "W"}


def normalize_street(name: str) -> str:
    """Upper-case, collapse whitespace, and reduce street types and directions to their short form.

    Applied to both sides of the join and to the pieces of a curb id, so changing it renumbers every
    id — which is safe only because a refresh replaces the curb table wholesale.
    """
    words = re.sub(r"\s+", " ", (name or "").strip().upper()).split(" ")
    return " ".join(STREET_TYPES.get(w, DIRECTIONS.get(w, w)) for w in words)


def segment_key(on: str, frm: str, to: str, side: str) -> str:
    """A stable id for a curb, so a saved parking spot survives a dataset refresh.

    The block is keyed by its two cross streets in sorted order, because the city records the same
    block as "5 Av to 6 Av" in one row and "6 Av to 5 Av" in another depending on which way the
    survey crew walked.
    """
    ends = "|".join(sorted([normalize_street(frm), normalize_street(to)]))
    raw = f"{normalize_street(on)}|{ends}|{side}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def nearest_segment(
    candidates: Sequence[Sequence[tuple[float, float]]],
    positions: Sequence[tuple[float, float]],
) -> list[tuple[float, float]] | None:
    """The candidate block nearest the signs, or None when the signs cannot say which.

    A street name alone cannot pick a block, so the signs' own coordinates do it. With no position
    to go on the curb is dropped rather than pinned to an arbitrary block of the right name, which
    is what used to happen: a rule drawn on the wrong street is worse than one not drawn at all.
    """
    if not positions:
        return None

    centre = (
        sum(lat for lat, _ in positions) / len(positions),
        sum(lon for _, lon in positions) / len(positions),
    )
    best, best_distance = None, float("inf")
    for points in candidates:
        distance = distance_to_polyline(centre, points)
        if distance < best_distance:
            best, best_distance = points, distance

    # Beyond a couple of blocks the match is a coincidence of naming, not the same street.
    return list(best) if best is not None and best_distance <= MAX_SIGN_TO_CURB_M else None


def build_segments(signs: Iterable[dict], centerlines: Iterable[dict], report_unparsed: bool) -> tuple[list[dict], dict]:
    # Every segment of a street, not one: "PRESIDENT ST" is fifty-odd blocks spread across three
    # boroughs, and which of them a curb belongs to is decided below by where its signs actually
    # stand. Keeping only the first match put every President Street in the city on one block of it.
    geometry_by_street: dict[str, list[list[tuple[float, float]]]] = defaultdict(list)
    for row in centerlines:
        geom = row.get("the_geom") or {}
        if geom.get("type") != "MultiLineString":
            continue
        parts = geom.get("coordinates") or []
        if not parts or not parts[0]:
            continue
        # Socrata gives GeoJSON order (lon, lat); everything downstream wants (lat, lon).
        points = [(float(lat), float(lon)) for lon, lat in parts[0]]
        if len(points) < 2:
            continue
        geometry_by_street[normalize_street(row.get("stname_label", ""))].append(points)

    builds: dict[str, SegmentBuild] = {}
    unparsed: dict[str, int] = defaultdict(int)
    stats = {"signs": 0, "signs_parsed": 0, "segments": 0, "no_geometry": 0, "no_position": 0}

    for row in signs:
        stats["signs"] += 1
        description = row.get("sign_description") or ""
        regulations = parse_sign(description)
        readable = [r for r in regulations if r.days]
        if not readable:
            if description.strip():
                unparsed[normalize(description)] += 1
            continue
        stats["signs_parsed"] += 1

        on = normalize_street(row.get("on_street", ""))
        frm = normalize_street(row.get("from_street", ""))
        to = normalize_street(row.get("to_street", ""))
        side = (row.get("side_of_street") or "").strip().upper()[:1]
        if not on:
            continue

        x, y = row.get("sign_x_coord"), row.get("sign_y_coord")
        if not x or not y:
            continue
        try:
            position = state_plane_to_wgs84(float(x), float(y))
        except (TypeError, ValueError):
            continue

        try:
            feet = float(row.get("distance_from_intersection"))
        except (TypeError, ValueError):
            feet = None

        key = segment_key(on, frm, to, side)
        build = builds.setdefault(key, SegmentBuild(on, frm, to, side))
        build.signs.append(
            SignRecord(
                position=position,
                feet=feet,
                arrow=parse_arrow(description),
                regulations=tuple(readable),
            )
        )

    out: list[dict] = []
    for key, build in builds.items():
        candidates = geometry_by_street.get(normalize_street(build.on_street))
        if not candidates:
            stats["no_geometry"] += 1
            continue

        points = nearest_segment(candidates, build.positions)
        if points is None:
            stats["no_position"] += 1
            continue

        out.append(
            {
                "id": key,
                "on": build.on_street,
                "from": build.from_street,
                "to": build.to_street,
                "side": {"N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST"}.get(build.side, "UNKNOWN"),
                "sign": side_sign(points, build.side),
                "bbox": bbox(points),
                "geom": encode_polyline(points),
                "rules": json.dumps(
                    [r.to_dto() for r in extents_for_block(build.signs, points)],
                    separators=(",", ":"),
                ),
            }
        )

    stats["segments"] = len(out)
    if report_unparsed and unparsed:
        print("\nMost common unparsed sign descriptions:", file=sys.stderr)
        for text, count in sorted(unparsed.items(), key=lambda kv: -kv[1])[:40]:
            print(f"  {count:6d}  {text}", file=sys.stderr)

    return out, stats


# ---------------------------------------------------------------------------
# Alternate side suspensions
# ---------------------------------------------------------------------------

CALENDAR_URL = "https://api.nyc.gov/public/api/GetCalendar"

#: The API answers about this many days whatever window is asked for, so the calendar is always
#: short-dated and the bundle has to be rebuilt to keep it current.
CALENDAR_DAYS = 90


def fetch_suspensions(key: str) -> dict | None:
    """The days the city has lifted alternate side, from the 311 calendar.

    Fetched here rather than on each phone. The endpoint needs a subscription key, and a key
    compiled into an app is a key published to everyone who unzips it — while the answer is the
    same thirty-odd days a year for the whole city. One request on a build machine replaces one per
    device per week, and the app ends up needing no key at all.

    Returns None when the fetch fails, which leaves whatever the previous bundle said rather than
    publishing an empty calendar: an empty one reads as "nothing is suspended", and the app would
    send someone out on Thanksgiving morning.
    """
    today = datetime.now(timezone.utc).date()
    start = today - timedelta(days=1)
    end = today + timedelta(days=CALENDAR_DAYS)
    url = (
        f"{CALENDAR_URL}?fromdate={start.strftime('%m/%d/%Y')}"
        f"&todate={end.strftime('%m/%d/%Y')}"
    )

    request = urllib.request.Request(url, headers={"Ocp-Apim-Subscription-Key": key})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception as error:  # noqa: BLE001 - any failure means "keep the old calendar"
        print(f"  suspension calendar unavailable: {error}", file=sys.stderr)
        return None

    dates: list[str] = []
    covered: list[str] = []
    for day in payload.get("days", []):
        raw = day.get("today_id")
        if not raw or len(raw) != 8:
            continue
        iso = f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
        covered.append(iso)
        for item in day.get("items", []):
            if item.get("type", "").strip().lower() != "alternate side parking":
                continue
            # "IN EFFECT", "SUSPENDED" or "NOT IN EFFECT". Only the middle one lifts a rule that
            # would otherwise have applied; "not in effect" is a Sunday, with nothing to lift.
            if "SUSPENDED" in item.get("status", "").upper():
                dates.append(iso)

    if not covered:
        return None

    return {"from": min(covered), "to": max(covered), "dates": sorted(set(dates))}


def write_bundle(segments: list[dict], out_dir: str, seed: bool = False, suspensions: dict | None = None) -> dict:
    """Write the bundle and its manifest.

    ``seed`` writes the bundle under a fixed, extension-free name for packaging into the APK's
    assets. The name matters: aapt treats an asset ending in ``.gz`` as something to be helpfully
    gunzipped at package time, storing it without the suffix — which both triples what the APK
    carries and means the file the manifest names is not the file that exists on the device.
    """
    os.makedirs(out_dir, exist_ok=True)

    # Content-addressed version: rebuilding unchanged input produces an unchanged version, so the
    # app skips the download rather than reinstalling the same rows.
    digest = hashlib.sha256()
    for segment in segments:
        digest.update(json.dumps(segment, sort_keys=True, separators=(",", ":")).encode("utf-8"))
    version = digest.hexdigest()[:12]

    filename = SEED_FILENAME if seed else f"segments-{version}.jsonl.gz"
    with gzip.open(os.path.join(out_dir, filename), "wt", encoding="utf-8") as handle:
        for segment in segments:
            handle.write(json.dumps(segment, separators=(",", ":")))
            handle.write("\n")

    manifest = {
        "version": version,
        "path": filename,
        # Carried in the manifest rather than beside it: the manifest is fetched on every check
        # even when the segment bundle is unchanged, so the calendar refreshes without a download.
        "suspensions": suspensions,
        "segment_count": len(segments),
        "built_at": datetime.now(timezone.utc).isoformat(),
    }
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
    return manifest


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="dist", help="output directory (default: dist)")
    parser.add_argument("--borough", help="restrict to one borough, e.g. Brooklyn")
    parser.add_argument("--limit", type=int, help="cap the number of sign rows, for a quick test build")
    parser.add_argument("--signs", help="path to a cached signs JSON file instead of the API")
    parser.add_argument("--centerlines", help="path to a cached centreline JSON file instead of the API")
    parser.add_argument("--report-unparsed", action="store_true", help="print sign copy the parser could not read")
    parser.add_argument(
        "--calendar-key",
        help="NYC API portal subscription key, for the alternate side suspension calendar "
        "(default: $NYC_311_API_KEY). Without it the bundle carries no suspensions.",
    )
    parser.add_argument(
        "--seed",
        action="store_true",
        help=f"name the bundle {SEED_FILENAME}, for packaging into app/src/main/assets/asp",
    )
    args = parser.parse_args(argv)

    where = f"upper(borough)='{args.borough.upper()}'" if args.borough else None

    print("Fetching sign inventory…", file=sys.stderr)
    signs = load_rows(
        args.signs,
        SIGNS_DATASET,
        "sign_description,on_street,from_street,to_street,side_of_street,borough,sign_x_coord,sign_y_coord,"
        "distance_from_intersection",
        where,
        args.limit,
    )
    print(f"  {len(signs)} sign rows", file=sys.stderr)

    print("Fetching street centrelines…", file=sys.stderr)
    centerlines = load_rows(args.centerlines, CENTERLINE_DATASET, "stname_label,the_geom", None, None)
    print(f"  {len(centerlines)} centreline rows", file=sys.stderr)

    segments, stats = build_segments(signs, centerlines, args.report_unparsed)
    key = args.calendar_key or os.environ.get("NYC_311_API_KEY", "")
    suspensions = fetch_suspensions(key) if key else None
    if suspensions:
        print(
            f"Alternate side suspended on {len(suspensions['dates'])} days "
            f"between {suspensions['from']} and {suspensions['to']}.",
            file=sys.stderr,
        )
    elif not key:
        print(
            "No 311 key given, so the bundle carries no suspension calendar and the app will "
            "treat holidays as ordinary cleaning days. Pass --calendar-key or set NYC_311_API_KEY.",
            file=sys.stderr,
        )

    manifest = write_bundle(segments, args.out, seed=args.seed, suspensions=suspensions)

    parsed_pct = 100.0 * stats["signs_parsed"] / stats["signs"] if stats["signs"] else 0.0
    print(
        f"\nParsed {stats['signs_parsed']}/{stats['signs']} signs ({parsed_pct:.1f}%), "
        f"{stats['segments']} curbs written, {stats['no_geometry']} with no street geometry, """
        f"{stats['no_position']} with no usable sign position.",
        file=sys.stderr,
    )
    print(f"Wrote {args.out}/{manifest['path']} (version {manifest['version']}).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
