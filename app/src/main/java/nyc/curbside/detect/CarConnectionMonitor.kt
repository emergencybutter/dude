package nyc.curbside.detect

import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nyc.curbside.CurbsideLog
import nyc.curbside.di.ApplicationScope
import nyc.curbside.drive.DriveSignal
import nyc.curbside.drive.SignalKind
import nyc.curbside.drive.SignalSource

/**
 * Watches the Android Auto head-unit connection.
 *
 * This is the most precise signal the app has and the cheapest: the projection state is already
 * being broadcast by the Android Auto app, and observing it costs one registered observer. When the
 * user unplugs, we know the drive is over within a second — no accelerometer heuristics, no
 * waiting for a walking transition.
 *
 * The catch, and the reason this cannot be the only signal: [CarConnection] is a LiveData backed by
 * a content provider and a context-registered receiver, so it only reports while our process is
 * alive. It cannot wake a killed app. So it is wired up as a *refinement* — whenever the process is
 * up, it produces a sharper edge than activity recognition would, and when the process is gone the
 * other two signals carry the load.
 *
 * "While the process is alive" is deliberately not "while the user has the app open". This used to
 * be tied to the activity, which made it worthless in the case it exists for: nobody is looking at
 * the screen while they plug the phone into the dashboard. Bluetooth or a transition wakes the
 * process, and from then on the projection edge is there to be had.
 *
 * @see DetectionRegistrar for the always-on half.
 */
@Singleton
class CarConnectionMonitor @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val coordinator: DriveCoordinator,
    private val settings: nyc.curbside.data.CurbsideSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private var observer: Observer<Int>? = null

    /**
     * The exact LiveData the observer was attached to.
     *
     * `CarConnection(context)` builds a new object every call, each with its own LiveData, so
     * removing an observer from a freshly constructed one removes it from something it was never
     * on. Holding the instance is the only way [stop] can undo what [start] did.
     */
    private var connection: LiveData<Int>? = null
    private var lastType: Int? = null

    /** Starts observing. Idempotent, and safe to call from any thread. */
    fun start() {
        if (observer != null) return

        val liveData = CarConnection(context).type
        val newObserver = Observer<Int> { type ->
            val previous = lastType
            lastType = type
            if (previous == type) return@Observer

            // Recorded off to one side of the edge logic below, and deliberately not gated on it.
            // Whether this reading is an edge we act on is a question about *this drive*; whether a
            // head unit exists at all is a question about the phone, and the settings screen asks
            // the second one. Arriving already plugged in answers it just as well as plugging in.
            if (isConnected(type)) scope.launch { settings.setAndroidAutoSeen() }

            val kind = when {
                // The first value is the state of the world as we find it, not an edge. Swallowing
                // it wholesale meant that starting up with the head unit already plugged in said
                // nothing at all — and since this observer starts whenever the process does,
                // "already plugged in" is a normal way for it to start. If a car is connected the
                // moment we begin watching, a drive is under way; the state machine ignores a start
                // it already knows about, so saying so costs nothing when it is not news.
                previous == null -> if (isConnected(type)) SignalKind.DRIVE_STARTED else null.also {
                    // Not an edge, but not nothing either: if the machine still believes the head
                    // unit is connected, the unplug happened while the process was dead. Left alone,
                    // that belief would overrule every end signal until the stale check cleared it.
                    scope.launch { coordinator.onLinkAbsent(SignalSource.ANDROID_AUTO) }
                }
                isConnected(type) && !isConnected(previous) -> SignalKind.DRIVE_STARTED
                !isConnected(type) && isConnected(previous) -> SignalKind.DRIVE_ENDED
                else -> null
            } ?: run {
                CurbsideLog.d("android auto reported connection type $type, not an edge we act on")
                return@Observer
            }

            CurbsideLog.i("android auto ${if (kind == SignalKind.DRIVE_STARTED) "connected" else "disconnected"}")

            // The application scope, never a caller's. An earlier version dispatched on the
            // activity's lifecycleScope, which is cancelled at onDestroy — after the first rotation
            // or fold the observer went on receiving edges and silently dropped every one of them.
            scope.launch {
                coordinator.onSignal(DriveSignal(SignalSource.ANDROID_AUTO, kind, Instant.now()))
            }
        }

        observer = newObserver
        connection = liveData
        // observeForever has to happen on the main thread; CarConnection's LiveData has no
        // background observation path.
        scope.launch(Dispatchers.Main) { liveData.observeForever(newObserver) }
    }

    suspend fun stop() {
        val current = observer ?: return
        val liveData = connection
        observer = null
        connection = null
        lastType = null
        withContext(Dispatchers.Main) { liveData?.removeObserver(current) }
    }

    /**
     * Projection means a phone plugged into a head unit — the case this app is built around.
     * Native means the app is running on Android Automotive OS, where the "car" is the phone and
     * there is no drive to detect this way, so it is not treated as a connection edge.
     */
    private fun isConnected(type: Int): Boolean = type == CarConnection.CONNECTION_TYPE_PROJECTION
}
