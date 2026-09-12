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
import nyc.curbside.asp.EvaluatedCurb
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
) {
    val previewedAt: ZonedDateTime get() = ZonedDateTime.now(NYC).plusHours(previewOffsetHours.toLong())
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val asp: AspRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var viewport: BoundingBox? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(datasetInstalled = asp.isInstalled())
        }
        // Statuses go stale on their own: a curb that reads "move within the hour" becomes "move
        // now" with no user action. A minute is fine — the buckets are hours wide.
        viewModelScope.launch {
            while (true) {
                delay(REFRESH_MILLIS)
                if (_state.value.previewOffsetHours == 0) reload()
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
