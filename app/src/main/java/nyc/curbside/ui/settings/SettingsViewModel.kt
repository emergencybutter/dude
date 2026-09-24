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
import nyc.curbside.data.Vehicle
import nyc.curbside.data.VehicleRepository
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
    /** The household's cars. Empty until one is nominated on this screen. */
    val vehicles: List<Vehicle> = emptyList(),
    val householdId: String? = null,
    val autoShare: Boolean = false,
    /** False when the build carries no Firebase config; the sharing card says so and offers nothing. */
    val sharingAvailable: Boolean = false,
    val datasetVersion: String? = null,
    val segmentCount: Int = 0,
    val suspensionSummary: String = "Suspension calendar not loaded yet.",
    /** Set when the user asks for a pairing code; the screen renders it as a QR. */
    val pendingInvite: PairingInvite? = null,
    /** True while the camera dialog is up. */
    val scanning: Boolean = false,
    /** Why the last pairing attempt got nowhere, for the sharing card to say so. */
    val pairingError: String? = null,
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
    private val vehicles: VehicleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState(sharingAvailable = household.isAvailable))

    val state: StateFlow<SettingsUiState> = combine(
        vehicles.vehicles,
        settings.householdId,
        settings.autoShareEnabled,
        settings.aspDatasetVersion,
        local,
    ) { cars, householdId, autoShare, version, base ->
        base.copy(
            vehicles = cars,
            carBluetoothName = cars.firstOrNull()?.name,
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
            androidAutoSeen = settings.readAndroidAutoSeen(),
            segmentCount = curbDao.count(),
            suspensionSummary = summary,
        )
    }

    fun onAutoShareChanged(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoShare(enabled) }
    }

    /**
     * Produces the code the other phone scans.
     *
     * An invite into the household this device already belongs to, or a brand new household on the
     * first phone. Never a second household: [HouseholdRepository.createHousehold] mints a fresh
     * key, and doing that while one is in use would strand every spot already shared.
     */
    fun onShowPairingCode() {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, pairingError = null)
            val invite = if (settings.readHouseholdId() == null) {
                household.createHousehold(DEFAULT_MEMBER_NAME)
            } else {
                household.invite()
            }
            local.value = local.value.copy(
                pendingInvite = invite,
                pairingError = if (invite == null) NO_INVITE else null,
                busy = false,
            )
        }
    }

    fun onDismissPairingCode() {
        local.value = local.value.copy(pendingInvite = null)
    }

    fun onScanPairing() {
        local.value = local.value.copy(scanning = true, pendingInvite = null, pairingError = null)
    }

    fun onScannerDismissed() {
        local.value = local.value.copy(scanning = false)
    }

    /**
     * Redeems a scanned code.
     *
     * The camera plumbing lives in the screen, where the permission is requested; this takes the
     * raw payload so the failures — somebody else's QR, an invite already used or expired — are
     * told apart here and reported in one place.
     */
    fun onPairingScanned(raw: String) {
        val invite = PairingInvite.parse(raw)
        if (invite == null) {
            local.value = local.value.copy(scanning = false, pairingError = NOT_A_PAIRING_CODE)
            return
        }
        viewModelScope.launch {
            local.value = local.value.copy(scanning = false, busy = true, pairingError = null)
            val joined = household.join(invite, DEFAULT_MEMBER_NAME)
            local.value = local.value.copy(
                busy = false,
                pairingError = if (joined) null else COULD_NOT_JOIN,
            )
        }
    }

    fun onLeaveHousehold() {
        viewModelScope.launch {
            household.leave()
            local.value = local.value.copy(pendingInvite = null, pairingError = null)
        }
    }

    /**
     * Records which paired device is the car.
     *
     * Naming it is what makes a Bluetooth disconnect mean "the drive ended" rather than "the
     * headphones came off", so [CarBluetoothReceiver] ignores every other device. Passing null
     * forgets it and falls back to motion sensing alone.
     */
    fun onVehicleAdded(address: String, name: String) {
        viewModelScope.launch { vehicles.add(Vehicle.fromStereo(address, name)) }
    }

    fun onVehicleRemoved(id: String) {
        viewModelScope.launch { vehicles.remove(id) }
    }

    fun onVehicleRenamed(id: String, name: String) {
        viewModelScope.launch { vehicles.rename(id, name) }
    }

    fun onRefreshData() {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true)
            registrar.ensureRegistered()
            installer.installIfNeeded()
            refreshDiagnostics()
            local.value = local.value.copy(busy = false)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_MEMBER_NAME = "Me"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

        const val NO_INVITE =
            "Could not reach the server to make a code. Check your connection and try again."
        const val NOT_A_PAIRING_CODE =
            "That is not a Curbside pairing code. Scan the square the other phone is showing " +
                "under Settings › Sharing."
        const val COULD_NOT_JOIN =
            "That code did not work. They expire after fifteen minutes and only work once — " +
                "ask the other phone for a fresh one."
    }
}
