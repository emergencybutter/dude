package nyc.curbside.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspRepository
import nyc.curbside.asp.CurbEvaluation
import nyc.curbside.asp.CurbStatus
import nyc.curbside.asp.NYC
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.ParkingRepository
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.share.ShareCoordinator

data class HomeUiState(
    val car: ParkingEventEntity? = null,
    val evaluation: CurbEvaluation? = null,
    val curbLabel: String? = null,
    val partnerCars: List<ParkingEventEntity> = emptyList(),
    val autoShareEnabled: Boolean = false,
    val needsSideConfirmation: Boolean = false,
    /** Recomputed every second so the countdown ticks without the whole state churning. */
    val timeUntilMove: Duration? = null,
) {
    val status: CurbStatus get() = evaluation?.status ?: CurbStatus.UNKNOWN
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val parking: ParkingRepository,
    private val asp: AspRepository,
    private val share: ShareCoordinator,
    settings: CurbsideSettings,
) : ViewModel() {

    private val tick = MutableStateFlow(Instant.now())

    val state: StateFlow<HomeUiState> = combine(
        parking.observeCurrent(),
        parking.observeReceived(),
        settings.autoShareEnabled,
        tick,
    ) { car, partnerCars, autoShare, now ->
        val evaluated = car?.curbSegmentId?.let { asp.curbById(it, ZonedDateTime.now(NYC)) }

        HomeUiState(
            car = car,
            evaluation = evaluated?.evaluation,
            curbLabel = evaluated?.curb?.segment?.let { "${it.onStreet} between ${it.fromStreet} and ${it.toStreet}" },
            partnerCars = partnerCars,
            autoShareEnabled = autoShare,
            needsSideConfirmation = car != null && car.curbSegmentId != null && !car.curbSideConfirmed,
            timeUntilMove = car?.moveByEpochMillis
                ?.let { Duration.between(now, Instant.ofEpochMilli(it)) }
                ?.takeIf { !it.isNegative },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    init {
        viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                tick.value = Instant.now()
            }
        }
    }

    fun onDroveAway() {
        viewModelScope.launch { parking.clearCurrent() }
    }

    fun onNoteChanged(note: String) {
        val id = state.value.car?.id ?: return
        viewModelScope.launch { parking.setNote(id, note) }
    }

    fun onSideCorrected(segmentId: String) {
        val id = state.value.car?.id ?: return
        viewModelScope.launch { parking.setCurb(id, segmentId) }
    }

    fun shareIntent() = state.value.car?.let(share::manualShareIntent)

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
