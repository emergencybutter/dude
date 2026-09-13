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
    SignRecord,
    bbox,
    block_orientation,
    extents_for_block,
    parse_arrow,
    polyline_length_m,
    project_onto_polyline,
    distance_to_polyline,
    encode_polyline,
    nearest_segment,
    normalize_street,
    state_plane_to_wgs84,
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


class OvernightWindowTest(unittest.TestCase):
    """Mirrors the overnight cases in SignParserTest.kt; the two parsers must agree."""

    def test_an_overnight_window_becomes_two_one_either_side_of_midnight(self):
        parsed = parse_sign("MOON & STARS (SYMBOLS) NO STANDING 10PM-5AM ALL DAYS <->")

        self.assertEqual(2, len(parsed))
        evening, morning = parsed
        self.assertEqual("NO_STANDING", evening.kind)
        self.assertEqual((22 * 60, END_OF_DAY), (evening.start_minute, evening.end_minute))
        self.assertEqual((0, 5 * 60), (morning.start_minute, morning.end_minute))
        # Not a rule with no window: that is what the engine reads as restricted around the clock.
        self.assertTrue(all(r.start_minute is not None for r in parsed))

    def test_the_morning_half_lands_on_the_next_day(self):
        parsed = parse_sign("NO PARKING 10PM-4AM MON THURS")

        self.assertEqual(frozenset({MON, THU}), parsed[0].days)
        self.assertEqual(frozenset({TUE, FRI}), parsed[1].days)

    def test_a_borrowed_meridiem_that_only_looks_overnight_is_still_morning(self):
        parsed = parse_sign("NO PARKING (SANITATION BROOM SYMBOL) 11-12:30PM WED")

        self.assertEqual((11 * 60, 12 * 60 + 30), (parsed[0].start_minute, parsed[0].end_minute))

    def test_all_days_is_the_whole_week_stated_rather_than_inferred(self):
        parsed = parse_sign("NO STANDING 8AM-6PM ALL DAYS")

        self.assertEqual(ALL_DAYS, parsed[0].days)
        self.assertFalse(parsed[0].days_inferred)


class GeometryJoinTest(unittest.TestCase):
    """Picking the right block of a street out of the fifty that share its name."""

    # Two real blocks of President Street: Park Slope, and Crown Heights two miles east.
    PARK_SLOPE = [(40.6738, -73.9840), (40.6730, -73.9800)]
    CROWN_HEIGHTS = [(40.6671, -73.9340), (40.6669, -73.9300)]

    def test_state_plane_converts_to_the_right_corner_of_brooklyn(self):
        lat, lon = state_plane_to_wgs84(1002948, 182343)  # President St at Utica Av

        self.assertAlmostEqual(40.667, lat, places=2)
        self.assertAlmostEqual(-73.933, lon, places=2)

    def test_the_block_nearest_the_signs_wins(self):
        signs = [state_plane_to_wgs84(1002948, 182343)]

        chosen = nearest_segment([self.PARK_SLOPE, self.CROWN_HEIGHTS], signs)

        self.assertEqual(self.CROWN_HEIGHTS, chosen)

    def test_a_curb_with_no_sign_positions_is_dropped_not_guessed(self):
        self.assertIsNone(nearest_segment([self.PARK_SLOPE, self.CROWN_HEIGHTS], []))

    def test_a_street_of_the_same_name_far_away_is_not_a_match(self):
        staten_island = [state_plane_to_wgs84(960000, 150000)]

        self.assertIsNone(nearest_segment([self.PARK_SLOPE], staten_island))

    def test_distance_to_a_polyline_is_measured_in_metres(self):
        # A due-east line, and a point 0.001 degrees of latitude north of it: one minute of arc is
        # about 111 m, and the perpendicular is unambiguous because the line does not slope.
        due_east = [(40.6700, -73.9900), (40.6700, -73.9800)]

        self.assertAlmostEqual(111.0, distance_to_polyline((40.6710, -73.9850), due_east), delta=3.0)


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


class NormalizeStreetTest(unittest.TestCase):
    """The sign inventory and the centreline file spell street types differently, and the join is
    on the name alone. Every case here is a real pairing that produced no geometry until both sides
    were reduced to the same short form."""

    def test_street_types_reduce_to_the_centreline_spelling(self):
        self.assertEqual(normalize_street("STERLING STREET"), "STERLING ST")
        self.assertEqual(normalize_street("AVENUE N"), "AVE N")
        self.assertEqual(normalize_street("OCEAN PARKWAY"), "OCEAN PKWY")

    def test_directions_reduce_to_an_initial(self):
        self.assertEqual(normalize_street("WILLIAMSBURG ST WEST"), "WILLIAMSBURG ST W")

    def test_a_name_that_needs_no_reduction_is_untouched(self):
        self.assertEqual(normalize_street("BROADWAY"), "BROADWAY")

    def test_the_centreline_spelling_is_already_canonical(self):
        self.assertEqual(normalize_street("W  60 ST"), normalize_street("WEST 60 STREET"))

    def test_a_sign_and_its_centreline_agree_after_normalising(self):
        self.assertEqual(normalize_street("STERLING STREET"), normalize_street("STERLING ST"))


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


# ---------------------------------------------------------------------------
# Sign extents
# ---------------------------------------------------------------------------

#: Union St's south side, Van Brunt to Columbia: a straight run east, about 175m long.
BLOCK_START = (40.6800, -74.0100)
BLOCK_LENGTH_M = 175.0
_LON_PER_M = 1.0 / (111_320.0 * 0.7585)
UNION_ST_SOUTH = [BLOCK_START, (40.6800, -74.0100 + BLOCK_LENGTH_M * _LON_PER_M)]

FEET_PER_M = 3.28084


def sign_at(feet: float, description: str, geometry=UNION_ST_SOUTH) -> SignRecord:
    """A sign standing the given distance along the block, as the city measures it."""
    fraction = (feet / FEET_PER_M) / BLOCK_LENGTH_M
    lon = geometry[0][1] + (geometry[-1][1] - geometry[0][1]) * fraction
    return SignRecord(
        position=(geometry[0][0], lon),
        feet=feet,
        arrow=parse_arrow(description),
        regulations=tuple(parse_sign(description)),
    )


CLEANING = "NO PARKING (SANITATION BROOM SYMBOL) FRIDAY 8:30AM-10AM"
ANYTIME = "NO STANDING ANYTIME"
TRUCKS = "TRUCK (SYMBOL) TRUCK LOADING ONLY MONDAY-FRIDAY 7AM-7PM"


class ArrowTest(unittest.TestCase):
    def test_both_ways(self):
        self.assertEqual("both", parse_arrow("NO STANDING ANYTIME <->"))
        self.assertEqual("both", parse_arrow("2 HMP 8AM-7PM EXCEPT SUNDAY <-->"))

    def test_forward(self):
        self.assertEqual("forward", parse_arrow("NO STANDING ANYTIME --> (SUPERSEDES SP-10BA)"))
        self.assertEqual("forward", parse_arrow("2 HMP 9AM-7PM EXCEPT SUNDAY ->"))

    def test_back(self):
        self.assertEqual("back", parse_arrow("NO STANDING ANYTIME <--"))

    def test_no_arrow(self):
        self.assertIsNone(parse_arrow("PAY-BY-CELL LOCATOR NUMBER"))


class ProjectionTest(unittest.TestCase):
    def test_length_of_the_test_block(self):
        self.assertAlmostEqual(BLOCK_LENGTH_M, polyline_length_m(UNION_ST_SOUTH), delta=1.0)

    def test_a_sign_projects_to_where_it_stands(self):
        _, along = project_onto_polyline(sign_at(87, CLEANING).position, UNION_ST_SOUTH)
        self.assertAlmostEqual(87 / FEET_PER_M, along, delta=1.0)

    def test_orientation_needs_two_signs(self):
        self.assertEqual(0, block_orientation([(10.0, 30.0)]))
        self.assertEqual(1, block_orientation([(10.0, 30.0), (50.0, 160.0)]))
        self.assertEqual(-1, block_orientation([(50.0, 30.0), (10.0, 160.0)]))


class ExtentTest(unittest.TestCase):
    """The Union St bug, from the side the data comes in on."""

    def union_street_south(self):
        return [
            sign_at(70, ANYTIME + " -->"),
            sign_at(87, CLEANING + " -->"),
            sign_at(212, TRUCKS + " -->"),
            sign_at(212, CLEANING + " -->"),
            sign_at(303, CLEANING + " <->"),
            sign_at(575, CLEANING + " <->"),
        ]

    def rule(self, regulations, kind):
        matches = [r for r in regulations if r.kind == kind]
        self.assertEqual(1, len(matches), f"expected exactly one {kind}, got {matches}")
        return matches[0]

    def test_a_hydrant_sign_governs_metres_not_the_block(self):
        rules = extents_for_block(self.union_street_south(), UNION_ST_SOUTH)
        anytime = self.rule(rules, "NO_STANDING")

        self.assertIsNotNone(anytime.extent, "the anytime sign has to be pinned down")
        start, end = anytime.extent
        # 70ft to the next sign at 87ft: about five metres of kerb, by the Van Brunt corner.
        self.assertAlmostEqual(70 / FEET_PER_M, start, delta=2.0)
        self.assertAlmostEqual(87 / FEET_PER_M, end, delta=2.0)

    def test_the_cleaning_schedule_still_covers_the_rest_of_the_block(self):
        rules = extents_for_block(self.union_street_south(), UNION_ST_SOUTH)
        cleaning = self.rule(rules, "STREET_CLEANING")

        # It starts at its first sign, 87ft in, and runs to the end of the block.
        self.assertIsNotNone(cleaning.extent)
        start, end = cleaning.extent
        self.assertAlmostEqual(87 / FEET_PER_M, start, delta=2.0)
        self.assertAlmostEqual(BLOCK_LENGTH_M, end, delta=2.0)

    def test_repeated_sign_copy_collapses_to_one_rule(self):
        # Four cleaning signs down the block, one rule out.
        rules = extents_for_block(self.union_street_south(), UNION_ST_SOUTH)
        self.assertEqual(1, len([r for r in rules if r.kind == "STREET_CLEANING"]))

    def test_a_backwards_centreline_mirrors_the_extents(self):
        reversed_block = list(reversed(UNION_ST_SOUTH))
        signs = self.union_street_south()
        rules = extents_for_block(signs, reversed_block)
        anytime = self.rule(rules, "NO_STANDING")

        # Same stretch of kerb, measured from the other end of the line.
        start, end = anytime.extent
        self.assertAlmostEqual(BLOCK_LENGTH_M - 87 / FEET_PER_M, start, delta=2.0)
        self.assertAlmostEqual(BLOCK_LENGTH_M - 70 / FEET_PER_M, end, delta=2.0)

    def test_signs_without_arrows_keep_the_whole_block(self):
        signs = [sign_at(70, ANYTIME), sign_at(87, CLEANING)]
        rules = extents_for_block(signs, UNION_ST_SOUTH)
        self.assertTrue(all(r.extent is None for r in rules), rules)

    def test_one_sign_alone_cannot_orient_the_block(self):
        rules = extents_for_block([sign_at(70, ANYTIME + " -->")], UNION_ST_SOUTH)
        self.assertTrue(all(r.extent is None for r in rules), rules)

    def test_an_extent_reaching_both_ends_is_dropped(self):
        signs = [sign_at(10, CLEANING + " <->"), sign_at(560, CLEANING + " <->")]
        rules = extents_for_block(signs, UNION_ST_SOUTH)
        self.assertIsNone(self.rule(rules, "STREET_CLEANING").extent)

    def test_the_extent_reaches_the_app_in_the_dto(self):
        rules = extents_for_block(self.union_street_south(), UNION_ST_SOUTH)
        dto = self.rule(rules, "NO_STANDING").to_dto()
        self.assertIn("a", dto)
        self.assertIn("b", dto)
        self.assertEqual(int, type(dto["a"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
