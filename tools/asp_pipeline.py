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
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Iterable, Iterator, Sequence

SOCRATA_HOST = "https://data.cityofnewyork.us"
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

    def to_dto(self) -> dict:
        """The compact shape ``RegulationCodec`` on the app side expects."""
        dto: dict = {
            "k": self.kind,
            "d": sum(1 << day for day in self.days),
        }
        if self.start_minute is not None and self.end_minute is not None:
            dto["s"] = self.start_minute
            dto["e"] = self.end_minute
        if self.raw:
            dto["r"] = self.raw
        if self.days_inferred:
            dto["i"] = True
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


def parse_window(clause: str) -> tuple[int, int] | None:
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
        # Borrowing PM wrapped the window; the sign meant the morning.
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


def parse_days(clause: str) -> frozenset[int] | None:
    if "INCLUDING" in clause:
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

        out.append(
            Regulation(
                kind=kind,
                days=days,
                start_minute=window[0] if window else None,
                end_minute=window[1] if window else None,
                raw=description,
                days_inferred=explicit is None,
            )
        )
    return out


# ---------------------------------------------------------------------------
# Geometry
# ---------------------------------------------------------------------------

EARTH_RADIUS_M = 6_371_008.8
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
    regulations: list[Regulation] = field(default_factory=list)
    geometry: list[tuple[float, float]] = field(default_factory=list)


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


def build_segments(signs: Iterable[dict], centerlines: Iterable[dict], report_unparsed: bool) -> tuple[list[dict], dict]:
    geometry_by_block: dict[str, list[tuple[float, float]]] = {}
    for row in centerlines:
        geom = row.get("the_geom") or {}
        if geom.get("type") != "MultiLineString":
            continue
        parts = geom.get("coordinates") or []
        if not parts or not parts[0]:
            continue
        # Socrata gives GeoJSON order (lon, lat); everything downstream wants (lat, lon).
        points = [(float(lat), float(lon)) for lon, lat in parts[0]]
        key = normalize_street(row.get("stname_label", ""))
        geometry_by_block.setdefault(key, points)

    builds: dict[str, SegmentBuild] = {}
    unparsed: dict[str, int] = defaultdict(int)
    stats = {"signs": 0, "signs_parsed": 0, "segments": 0, "no_geometry": 0}

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

        key = segment_key(on, frm, to, side)
        build = builds.setdefault(key, SegmentBuild(on, frm, to, side))
        for regulation in readable:
            if regulation not in build.regulations:
                build.regulations.append(regulation)

    out: list[dict] = []
    for key, build in builds.items():
        points = geometry_by_block.get(normalize_street(build.on_street))
        if not points or len(points) < 2:
            stats["no_geometry"] += 1
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
                "rules": json.dumps([r.to_dto() for r in build.regulations], separators=(",", ":")),
            }
        )

    stats["segments"] = len(out)
    if report_unparsed and unparsed:
        print("\nMost common unparsed sign descriptions:", file=sys.stderr)
        for text, count in sorted(unparsed.items(), key=lambda kv: -kv[1])[:40]:
            print(f"  {count:6d}  {text}", file=sys.stderr)

    return out, stats


def write_bundle(segments: list[dict], out_dir: str) -> dict:
    os.makedirs(out_dir, exist_ok=True)

    # Content-addressed version: rebuilding unchanged input produces an unchanged version, so the
    # app skips the download rather than reinstalling the same rows.
    digest = hashlib.sha256()
    for segment in segments:
        digest.update(json.dumps(segment, sort_keys=True, separators=(",", ":")).encode("utf-8"))
    version = digest.hexdigest()[:12]

    filename = f"segments-{version}.jsonl.gz"
    with gzip.open(os.path.join(out_dir, filename), "wt", encoding="utf-8") as handle:
        for segment in segments:
            handle.write(json.dumps(segment, separators=(",", ":")))
            handle.write("\n")

    manifest = {
        "version": version,
        "path": filename,
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
    args = parser.parse_args(argv)

    where = f"upper(borough)='{args.borough.upper()}'" if args.borough else None

    print("Fetching sign inventory…", file=sys.stderr)
    signs = load_rows(
        args.signs,
        SIGNS_DATASET,
        "sign_description,on_street,from_street,to_street,side_of_street,borough",
        where,
        args.limit,
    )
    print(f"  {len(signs)} sign rows", file=sys.stderr)

    print("Fetching street centrelines…", file=sys.stderr)
    centerlines = load_rows(args.centerlines, CENTERLINE_DATASET, "stname_label,the_geom", None, None)
    print(f"  {len(centerlines)} centreline rows", file=sys.stderr)

    segments, stats = build_segments(signs, centerlines, args.report_unparsed)
    manifest = write_bundle(segments, args.out)

    parsed_pct = 100.0 * stats["signs_parsed"] / stats["signs"] if stats["signs"] else 0.0
    print(
        f"\nParsed {stats['signs_parsed']}/{stats['signs']} signs ({parsed_pct:.1f}%), "
        f"{stats['segments']} curbs written, {stats['no_geometry']} dropped for want of geometry.",
        file=sys.stderr,
    )
    print(f"Wrote {args.out}/{manifest['path']} (version {manifest['version']}).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
