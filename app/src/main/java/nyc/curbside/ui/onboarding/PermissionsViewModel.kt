package nyc.curbside.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.Vehicle
import nyc.curbside.data.VehicleRepository
import nyc.curbside.detect.DetectionRegistrar

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val settings: CurbsideSettings,
    private val registrar: DetectionRegistrar,
    private val vehicles: VehicleRepository,
) : ViewModel() {

    /** Null until the stored flag has been read, so no screen is chosen on a guess. */
    val explained: StateFlow<Boolean?> =
        settings.permissionsExplained.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Arms activity transitions now that the permission may have arrived.
     *
     * Registration is the step that was silently skipped before this screen existed: the app asked
     * Play Services for transitions once at launch, found no permission, and never asked again.
     */
    fun onPermissionsChanged() {
        viewModelScope.launch { registrar.ensureRegistered() }
    }

    /**
     * The stereo picked during onboarding becomes the household's first car.
     *
     * Offered here rather than only in Settings because it is the difference between detection that
     * knows when the ignition went off and detection guessing from motion: a user who never opens
     * Settings would otherwise run on the weakest signal without being told.
     *
     * Called once per car when several are detected at once, which is the ordinary case for a
     * household that has paired both cars with this phone.
     */
    fun onCarStereoChosen(address: String, label: String) {
        viewModelScope.launch { vehicles.add(Vehicle.fromStereo(address, label)) }
    }

    /** Marks the explanation as seen, whatever the user decided to grant. */
    fun onFinished() {
        viewModelScope.launch { settings.setPermissionsExplained(true) }
    }
}
