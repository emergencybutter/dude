package nyc.curbside.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import nyc.curbside.BuildConfig
import nyc.curbside.asp.BoundingBox
import nyc.curbside.asp.CurbMatcher
import nyc.curbside.asp.CurbStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Downtown Brooklyn, the fallback when location is refused or has not arrived yet. */
private val DEFAULT_CENTER = LatLng(40.6892, -73.9857)
private const val DEFAULT_ZOOM = 15.5

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasLocationPermission(context: Context): Boolean =
    LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private val PREVIEW_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h a")

/** Roughly a fingertip. Curb lines are a few pixels wide, so the hit area has to be generous. */
private const val TAP_RADIUS_PIXELS = 22.0

/** Zoomed all the way in, a fingertip covers less than a lane; do not let the target shrink below this. */
private const val MIN_TAP_METERS = 6.0

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()

    Box(Modifier.fillMaxSize()) {
        AspMap(
            curbs = state.curbs,
            cars = state.cars,
            selectedId = state.selectedId,
            darkTheme = darkTheme,
            onViewportChanged = viewModel::onViewportChanged,
            onTap = viewModel::onMapTapped,
            onLongPress = viewModel::onMapLongPressed,
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
            val pending = state.pendingPin
            val selected = state.selected
            when {
                pending != null -> PlaceCarCard(
                    onConfirm = viewModel::onPendingPinConfirmed,
                    onCancel = viewModel::onPendingPinCancelled,
                )
                selected != null -> CurbDetailCard(selected, onDismiss = viewModel::clearSelection)
                else -> Legend()
            }
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

/**
 * Where each car sits on screen, in pixels, recomputed as the camera moves.
 *
 * The car markers are drawn in Compose on top of the map rather than as a MapLibre layer. A circle
 * layer and a symbol layer, both bound to a source holding a point in view, both reported visible
 * with sane properties, rendered nothing at all — while the curb line layers on an identical
 * arrangement drew fine. For a handful of markers, projecting the coordinates and drawing them
 * ourselves is less machinery than the layer it replaces, and it cannot fail silently.
 */
private data class ScreenPin(val car: CarPin, val x: Float, val y: Float)

@Composable
private fun AspMap(
    curbs: List<nyc.curbside.asp.EvaluatedCurb>,
    cars: List<CarPin>,
    selectedId: String?,
    darkTheme: Boolean,
    onViewportChanged: (BoundingBox) -> Unit,
    onTap: (point: nyc.curbside.asp.LatLng, toleranceMeters: Double) -> Unit,
    onLongPress: (nyc.curbside.asp.LatLng) -> Unit,
) {
    val context = LocalContext.current

    // The click listener is registered once, against the map, and outlives every recomposition;
    // capturing the lambda directly would pin the first one forever.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentCars by rememberUpdatedState(cars)

    // `update` runs on every recomposition, and `map.style` stays null for the whole second or so
    // the style takes to load — so several passes each saw "no style yet" and each called setStyle.
    // Two style loads then race, every one of them adding the same sources and layers, and what
    // survives is bound to whichever source lost. Ask once.
    val styleRequested = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    // Declared after MapLibre.getInstance, and that ordering is load-bearing: constructing any
    // MapLibre object before the SDK is initialised throws "ThreadUtils isn't correctly
    // initialised". The source is held rather than looked up by id, because getSourceAs returned
    // one the style acknowledged but that setGeoJson never reached — features went in and the
    // map's own count of the source stayed at zero while the curb source filled normally.
    var screenPins by remember { mutableStateOf<List<ScreenPin>>(emptyList()) }

    // Foreground location, asked for here rather than at launch: opening a map of where you may
    // park is the moment showing where you are first makes sense. Refusing it costs the blue dot
    // and the opening camera position, nothing else — the rules are already on the device.
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { locationGranted = it.values.any { granted -> granted } }

    LaunchedEffect(Unit) {
        if (!locationGranted) permissionRequest.launch(LOCATION_PERMISSIONS)
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

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.getMapAsync { map ->
                if (map.style == null && styleRequested.compareAndSet(false, true)) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(DEFAULT_CENTER)
                        .zoom(DEFAULT_ZOOM)
                        .build()

                    map.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL)) { style ->
                        style.addSource(AspMapLayer.emptySource())
                        AspMapLayer.layers(darkTheme).forEach(style::addLayer)

                        if (locationGranted) showWhereYouAre(map, style, context)

                        fun reportViewport() {
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

                        // Only reload on idle, never mid-gesture: querying and re-serialising a few
                        // thousand features on every frame of a pan would drop the map to single
                        // digit frames per second for no benefit.
                        map.addOnCameraIdleListener(::reportViewport)

                        fun projectCars() {
                            screenPins = currentCars.map { car ->
                                val point = map.projection
                                    .toScreenLocation(LatLng(car.latitude, car.longitude))
                                ScreenPin(car, point.x, point.y)
                            }
                        }

                        map.addOnCameraMoveListener(::projectCars)
                        map.addOnCameraIdleListener(::projectCars)
                        projectCars()

                        // The camera is positioned before the style finishes loading, so it is
                        // already at rest by the time the listener above exists and no idle event
                        // is ever fired for the opening view. Without this the map opens empty and
                        // only fills in once the user pans.
                        reportViewport()
                    }
                }

                // The permission may be granted after the style has already loaded, which is the
                // usual case: the dialog is answered while the map behind it is drawing.
                val style = map.style
                if (style != null && locationGranted && !map.locationComponent.isLocationComponentActivated) {
                    showWhereYouAre(map, style, context)
                }

                style
                    ?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(AspMapLayer.SOURCE_ID)
                    ?.setGeoJson(AspMapLayer.toFeatureCollection(curbs, selectedId))

                if (style != null) {
                    screenPins = cars.map { car ->
                        val point = map.projection.toScreenLocation(LatLng(car.latitude, car.longitude))
                        ScreenPin(car, point.x, point.y)
                    }
                }
            }
        },
    )

        screenPins.forEach { pin -> CarMarker(pin) }
    }
}

/** One car, drawn over the map at the position the projection put it. */
@Composable
private fun CarMarker(pin: ScreenPin) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .offset(
                x = with(density) { pin.x.toDp() } - MARKER_SIZE / 2,
                y = with(density) { pin.y.toDp() } - MARKER_SIZE / 2,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(MARKER_SIZE)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            tonalElevation = 3.dp,
        ) {
            Text(
                pin.car.label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

private val MARKER_SIZE = 18.dp

/**
 * Turns on the blue dot and points the camera at it.
 *
 * [CameraMode.TRACKING] follows the location until the user pans, at which point MapLibre drops
 * back to [CameraMode.NONE] and leaves the map where they put it. The subscription lives exactly as
 * long as the map is on screen: [MapView.onStop] ends it, so this costs nothing between visits and
 * nothing at all when the map is closed. It is unrelated to the once-per-drive fix that finds the
 * car, which does not need the map to be open.
 */
@SuppressLint("MissingPermission") // Only called behind hasLocationPermission.
private fun showWhereYouAre(map: MapLibreMap, style: Style, context: Context) {
    map.locationComponent.apply {
        activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationEngine(LocationEngineDefault.getDefaultLocationEngine(context))
                .build(),
        )
        isLocationComponentEnabled = true
        renderMode = RenderMode.COMPASS
        cameraMode = CameraMode.TRACKING
    }
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

/**
 * Confirms a car position the user placed by hand.
 *
 * Needed whenever detection could not: no usable fix at the end of a drive, a fix too rough to be
 * worth trusting, or somebody else's car that the app was never going to notice.
 */
@Composable
private fun PlaceCarCard(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Put the car here?", style = MaterialTheme.typography.titleMedium)
            Text(
                "This replaces wherever Curbside thinks the car is now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Park here") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
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
