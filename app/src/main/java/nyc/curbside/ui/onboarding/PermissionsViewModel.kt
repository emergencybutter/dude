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
import nyc.curbside.detect.DetectionRegistrar

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val settings: CurbsideSettings,
    private val registrar: DetectionRegistrar,
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

    /** Marks the explanation as seen, whatever the user decided to grant. */
    fun onFinished() {
        viewModelScope.launch { settings.setPermissionsExplained(true) }
    }
}
