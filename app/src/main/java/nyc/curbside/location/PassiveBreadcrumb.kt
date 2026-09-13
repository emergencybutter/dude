package nyc.curbside.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nyc.curbside.data.Breadcrumb
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.di.ApplicationScope

/**
 * Records the last position seen during a drive, for free.
 *
 * [Priority.PRIORITY_PASSIVE] is the trick: it never asks the system to compute a location, it only
 * asks to be told about locations *other* apps have already caused to be computed. Its marginal
 * energy cost is the delivery itself.
 *
 * During an Android Auto drive there is almost always a navigation app requesting fixes at 1Hz, so
 * a passive subscription gets a continuous high-quality track for nothing. On a drive with no
 * navigation running it may receive nothing at all, which is fine — it is a fallback, not the
 * primary source.
 *
 * What it buys: when the car goes into an underground garage the live fix at parking time is either
 * absent or a 300m guess, and the last passive fix is the garage entrance. That is the difference
 * between "your car is somewhere in this neighbourhood" and "your car is in this garage".
 *
 * It is subscribed only between [start] and [stop], which bracket exactly one drive.
 */
@Singleton
class PassiveBreadcrumb @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, INTERVAL_MILLIS)
            // Nothing here forces a fix; these only cap how often a piggybacked one is delivered.
            .setMinUpdateIntervalMillis(INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()

        runCatching { client.requestLocationUpdates(request, pendingIntent()) }
    }

    fun stop() {
        runCatching { client.removeLocationUpdates(pendingIntent()) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BreadcrumbReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 4_203

        /** Thirty seconds between kept fixes is plenty to find a garage entrance again. */
        const val INTERVAL_MILLIS = 30_000L
        const val MIN_DISTANCE_METERS = 25f
    }
}

/**
 * Stores the newest passive fix, overwriting the last. Only one is ever kept: this is a fallback
 * position, not a trip log, and keeping a track of the user's driving would be both a privacy
 * liability and a thing nobody asked for.
 */
@AndroidEntryPoint
class BreadcrumbReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: CurbsideSettings

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val location = LocationResult.extractResult(intent)?.lastLocation ?: return
        val pending = goAsync()
        scope.launch {
            try {
                settings.writeBreadcrumb(
                    Breadcrumb(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
                        at = Instant.ofEpochMilli(location.time),
                    ),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
