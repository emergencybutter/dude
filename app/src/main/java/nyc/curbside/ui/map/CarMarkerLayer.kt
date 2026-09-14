package nyc.curbside.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import nyc.curbside.R
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** A parked car, as the map needs to draw it. */
data class CarPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** The car's name, or a stand-in when the app could not tell which car it was. */
    val label: String,
    val byPartner: Boolean,
)

/**
 * Where the cars are.
 *
 * Drawn as symbols above the curb overlay rather than as part of it: the overlay answers "what are
 * the rules here", and this answers "where did I leave it", which is the question people open the
 * map with when they have forgotten.
 *
 * The pin is deliberately neutral — near-black on a light basemap, white on a dark one, with a halo
 * the other way. Every colour with meaning on this map is already spoken for by a curb status, and
 * a car marker in lime or amber would read as a verdict about the kerb under it.
 */
object CarMarkerLayer {

    const val SOURCE_ID = "car-pins"
    const val LAYER_ID = "car-pins-symbols"
    const val ICON_ID = "car-pin"

    const val PROPERTY_LABEL = "label"

    fun emptySource(): GeoJsonSource =
        GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList()))

    fun toFeatureCollection(cars: List<CarPin>): FeatureCollection = FeatureCollection.fromFeatures(
        cars.map { car ->
            Feature.fromGeometry(Point.fromLngLat(car.longitude, car.latitude)).apply {
                addStringProperty(PROPERTY_LABEL, car.label)
            }
        },
    )

    /**
     * The pin bitmap, tinted for the basemap it will sit on.
     *
     * MapLibre symbol layers draw registered images, not drawables, so the vector the notifications
     * use is rasterised once when the style loads.
     */
    fun icon(context: Context, darkTheme: Boolean): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_pin) ?: return null
        drawable.mutate().setTint(if (darkTheme) Color.WHITE else Color.parseColor("#17181A"))
        return drawable.toBitmap(width = ICON_PIXELS, height = ICON_PIXELS)
    }

    fun layer(darkTheme: Boolean): SymbolLayer = SymbolLayer(LAYER_ID, SOURCE_ID)
        .withProperties(
            PropertyFactory.iconImage(ICON_ID),
            // The tip of the pin is the position, not the middle of the drop.
            PropertyFactory.iconAnchor("bottom"),
            // A car is never clutter to be thinned out: if it is on screen it has to be drawn, even
            // when a street label wants the same pixels.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(11f, 0.6f),
                    Expression.stop(17f, 1.0f),
                ),
            ),
            PropertyFactory.textField(Expression.get(PROPERTY_LABEL)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
            PropertyFactory.textSize(12f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textColor(if (darkTheme) Color.WHITE else Color.parseColor("#17181A")),
            // A halo the other way round, so the name stays readable over a park, a road, or a
            // curb line in any of the status colours.
            PropertyFactory.textHaloColor(if (darkTheme) Color.parseColor("#17181A") else Color.WHITE),
            PropertyFactory.textHaloWidth(1.4f),
        )

    /** Big enough to stay crisp on a 3x screen at full icon size. */
    private const val ICON_PIXELS = 84
}
