package nyc.curbside.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import nyc.curbside.BuildConfig
import nyc.curbside.asp.BoundingBox
import nyc.curbside.asp.CurbStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Downtown Brooklyn, an arbitrary but sane starting view when there is no car and no GPS yet. */
private val DEFAULT_CENTER = LatLng(40.6892, -73.9857)
private const val DEFAULT_ZOOM = 15.5

private val PREVIEW_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h a")

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()

    Box(Modifier.fillMaxSize()) {
        AspMap(
            curbs = state.curbs,
            darkTheme = darkTheme,
            onViewportChanged = viewModel::onViewportChanged,
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimeScrubber(
                offsetHours = state.previewOffsetHours,
                onChange = viewModel::setPreviewOffsetHours,
                label = if (state.previewOffsetHours == 0) {
                    "Right now"
                } else {
                    PREVIEW_LABEL.format(state.previewedAt)
                },
            )
            Legend()
        }

        if (!state.datasetInstalled) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
            ) {
                Text(
                    "Downloading the city's sign data. This happens once, on wifi.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AspMap(
    curbs: List<nyc.curbside.asp.EvaluatedCurb>,
    darkTheme: Boolean,
    onViewportChanged: (BoundingBox) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.getMapAsync { map ->
                if (map.style == null) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(DEFAULT_CENTER)
                        .zoom(DEFAULT_ZOOM)
                        .build()

                    map.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL)) { style ->
                        style.addSource(AspMapLayer.emptySource())
                        AspMapLayer.layers(darkTheme).forEach(style::addLayer)

                        // Only reload on idle, never mid-gesture: querying and re-serialising a few
                        // thousand features on every frame of a pan would drop the map to single
                        // digit frames per second for no benefit.
                        map.addOnCameraIdleListener {
                            val bounds = map.projection.visibleRegion.latLngBounds
                            onViewportChanged(
                                BoundingBox(
                                    minLat = bounds.latitudeSouth,
                                    minLon = bounds.longitudeWest,
                                    maxLat = bounds.latitudeNorth,
                                    maxLon = bounds.longitudeEast,
                                ),
                            )
                        }
                    }
                }

                map.style
                    ?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(AspMapLayer.SOURCE_ID)
                    ?.setGeoJson(AspMapLayer.toFeatureCollection(curbs))
            }
        },
    )
}

/**
 * Shifts the map's clock forward by up to two days.
 *
 * "Where can I leave the car until Thursday morning" is the question New Yorkers actually ask, and
 * it is unanswerable from a map that only shows the present. Two days is the useful range: past
 * that, every curb has been cleaned at least once and the colours stop meaning anything.
 */
@Composable
private fun TimeScrubber(offsetHours: Int, onChange: (Int) -> Unit, label: String) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = offsetHours.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 0f..48f,
                steps = 47,
            )
        }
    }
}

@Composable
private fun Legend() {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CurbStatus.entries.forEach { status ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(android.graphics.Color.parseColor(status.lightHex)), CircleShape),
                    )
                    Text(status.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
