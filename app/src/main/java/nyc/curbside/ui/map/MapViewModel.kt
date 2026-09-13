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
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspRepository
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
) {
    val previewedAt: ZonedDateTime get() = ZonedDateTime.now(NYC).plusHours(previewOffsetHours.toLong())

    val selectedId: String? get() = selected?.curb?.curb?.segment?.id
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val asp: AspRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var viewport: BoundingBox? = null
    private var loadJob: Job? = null
    private var selectedId: String? = null
    private var selectJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(datasetInstalled = asp.isInstalled())
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
        refreshSelection()
    }

    fun clearSelection() {
        selectedId = null
        selectJob?.cancel()
        _state.value = _state.value.copy(selected = null)
    }

    private fun refreshSelection() {
        val id = selectedId ?: return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            _state.value = _state.value.copy(selected = asp.curbDetail(id, _state.value.previewedAt))
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
