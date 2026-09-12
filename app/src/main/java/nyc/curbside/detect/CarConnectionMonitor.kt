package nyc.curbside.detect

import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * alive. It cannot wake a killed app. So it is wired up as a *refinement* — whenever the app
 * happens to be running (which, if the user has our app on the head unit or has just used it, is
 * most of the drive) it produces a sharper edge than activity recognition would, and when the app
 * is not running the other two signals carry the load.
 *
 * @see DetectionRegistrar for the always-on half.
 */
@Singleton
class CarConnectionMonitor @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val coordinator: DriveCoordinator,
) {

    private var observer: Observer<Int>? = null
    private var lastType: Int? = null

    /**
     * Starts observing. Must be called on the main thread; [CarConnection]'s LiveData has no
     * background observation path.
     */
    fun start(scope: CoroutineScope) {
        if (observer != null) return

        val liveData = CarConnection(context).type
        val newObserver = Observer<Int> { type ->
            val previous = lastType
            lastType = type
            if (previous == null || previous == type) return@Observer

            val kind = when {
                isConnected(type) && !isConnected(previous) -> SignalKind.DRIVE_STARTED
                !isConnected(type) && isConnected(previous) -> SignalKind.DRIVE_ENDED
                else -> null
            } ?: return@Observer

            scope.launch {
                coordinator.onSignal(DriveSignal(SignalSource.ANDROID_AUTO, kind, Instant.now()))
            }
        }

        observer = newObserver
        scope.launch(Dispatchers.Main) { liveData.observeForever(newObserver) }
    }

    suspend fun stop() {
        val current = observer ?: return
        observer = null
        lastType = null
        withContext(Dispatchers.Main) { CarConnection(context).type.removeObserver(current) }
    }

    /**
     * Projection means a phone plugged into a head unit — the case this app is built around.
     * Native means the app is running on Android Automotive OS, where the "car" is the phone and
     * there is no drive to detect this way, so it is not treated as a connection edge.
     */
    private fun isConnected(type: Int): Boolean = type == CarConnection.CONNECTION_TYPE_PROJECTION
}
