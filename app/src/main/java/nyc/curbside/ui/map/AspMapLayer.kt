package nyc.curbside.ui.map

import android.graphics.Color
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import nyc.curbside.asp.CurbStatus
import nyc.curbside.asp.EvaluatedCurb
import nyc.curbside.asp.StrokePattern

/**
 * Draws the alternate side overlay.
 *
 * ## Where the colour is decided
 *
 * Not in the style. The obvious approach — encode each curb's weekly schedule as feature properties
 * and write a MapLibre expression that works out the current status — collapses as soon as you try
 * it: expressions have no date arithmetic, no timezone handling, and no notion of a suspension
 * calendar, so the schedule logic would have to be reimplemented, badly, in JSON.
 *
 * Instead the status is computed in Kotlin by the same `SweepSchedule` the rest of the app uses,
 * stamped onto each feature as a string, and the style does nothing but map that string to a
 * colour. A viewport is a few thousand curbs and the evaluation is trivial arithmetic, so this runs
 * in single-digit milliseconds off the main thread on every pan — and, crucially, the map and the
 * "move by" notification can never disagree about what a curb says.
 *
 * ## Three layers, not one
 *
 * `line-dasharray` is not data-driven in MapLibre, so each stroke pattern needs its own layer with
 * a filter. That is also convenient for draw order: the urgent dashed layer sits on top.
 *
 * ## Sides
 *
 * Both curbs of a street share one centreline in the city's data, so each feature carries an offset
 * sign and the style pushes it left or right by a zoom-scaled amount. The two sides visibly
 * separate as you zoom in, and merge into one line when zoomed out, which is the correct behaviour
 * at a zoom where you cannot see which side of a street you are on anyway.
 */
object AspMapLayer {

    const val SOURCE_ID = "asp-curbs"
    const val LAYER_SELECTED = "asp-curbs-selected"
    const val LAYER_SOLID = "asp-curbs-solid"
    const val LAYER_DASHED = "asp-curbs-dashed"
    const val LAYER_DOTTED = "asp-curbs-dotted"

    const val PROPERTY_STATUS = "status"
    const val PROPERTY_OFFSET_SIGN = "offset_sign"
    const val PROPERTY_SEGMENT_ID = "segment_id"
    const val PROPERTY_LABEL = "label"
    const val PROPERTY_SELECTED = "selected"

    /** Below this the overlay is hidden: the lines would be an unreadable smear of colour. */
    const val MIN_ZOOM = 14.0f

    fun emptySource(): GeoJsonSource = GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList()))

    /**
     * @param selectedId the curb the user tapped, stamped onto its feature so the highlight layer
     *   can filter for it. Carried as a property rather than as a layer filter rebuilt on every
     *   selection, because the features are re-uploaded on selection change anyway.
     */
    fun toFeatureCollection(
        curbs: List<EvaluatedCurb>,
        selectedId: String? = null,
    ): FeatureCollection = FeatureCollection.fromFeatures(
        curbs.mapNotNull { evaluated ->
            val points = evaluated.curb.geometry.map { Point.fromLngLat(it.lon, it.lat) }
            if (points.size < 2) return@mapNotNull null

            Feature.fromGeometry(LineString.fromLngLats(points)).apply {
                addStringProperty(PROPERTY_STATUS, evaluated.evaluation.status.name)
                addNumberProperty(PROPERTY_OFFSET_SIGN, evaluated.curb.sideSign)
                addStringProperty(PROPERTY_SEGMENT_ID, evaluated.curb.segment.id)
                addStringProperty(PROPERTY_LABEL, label(evaluated))
                addBooleanProperty(PROPERTY_SELECTED, evaluated.curb.segment.id == selectedId)
            }
        },
    )

    private fun label(evaluated: EvaluatedCurb): String {
        val segment = evaluated.curb.segment
        val where = "${segment.onStreet} · ${segment.side.name.lowercase()} side"
        val next = evaluated.evaluation.next?.regulation?.window
        return if (next != null) "$where · ${evaluated.evaluation.status.label} ($next)" else where
    }

    fun layers(darkTheme: Boolean): List<LineLayer> = listOf(
        // First, so the halo sits under the curb it is highlighting rather than washing it out.
        selectionLayer(darkTheme),
        layer(LAYER_SOLID, StrokePattern.SOLID, darkTheme),
        layer(LAYER_DOTTED, StrokePattern.DOTTED, darkTheme),
        // Dashed last so "move now" and "move within the hour" draw over everything else.
        layer(LAYER_DASHED, StrokePattern.DASHED, darkTheme),
    )

    /**
     * A wide neutral casing under the selected curb.
     *
     * Neutral rather than a colour of its own: the curb's own status colour is the information on
     * this map, and a selection tint over it would either hide that or invent a ninth status. A
     * halo reads as "this one" without saying anything about the rules.
     */
    private fun selectionLayer(darkTheme: Boolean): LineLayer = LineLayer(LAYER_SELECTED, SOURCE_ID)
        .withProperties(
            PropertyFactory.lineColor(Color.parseColor(if (darkTheme) "#FFFFFF" else "#202124")),
            PropertyFactory.lineOpacity(if (darkTheme) 0.55f else 0.35f),
            PropertyFactory.lineOffset(offsetExpression()),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(MIN_ZOOM, 4.0f),
                    Expression.stop(18f, 13.0f),
                ),
            ),
        )
        .withFilter(Expression.eq(Expression.get(PROPERTY_SELECTED), Expression.literal(true)))
        .also { it.minZoom = MIN_ZOOM }

    private fun layer(id: String, pattern: StrokePattern, darkTheme: Boolean): LineLayer {
        val statuses = CurbStatus.entries.filter { it.strokePattern == pattern }

        val properties = mutableListOf(
            PropertyFactory.lineColor(colorExpression(darkTheme)),
            PropertyFactory.lineWidth(widthExpression()),
            PropertyFactory.lineOffset(offsetExpression()),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineOpacity(
                // Fade in over one zoom level rather than popping into existence.
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(MIN_ZOOM, 0.0f),
                    Expression.stop(MIN_ZOOM + 1f, 0.9f),
                ),
            ),
        )
        pattern.dashArray?.let { dashes ->
            properties += PropertyFactory.lineDasharray(dashes.toTypedArray())
        }

        return LineLayer(id, SOURCE_ID)
            .withProperties(*properties.toTypedArray())
            .withFilter(
                Expression.match(
                    Expression.get(PROPERTY_STATUS),
                    *statuses.flatMap { listOf(Expression.literal(it.name), Expression.literal(true)) }
                        .toTypedArray(),
                    Expression.literal(false),
                ),
            )
            .also { it.minZoom = MIN_ZOOM }
    }

    /**
     * One `match` from status name to the palette defined alongside the rule engine.
     *
     * The fallback goes last. `Expression.match(input, default, vararg Stop)` exists and takes the
     * default second, but its stops are [Expression.Stop]; handing it bare expressions binds the
     * raw `match(vararg Expression)` instead, which emits its arguments verbatim — putting the
     * default where the first branch label belongs, and getting the whole property rejected.
     */
    private fun colorExpression(darkTheme: Boolean): Expression {
        val stops = CurbStatus.entries.flatMap { status ->
            val hex = if (darkTheme) status.darkHex else status.lightHex
            listOf(Expression.literal(status.name), Expression.color(Color.parseColor(hex)))
        }
        return Expression.match(
            Expression.get(PROPERTY_STATUS),
            *stops.toTypedArray(),
            Expression.color(Color.parseColor(CurbStatus.UNKNOWN.lightHex)),
        )
    }

    /**
     * Width scales with zoom and with urgency: at street level a "sweeping now" curb is noticeably
     * heavier than a clear one, which is the second, non-colour channel the legend relies on.
     */
    private fun widthExpression(): Expression = Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(MIN_ZOOM, Expression.product(urgencyWeight(), Expression.literal(0.35f))),
        Expression.stop(18f, Expression.product(urgencyWeight(), Expression.literal(1.1f))),
    )

    private fun urgencyWeight(): Expression = Expression.match(
        Expression.get(PROPERTY_STATUS),
        *CurbStatus.entries
            .flatMap { listOf(Expression.literal(it.name), Expression.literal(it.widthDp)) }
            .toTypedArray(),
        Expression.literal(CurbStatus.CLEAR.widthDp),
    )

    /**
     * Pushes each curb off the shared centreline towards its own side of the street. The magnitude
     * grows with zoom so the two sides pull apart as you get closer, roughly tracking the apparent
     * width of the roadway.
     */
    private fun offsetExpression(): Expression {
        val side = Expression.toNumber(Expression.get(PROPERTY_OFFSET_SIGN))
        // `zoom` is only legal as the direct input of a top-level `step` or `interpolate`, so the
        // interpolate has to be the whole property: the side multiplies each stop's output rather
        // than the interpolate as a whole. Same curve, legal shape.
        return Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(MIN_ZOOM, Expression.product(side, Expression.literal(1.5f))),
            Expression.stop(18f, Expression.product(side, Expression.literal(7.0f))),
        )
    }
}
