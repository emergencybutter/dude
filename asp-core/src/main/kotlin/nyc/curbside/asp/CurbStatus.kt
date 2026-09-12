package nyc.curbside.asp

/**
 * How a curb should be drawn on the map right now.
 *
 * The ordering is deliberate: statuses are declared worst-first, so `minOf` over the regulations on
 * a segment picks the one the driver most needs to know about.
 *
 * ## Colour choice
 *
 * The ramp runs red → orange → amber → lime → green over "how long until you must move", which is
 * the one question the map exists to answer. Two statuses sit outside that ramp because they are
 * not points on the same axis: [SUSPENDED_TODAY] is blue (the rule exists but is lifted) and
 * [ALWAYS_RESTRICTED] is purple (no amount of waiting helps).
 *
 * Red and green are the classic deuteranopia collision, so colour is never the only signal:
 * [strokePattern] gives [SWEEPING_NOW] and [MOVE_WITHIN_HOUR] a dashed casing and [UNKNOWN] a
 * dotted one, and [widthDp] makes urgent segments visibly heavier. The legend spells out the time
 * bucket in words as well.
 */
enum class CurbStatus(
    val label: String,
    /** Hex for a light basemap. */
    val lightHex: String,
    /** Lifted for a dark basemap, keeping roughly the same hue at higher luminance. */
    val darkHex: String,
    val widthDp: Float,
    val strokePattern: StrokePattern,
) {
    /** A restriction window is open right now. Parked here, you are being ticketed. */
    RESTRICTED_NOW("Restricted now", "#C81E3C", "#FF6B81", 5.0f, StrokePattern.DASHED),

    /** The next window opens in under an hour. */
    MOVE_WITHIN_HOUR("Move within the hour", "#E8590C", "#FF9A52", 4.5f, StrokePattern.DASHED),

    /** The next window opens later today or overnight — move before morning. */
    MOVE_TODAY("Move before tomorrow", "#E0A100", "#FFD24A", 4.0f, StrokePattern.SOLID),

    /** Next window within two days. Fine for a short stay, plan around it for a long one. */
    MOVE_IN_TWO_DAYS("Restricted within 2 days", "#8CB33A", "#B7E05A", 3.5f, StrokePattern.SOLID),

    /** Nothing due for at least two days, or no restrictions at all on this curb. */
    CLEAR("Clear for 2+ days", "#2E8B57", "#4FD18B", 3.0f, StrokePattern.SOLID),

    /** Alternate side is suspended today, so today's window does not apply. */
    SUSPENDED_TODAY("ASP suspended today", "#3D7EBB", "#7FB8EE", 3.5f, StrokePattern.SOLID),

    /** No standing/stopping at all hours. Never a legal overnight spot. */
    ALWAYS_RESTRICTED("No parking anytime", "#6B4C9A", "#B69AE0", 4.0f, StrokePattern.SOLID),

    /** No usable sign data for this curb. Drawn faintly so it reads as absence, not permission. */
    UNKNOWN("No sign data", "#9AA0A6", "#6E7479", 2.5f, StrokePattern.DOTTED);

    /** True when the driver has to act on this within the hour. */
    val isUrgent: Boolean
        get() = this == RESTRICTED_NOW || this == MOVE_WITHIN_HOUR

    companion object {
        /** Buckets minutes-until-restriction onto the ramp. Negative means a window is already open. */
        fun fromMinutesUntil(minutes: Long): CurbStatus = when {
            minutes < 0 -> RESTRICTED_NOW
            minutes < 60 -> MOVE_WITHIN_HOUR
            minutes < 12 * 60 -> MOVE_TODAY
            minutes < 48 * 60 -> MOVE_IN_TWO_DAYS
            else -> CLEAR
        }
    }
}

enum class StrokePattern {
    SOLID,
    DASHED,
    DOTTED;

    /** Dash array in line-widths, the unit MapLibre's `line-dasharray` expects. */
    val dashArray: List<Float>?
        get() = when (this) {
            SOLID -> null
            DASHED -> listOf(2f, 1.2f)
            DOTTED -> listOf(0.4f, 1.6f)
        }
}
