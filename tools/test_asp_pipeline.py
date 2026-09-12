#!/usr/bin/env python3
"""Checks the pipeline's sign parser against the same corpus as ``SignParserTest.kt``.

The pipeline and the app parse sign copy in two languages. They must agree, or the app's rule
engine will be reasoning about a schedule the pipeline never intended — so every case asserted on
the Kotlin side is asserted here too, with the same expected output.

Run with ``python3 tools/test_asp_pipeline.py``.
"""

from __future__ import annotations

import unittest

from asp_pipeline import (
    ALL_DAYS,
    END_OF_DAY,
    Regulation,
    bbox,
    encode_polyline,
    parse_sign,
    segment_key,
    side_sign,
)

MON, TUE, WED, THU, FRI, SAT, SUN = range(7)


class SignParserTest(unittest.TestCase):
    def one(self, text: str) -> Regulation:
        parsed = parse_sign(text)
        self.assertEqual(1, len(parsed), f"expected a single clause from: {text}")
        return parsed[0]

    def test_two_day_sweeping_sign(self):
        r = self.one("NO PARKING (SANITATION BROOM SYMBOL) 11:30AM-1PM TUES & FRI")
        self.assertEqual("STREET_CLEANING", r.kind)
        self.assertEqual(frozenset({TUE, FRI}), r.days)
        self.assertEqual((11 * 60 + 30, 13 * 60), (r.start_minute, r.end_minute))

    def test_opening_meridiem_inferred_from_closing(self):
        r = self.one("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS")
        self.assertEqual(frozenset({MON, THU}), r.days)
        self.assertEqual((8 * 60, 9 * 60 + 30), (r.start_minute, r.end_minute))

    def test_inferred_pm_does_not_wrap_past_midnight(self):
        r = self.one("NO PARKING (SANITATION BROOM SYMBOL) 11-12:30PM WED")
        self.assertEqual((11 * 60, 12 * 60 + 30), (r.start_minute, r.end_minute))

    def test_thru_day_range(self):
        r = self.one("NO STANDING 7AM-10AM MON THRU FRI")
        self.assertEqual("NO_STANDING", r.kind)
        self.assertEqual(frozenset({MON, TUE, WED, THU, FRI}), r.days)

    def test_hyphenated_day_range(self):
        r = self.one("NO STANDING 4PM-7PM MON-FRI")
        self.assertEqual(5, len(r.days))
        self.assertIn(WED, r.days)

    def test_anytime(self):
        r = self.one("NO STANDING ANYTIME")
        self.assertEqual("NO_STANDING", r.kind)
        self.assertEqual(ALL_DAYS, r.days)
        self.assertIsNone(r.start_minute)

    def test_except_inverts_days(self):
        r = self.one("2 HOUR PARKING 9AM-7PM EXCEPT SUNDAY")
        self.assertEqual("TIME_LIMITED", r.kind)
        self.assertEqual(6, len(r.days))
        self.assertNotIn(SUN, r.days)

    def test_midnight_at_each_end(self):
        opening = self.one("NO PARKING MIDNIGHT-4AM")
        self.assertEqual((0, 4 * 60), (opening.start_minute, opening.end_minute))

        closing = self.one("NO STOPPING 7AM-MIDNIGHT")
        self.assertEqual((7 * 60, END_OF_DAY), (closing.start_minute, closing.end_minute))

    def test_noon(self):
        r = self.one("NO PARKING NOON-2PM SAT")
        self.assertEqual((12 * 60, 14 * 60), (r.start_minute, r.end_minute))
        self.assertEqual(frozenset({SAT}), r.days)

    def test_timed_sign_without_a_weekday_runs_every_day(self):
        r = self.one("NO PARKING 8AM-6PM")
        self.assertEqual(ALL_DAYS, r.days)
        self.assertTrue(r.days_inferred)

    def test_unreadable_sign_is_reported_not_guessed(self):
        r = self.one("AUTHORIZED VEHICLES ONLY")
        self.assertEqual("OTHER", r.kind)
        self.assertEqual(frozenset(), r.days)

    def test_multi_clause_sign(self):
        parsed = parse_sign("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON / NO STANDING 4PM-7PM FRI")
        self.assertEqual(2, len(parsed))
        self.assertEqual("STREET_CLEANING", parsed[0].kind)
        self.assertEqual(frozenset({MON}), parsed[0].days)
        self.assertEqual("NO_STANDING", parsed[1].kind)
        self.assertEqual(frozenset({FRI}), parsed[1].days)

    def test_hour_count_is_not_a_time_window(self):
        r = self.one("1 HOUR METERED PARKING 9AM-10PM INCLUDING SUNDAY")
        self.assertEqual((9 * 60, 22 * 60), (r.start_minute, r.end_minute))
        self.assertEqual(ALL_DAYS, r.days)

    def test_blank(self):
        self.assertEqual([], parse_sign("   "))


class DtoTest(unittest.TestCase):
    def test_day_bitmask_matches_the_kotlin_convention(self):
        # Bit 0 is Monday, matching DayOfWeek.getValue() - 1 on the app side.
        r = parse_sign("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS")[0]
        self.assertEqual((1 << 0) | (1 << 3), r.to_dto()["d"])

    def test_all_day_rule_carries_no_window(self):
        dto = parse_sign("NO STANDING ANYTIME")[0].to_dto()
        self.assertNotIn("s", dto)
        self.assertNotIn("e", dto)


class GeometryTest(unittest.TestCase):
    # A west-to-east block of Bergen Street, the same line used in GeoTest.kt.
    BERGEN = [(40.68210, -73.98010), (40.68212, -73.97880)]

    def test_polyline_matches_the_kotlin_codec(self):
        # Encoded with precision 6; decoding happens on the app side, so this pins the format.
        encoded = encode_polyline(self.BERGEN)
        self.assertTrue(encoded)
        # Round trip through a minimal decoder to prove the encoding is self-consistent.
        self.assertEqual(self.BERGEN, [(round(lat, 5), round(lon, 5)) for lat, lon in decode(encoded)])

    def test_north_side_of_an_eastbound_line_is_its_left(self):
        self.assertEqual(-1, side_sign(self.BERGEN, "N"))
        self.assertEqual(1, side_sign(self.BERGEN, "S"))

    def test_reversing_the_line_reverses_the_sign(self):
        self.assertEqual(1, side_sign(list(reversed(self.BERGEN)), "N"))

    def test_unknown_side_draws_on_the_centreline(self):
        self.assertEqual(0, side_sign(self.BERGEN, ""))

    def test_bbox(self):
        self.assertEqual([40.68210, -73.98010, 40.68212, -73.97880], bbox(self.BERGEN))


class SegmentKeyTest(unittest.TestCase):
    def test_cross_streets_in_either_order_give_the_same_curb(self):
        forward = segment_key("BERGEN ST", "5 AV", "6 AV", "N")
        backward = segment_key("BERGEN ST", "6 AV", "5 AV", "N")
        self.assertEqual(forward, backward)

    def test_the_two_sides_are_different_curbs(self):
        self.assertNotEqual(
            segment_key("BERGEN ST", "5 AV", "6 AV", "N"),
            segment_key("BERGEN ST", "5 AV", "6 AV", "S"),
        )

    def test_whitespace_and_case_do_not_matter(self):
        self.assertEqual(
            segment_key("bergen st", " 5 av ", "6  AV", "N"),
            segment_key("BERGEN ST", "5 AV", "6 AV", "N"),
        )


def decode(encoded: str, precision: int = 6) -> list[tuple[float, float]]:
    """Minimal reference decoder, used only to prove the encoder round-trips."""
    factor = 10**precision
    out: list[tuple[float, float]] = []
    index = lat = lon = 0

    def value() -> int:
        nonlocal index
        shift = result = 0
        while True:
            byte = ord(encoded[index]) - 63
            index += 1
            result |= (byte & 0x1F) << shift
            shift += 5
            if byte < 0x20:
                break
        return ~(result >> 1) if result & 1 else (result >> 1)

    while index < len(encoded):
        lat += value()
        lon += value()
        out.append((lat / factor, lon / factor))
    return out


if __name__ == "__main__":
    unittest.main(verbosity=2)
