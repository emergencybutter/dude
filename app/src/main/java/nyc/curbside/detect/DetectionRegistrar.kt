package nyc.curbside.detect

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import nyc.curbside.data.CurbsideSettings

/**
 * Owns the one always-on subscription the app has: Play Services activity transitions.
 *
 * ## Why this and not a location subscription
 *
 * The obvious way to notice parking is to watch where the phone is. That is also the way to ruin
 * the battery: a continuous fused-location subscription keeps GPS or at least Wi-Fi scanning warm
 * all day, for a signal we need roughly twice.
 *
 * Activity transitions invert it. The detector runs on the phone's sensor hub — the low-power core
 * that is already awake counting steps — using only the accelerometer. Nothing in our app runs
 * between transitions; the app process does not even exist. When the detector concludes the user
 * entered or left a vehicle it fires a PendingIntent, which wakes us for a few hundred milliseconds.
 *
 * The three signals then layer up: transitions provide the wake-up that works from a dead process,
 * Bluetooth provides an ignition-accurate edge for cars that pair, and Android Auto provides the
 * most precise edge of all for the drives where it is in use — see [CarConnectionMonitor].
 */
@Singleton
class DetectionRegistrar @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
) {

    private val transitions = listOf(
        transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
        // ON_FOOT is the confirmation that the user has actually left the car, and it is what lets
        // the state machine skip the Bluetooth debounce entirely.
        transition(DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
        transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
    )

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Registers transitions if they are not already registered. Safe to call on every app start and
     * after boot; Play Services treats a repeat registration with the same PendingIntent as a
     * replace, so the cost of being wrong is nil and the cost of skipping it is silent failure.
     */
    suspend fun ensureRegistered(): Boolean {
        if (!hasPermission()) {
            settings.setTransitionsRegistered(false)
            return false
        }
        return runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                .await()
            settings.setTransitionsRegistered(true)
            true
        }.getOrElse {
            // Play Services missing or out of date. Bluetooth and manual capture still work, and
            // the settings screen surfaces the degraded state rather than pretending all is well.
            settings.setTransitionsRegistered(false)
            false
        }
    }

    suspend fun unregister() {
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent())
                .await()
        }
        settings.setTransitionsRegistered(false)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ActivityTransitionReceiver::class.java),
        // Mutable because Play Services writes the transition result into the intent it sends.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun transition(activity: Int, type: Int) = ActivityTransition.Builder()
        .setActivityType(activity)
        .setActivityTransition(type)
        .build()

    private companion object {
        const val REQUEST_CODE = 4_201
    }
}
