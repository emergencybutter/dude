package nyc.curbside.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspDatasetInstaller
import nyc.curbside.asp.NYC
import nyc.curbside.asp.SuspensionRepository
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.db.CurbSegmentDao
import nyc.curbside.detect.CarBluetoothReceiver
import nyc.curbside.detect.DetectionRegistrar
import nyc.curbside.share.HouseholdRepository
import nyc.curbside.share.PairingInvite

data class SettingsUiState(
    val transitionsRegistered: Boolean = false,
    val androidAutoSeen: Boolean = false,
    val carBluetoothName: String? = null,
    val householdId: String? = null,
    val autoShare: Boolean = false,
    /** False when the build carries no Firebase config; the sharing card says so and offers nothing. */
    val sharingAvailable: Boolean = false,
    val datasetVersion: String? = null,
    val segmentCount: Int = 0,
    val suspensionSummary: String = "Suspension calendar not loaded yet.",
    /** Set when the user asks for a pairing code; the screen renders it as a QR. */
    val pendingInvite: PairingInvite? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: CurbsideSettings,
    private val registrar: DetectionRegistrar,
    private val household: HouseholdRepository,
    private val installer: AspDatasetInstaller,
    private val suspensions: SuspensionRepository,
    private val curbDao: CurbSegmentDao,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState(sharingAvailable = household.isAvailable))

    val state: StateFlow<SettingsUiState> = combine(
        settings.carBluetoothName,
        settings.householdId,
        settings.autoShareEnabled,
        settings.aspDatasetVersion,
        local,
    ) { stereo, householdId, autoShare, version, base ->
        base.copy(
            carBluetoothName = stereo,
            householdId = householdId,
            autoShare = autoShare,
            datasetVersion = version,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        SettingsUiState(sharingAvailable = household.isAvailable),
    )

    init {
        viewModelScope.launch { refreshDiagnostics() }
    }

    private suspend fun refreshDiagnostics() {
        val calendar = suspensions.current()
        val summary = when {
            calendar.fetchedAt == null -> "Suspension calendar not loaded yet."
            else -> {
                val upcoming = calendar.suspendedDates
                    .filter { !it.isBefore(java.time.LocalDate.now(NYC)) }
                    .sorted()
                    .take(3)
                    .joinToString(", ") { DAY.format(it) }
                if (upcoming.isEmpty()) {
                    "No alternate side suspensions in the next quarter."
                } else {
                    "Next suspensions: $upcoming."
                }
            }
        }

        local.value = local.value.copy(
            transitionsRegistered = settings.readTransitionsRegistered(),
            segmentCount = curbDao.count(),
            suspensionSummary = summary,
        )
    }

    fun onAutoShareChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoShare(enabled) }
    }

    fun onCreateHousehold() {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            val invite = household.createHousehold(DEFAULT_MEMBER_NAME)
            local.value = local.value.copy(pendingInvite = invite, busy = false)
        }
    }

    /**
     * Hook for the QR scanner. Left as a call into [HouseholdRepository.join] so the camera
     * plumbing lives in the screen where the permission is requested.
     */
    fun onPairingScanned(raw: String) {
        val invite = PairingInvite.parse(raw) ?: return
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            household.join(invite, DEFAULT_MEMBER_NAME)
            local.value = local.value.copy(busy = false)
        }
    }

    fun onScanPairing() {
        // The screen launches the scanner; this exists so the button has somewhere to report to.
        local.value = local.value.copy(pendingInvite = null)
    }

    fun onLeaveHousehold() {
        viewModelScope.launch { household.leave() }
    }

    /**
     * Records which paired device is the car.
     *
     * Naming it is what makes a Bluetooth disconnect mean "the drive ended" rather than "the
     * headphones came off", so [CarBluetoothReceiver] ignores every other device. Passing null
     * forgets it and falls back to motion sensing alone.
     */
    fun onCarStereoChosen(address: String?, name: String?) {
        viewModelScope.launch { settings.setCarBluetooth(address, name) }
    }

    fun onRefreshData() {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            registrar.ensureRegistered()
            suspensions.refresh()
            installer.installIfNeeded()
            refreshDiagnostics()
            local.value = local.value.copy(busy = false)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MEMBER_NAME = "Me"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}
