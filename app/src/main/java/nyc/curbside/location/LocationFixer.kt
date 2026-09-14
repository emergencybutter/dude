package nyc.curbside.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import nyc.curbside.asp.LatLng
import nyc.curbside.data.CurbsideSettings

/** How the coordinates for a parking event were arrived at, worst case last. */
enum class FixQuality {
    /** A fresh high-accuracy fix taken at the moment of parking. */
    GPS,

    /** The cached fused location, recent and accurate enough to use as-is. */
    RECENT_CACHED,

    /**
     * A fix too vague to say which block the car is on, kept only because it is better than
     * nothing. Never presented as the car's position: the UI calls it approximate and asks the
     * user to place the pin, and no curb rules are attributed to it.
     */
    COARSE,

    /**
     * The last position seen for free while driving. Used when the live fix fails or is hopeless,
     * which in practice means a parking garage. Off by however far the car travelled after the last
     * open-sky moment, so the UI labels it "approximate".
     */
    BREADCRUMB,

    /** The user dropped the pin themselves. */
    MANUAL,
}

data class Fix(
    val point: LatLng,
    val accuracyMeters: Float,
    val quality: FixQuality,
    val at: Instant,
)

/**
 * Produces one location per parking event, and only one.
 *
 * The app never subscribes to continuous location. Everything here is a single shot, taken at the
 * moment a drive is judged to have ended, with a cascade from cheapest to most expensive:
 *
 * 1. **The cached fused location.** Free — it is whatever the last app to ask for a location got.
 *    If Google Maps was navigating thirty seconds ago, this is already a good GPS fix and the
 *    cascade stops here, having spent no energy at all. This is the common case for an Android Auto
 *    drive.
 * 2. **One high-accuracy current-location request**, capped at [FIX_TIMEOUT]. This is the only
 *    time the app ever turns the GPS on, and it lasts seconds.
 * 3. **The passive breadcrumb.** If the fix times out or comes back hopeless, fall back to the last
 *    position seen for free during the drive — the garage entrance, typically.
 */
@Singleton
class LocationFixer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun capture(now: Instant = Instant.now()): Fix? {
        if (!hasLocationPermission()) return breadcrumbFix(now)

        cachedFix(now)?.let { return it }
        currentFix(now)?.let { return it }
        return breadcrumbFix(now)
    }

    /**
     * Whatever position the phone already had, for telling one car from another at the start of a
     * drive. Turns nothing on and waits for nothing — an absent or stale answer simply means the
     * app has to ask which car this was.
     */
    suspend fun lastKnown(now: Instant = Instant.now()): Fix? {
        if (!hasLocationPermission()) return null
        val last = runCatching { client.lastLocation.await() }.getOrNull() ?: return null

        if (Duration.between(Instant.ofEpochMilli(last.time), now) > ORIGIN_MAX_AGE) return null
        val accuracy = if (last.hasAccuracy()) last.accuracy else Float.MAX_VALUE
        // A fix this coarse names a neighbourhood, and both cars are probably in it.
        if (accuracy > ORIGIN_MAX_ACCURACY_METERS) return null

        return Fix(
            point = LatLng(last.latitude, last.longitude),
            accuracyMeters = accuracy,
            quality = FixQuality.RECENT_CACHED,
            at = Instant.ofEpochMilli(last.time),
        )
    }

    /** The free option: whatever fix the system already has, if it is fresh and tight enough. */
    private suspend fun cachedFix(now: Instant): Fix? {
        val last = runCatching { client.lastLocation.await() }.getOrNull() ?: return null
        val age = Duration.between(Instant.ofEpochMilli(last.time), now)

        if (age > CACHE_MAX_AGE) return null
        if (!last.hasAccuracy() || last.accuracy > CACHE_MAX_ACCURACY_METERS) return null

        return Fix(
            point = LatLng(last.latitude, last.longitude),
            accuracyMeters = last.accuracy,
            quality = FixQuality.RECENT_CACHED,
            at = Instant.ofEpochMilli(last.time),
        )
    }

    /**
     * The one expensive call in the app.
     *
     * Deliberately does not accept a stale fix. It used to allow one up to [CACHE_MAX_AGE] old, on
     * the reasoning that a recent navigation fix would come back instantly — but [cachedFix] has
     * already looked at exactly that location and rejected it, and letting it back in here handed
     * the caller the very fix the accuracy gate had just refused, restamped as [FixQuality.GPS].
     * A couple of seconds is enough to reuse a fix that landed while this was being set up.
     */
    private suspend fun currentFix(now: Instant): Fix? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setDurationMillis(FIX_TIMEOUT.toMillis())
            .setMaxUpdateAgeMillis(CURRENT_MAX_AGE.toMillis())
            .build()

        val location = withTimeoutOrNull(FIX_TIMEOUT.toMillis() + TIMEOUT_GRACE.toMillis()) {
            runCatching { client.getCurrentLocation(request, null).await() }.getOrNull()
        } ?: return null

        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        // A 200m fix in a garage is not a parking spot; the breadcrumb will be closer to the truth.
        if (accuracy > USABLE_ACCURACY_METERS) return null

        return Fix(
            point = LatLng(location.latitude, location.longitude),
            accuracyMeters = accuracy,
            // Reported accuracy is a 68% confidence radius, not a bound: a fix claiming forty
            // metres is routinely out by several times that. Anything past the trusted threshold
            // cannot name a block, so it is recorded as a guess rather than as the car.
            quality = if (accuracy <= TRUSTED_ACCURACY_METERS) FixQuality.GPS else FixQuality.COARSE,
            at = now,
        )
    }

    private suspend fun breadcrumbFix(now: Instant): Fix? {
        val crumb = settings.readBreadcrumb() ?: return null
        if (Duration.between(crumb.at, now) > BREADCRUMB_MAX_AGE) return null

        return Fix(
            point = LatLng(crumb.latitude, crumb.longitude),
            accuracyMeters = crumb.accuracyMeters,
            quality = FixQuality.BREADCRUMB,
            at = crumb.at,
        )
    }

    private companion object {
        val FIX_TIMEOUT: Duration = Duration.ofSeconds(20)
        val TIMEOUT_GRACE: Duration = Duration.ofSeconds(5)
        val CACHE_MAX_AGE: Duration = Duration.ofSeconds(30)

        /** Fresh means fresh. Long enough only to reuse a fix that arrived moments ago. */
        val CURRENT_MAX_AGE: Duration = Duration.ofSeconds(2)
        val BREADCRUMB_MAX_AGE: Duration = Duration.ofMinutes(15)

        /**
         * How stale the phone's cached position may be and still say where a drive began. Past
         * this it is where you were, not where the car was.
         */
        val ORIGIN_MAX_AGE: Duration = Duration.ofMinutes(15)
        const val ORIGIN_MAX_ACCURACY_METERS = 200f

        /** Tight enough to trust without spending anything to improve it. */
        const val CACHE_MAX_ACCURACY_METERS = 25f

        /**
         * The most error a fix may claim and still be called the car's position.
         *
         * A city block is around eighty metres and a street a dozen wide, so a fix reporting worse
         * than this cannot pick the block, never mind the side. It used to be a hundred metres,
         * which is a different street.
         */
        const val TRUSTED_ACCURACY_METERS = 30f

        /** Past this, even as a guess it is worthless and the breadcrumb is the better answer. */
        const val USABLE_ACCURACY_METERS = 60f
    }
}
