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
import nyc.curbside.asp.LatLng
import nyc.curbside.asp.NYC
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.Vehicle
import nyc.curbside.data.VehicleRepository
import nyc.curbside.data.ParkingRepository
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.share.ShareCoordinator

/** One car, parked, as the home screen talks about it. */
data class ParkedCarRow(
    val event: ParkingEventEntity,
    /** The car's name, or null when the app could not tell which car this is. */
    val vehicleName: String?,
    val evaluation: CurbEvaluation?,
    val curbLabel: String?,
    val timeUntilMove: Duration?,
    /** True when somebody else in the household left it there. */
    val byPartner: Boolean,
    val needsSideConfirmation: Boolean,
) {
    val status: CurbStatus get() = evaluation?.status ?: CurbStatus.UNKNOWN

    /** The app has cars set up but could not say which this was, so it has to ask. */
    val needsVehicle: Boolean get() = event.vehicleId == null && !byPartner
}

data class HomeUiState(
    /**
     * Every car currently parked, whoever left it there.
     *
     * Not "mine" and "theirs": when your wife parks your car, that spot is the answer to "where is
     * my car", and filing it under her would take your own car off your screen.
     */
    val cars: List<ParkedCarRow> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val autoShareEnabled: Boolean = false,
) {
    /**
     * True when no car has a stereo nominated, so drives are being detected by motion alone.
     *
     * Worth saying on the main screen rather than leaving to be discovered: motion is the weakest
     * of the three signals, it is slower than an ignition-accurate one, it occasionally reads a
     * walk as a drive, and it cannot tell one car from another. Android Auto does not make up for
     * it — that state is readable only while the app is open, which it is not when a drive starts.
     */
    val detectionOnMotionAlone: Boolean get() = vehicles.none { it.bluetoothAddress != null }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val parking: ParkingRepository,
    private val asp: AspRepository,
    private val share: ShareCoordinator,
    private val vehicles: VehicleRepository,
    settings: CurbsideSettings,
) : ViewModel() {

    private val tick = MutableStateFlow(Instant.now())

    val state: StateFlow<HomeUiState> = combine(
        parking.observeActive(),
        vehicles.vehicles,
        settings.autoShareEnabled,
        tick,
    ) { active, cars, autoShare, now ->
        HomeUiState(
            cars = active.map { event -> row(event, cars, now) },
            vehicles = cars,
            autoShareEnabled = autoShare,
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

    /**
     * Everything the screen says about one parked car, resolved at the moment it is displayed
     * rather than stored: the rules move on their own as the clock does.
     */
    private suspend fun row(
        event: ParkingEventEntity,
        vehicles: List<Vehicle>,
        now: Instant,
    ): ParkedCarRow {
        val evaluated = event.curbSegmentId?.let {
            asp.curbById(it, ZonedDateTime.now(NYC), LatLng(event.latitude, event.longitude))
        }
        return ParkedCarRow(
            event = event,
            vehicleName = vehicles.firstOrNull { it.id == event.vehicleId }?.name,
            evaluation = evaluated?.evaluation,
            curbLabel = evaluated?.curb?.segment
                ?.let { "${it.onStreet} between ${it.fromStreet} and ${it.toStreet}" },
            timeUntilMove = event.moveByEpochMillis
                ?.let { Duration.between(now, Instant.ofEpochMilli(it)) }
                ?.takeIf { !it.isNegative },
            byPartner = event.receivedFrom != null,
            needsSideConfirmation = event.curbSegmentId != null && !event.curbSideConfirmed,
        )
    }

    fun onDroveAway(eventId: String) {
        viewModelScope.launch { parking.clearEvent(eventId) }
    }

    fun onNoteChanged(eventId: String, note: String) {
        viewModelScope.launch { parking.setNote(eventId, note) }
    }

    fun onSideCorrected(eventId: String, segmentId: String) {
        viewModelScope.launch { parking.setCurb(eventId, segmentId) }
    }

    /** The user answering "which car was that?". */
    fun onVehicleChosen(eventId: String, vehicleId: String) {
        viewModelScope.launch { parking.assignVehicle(eventId, vehicleId) }
    }

    fun shareIntent(event: ParkingEventEntity) = share.manualShareIntent(event)

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
