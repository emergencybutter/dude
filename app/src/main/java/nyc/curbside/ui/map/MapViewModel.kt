package nyc.curbside.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspRepository
import nyc.curbside.data.ParkingRepository
import nyc.curbside.data.VehicleRepository
import nyc.curbside.asp.BoundingBox
import nyc.curbside.asp.CurbDetail
import nyc.curbside.asp.CurbMatcher
import nyc.curbside.asp.EvaluatedCurb
import nyc.curbside.asp.LatLng
import nyc.curbside.asp.NYC

data class MapUiState(
    val curbs: List<EvaluatedCurb> = emptyList(),
    val loading: Boolean = false,
    val datasetInstalled: Boolean = true,
    /**
     * How far into the future the map is being previewed, in hours. Zero is now.
     *
     * This is the feature that turns the map from a status display into a planning tool: "where can
     * I leave the car until Thursday" is the actual question, and answering it by scrubbing forward
     * two days is much better than reading seven schedules.
     */
    val previewOffsetHours: Int = 0,
    /** The curb the user tapped, with its rules and its next free span. */
    val selected: CurbDetail? = null,
    /** Every car the app knows the position of, to draw on top of the rules. */
    val cars: List<CarPin> = emptyList(),
    /**
     * A spot the user long-pressed and has not confirmed yet.
     *
     * Long-press rather than tap, and confirmed rather than immediate, because a tap already means
     * "tell me about this curb" and dropping the car somewhere by accident is worse than an extra
     * press.
     */
    val pendingPin: LatLng? = null,
) {
    val previewedAt: ZonedDateTime get() = ZonedDateTime.now(NYC).plusHours(previewOffsetHours.toLong())

    val selectedId: String? get() = selected?.curb?.curb?.segment?.id
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val asp: AspRepository,
    private val parking: ParkingRepository,
    vehicles: VehicleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var viewport: BoundingBox? = null
    private var loadJob: Job? = null
    private var selectedId: String? = null
    private var selectedAt: LatLng? = null
    private var selectJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(datasetInstalled = asp.isInstalled())
        }
        // Where the cars are, kept up to date on its own: a spot shared by a partner arrives
        // through the database, so the map gains a pin without the user touching anything.
        viewModelScope.launch {
            combine(parking.observeActive(), vehicles.vehicles) { parked, cars ->
                parked.map { event ->
                    CarPin(
                        id = event.id,
                        latitude = event.latitude,
                        longitude = event.longitude,
                        label = cars.firstOrNull { it.id == event.vehicleId }?.name
                            ?: if (event.receivedFrom != null) "Their car" else "Your car",
                        byPartner = event.receivedFrom != null,
                    )
                }
            }.collect { pins -> _state.value = _state.value.copy(cars = pins) }
        }
        // Statuses go stale on their own: a curb that reads "move within the hour" becomes "move
        // now" with no user action. A minute is fine — the buckets are hours wide.
        viewModelScope.launch {
            while (true) {
                delay(REFRESH_MILLIS)
                if (_state.value.previewOffsetHours == 0) {
                    reload()
                    refreshSelection()
                }
            }
        }
    }

    /**
     * Called from the map's camera-idle callback. Debounced by cancelling the previous load, so a
     * fling that crosses ten viewports only queries the last one.
     */
    fun onViewportChanged(box: BoundingBox) {
        viewport = box
        reload()
    }

    fun setPreviewOffsetHours(hours: Int) {
        _state.value = _state.value.copy(previewOffsetHours = hours)
        reload()
        // The open sheet answers for the scrubbed moment too — that is most of the point of having
        // both on screen at once: drag to Thursday morning and watch the spot stop being legal.
        refreshSelection()
    }

    /**
     * Resolves a tap on the map to one side of one block.
     *
     * Deliberately not `queryRenderedFeatures`: both curbs of a street are drawn from the same
     * centreline and merely pushed apart by a style offset, so a hit test against the rendered
     * geometry cannot tell the two sides apart — and the side is the whole question, since they are
     * cleaned on different days. [CurbMatcher] already decides which hand of a centreline a point
     * falls on, for parking the car; the same call answers it for a fingertip.
     *
     * @param toleranceMeters how far from a curb still counts as tapping it, which the caller works
     *   out from the zoom so that it stays a constant number of fingertips at any scale.
     */
    fun onMapTapped(point: LatLng, toleranceMeters: Double) {
        val candidates = _state.value.curbs.map { it.curb }
        // A tap has no GPS error: the side it fell on is the side the user meant.
        val hit = CurbMatcher.rank(point, candidates, accuracyMeters = 0f)
            .firstOrNull { it.distanceMeters <= toleranceMeters }

        if (hit == null) {
            clearSelection()
            return
        }
        selectedId = hit.curb.segment.id
        // The tapped point, not just the block: a block is no longer one answer, so the sheet has
        // to know which stretch of it was asked about.
        selectedAt = point
        refreshSelection()
    }

    /** The user pressed and held somewhere on the map. */
    fun onMapLongPressed(point: LatLng) {
        _state.value = _state.value.copy(pendingPin = point, selected = null)
        selectedId = null
        selectedAt = null
    }

    fun onPendingPinCancelled() {
        _state.value = _state.value.copy(pendingPin = null)
    }

    /**
     * Records the car where the user put it.
     *
     * The three places that tell people to "drop a pin" — a capture that found no usable position,
     * a fix too rough to trust, and the empty home screen — had nothing behind them until now:
     * ParkingRepository.recordManual existed and was never called from anywhere.
     */
    fun onPendingPinConfirmed() {
        val point = _state.value.pendingPin ?: return
        viewModelScope.launch {
            parking.recordManual(point)
            _state.value = _state.value.copy(pendingPin = null)
        }
    }

    fun clearSelection() {
        selectedId = null
        selectedAt = null
        selectJob?.cancel()
        _state.value = _state.value.copy(selected = null)
    }

    private fun refreshSelection() {
        val id = selectedId ?: return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                selected = asp.curbDetail(id, _state.value.previewedAt, selectedAt),
            )
        }
    }

    private fun reload() {
        val box = viewport ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val curbs = asp.curbsIn(box, _state.value.previewedAt)
            _state.value = _state.value.copy(curbs = curbs, loading = false)
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
    }
}
