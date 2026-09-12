package nyc.curbside.asp

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nyc.curbside.BuildConfig
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.db.CurbSegmentDao
import nyc.curbside.data.db.CurbSegmentEntity
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Installs and refreshes the curb dataset.
 *
 * The app does not talk to NYC Open Data directly. Doing so would mean pulling a million sign rows
 * over a phone connection, parsing free-text sign copy on device, and joining it to street
 * centrelines — minutes of CPU and tens of megabytes, repeated on every phone. Instead an offline
 * pipeline (see `tools/asp_pipeline.py`) does that work once and publishes a compact, versioned
 * bundle: one gzipped JSON-lines file of pre-parsed curb segments, a few megabytes for the whole
 * city.
 *
 * The download happens once on first run, on unmetered network, and then monthly — the sign
 * inventory changes slowly. Suspensions, which change weekly, come from a separate and much smaller
 * feed; see [SuspensionRepository].
 */
@Singleton
class AspDatasetInstaller @Inject constructor(
    private val dao: CurbSegmentDao,
    private val settings: CurbsideSettings,
    private val http: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return true when a new dataset was installed; false when already current or unavailable.
     */
    suspend fun installIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.ASP_DATASET_BASE_URL.isEmpty()) return@withContext false

        val manifest = fetchManifest() ?: return@withContext false
        val installed = settings.readAspDatasetVersion()
        if (!force && installed == manifest.version && dao.count() > 0) return@withContext false

        val segments = downloadSegments(manifest) ?: return@withContext false
        if (segments.isEmpty()) return@withContext false

        // Replace wholesale inside one transaction: a half-updated curb table would show the user
        // stale rules on some blocks and new rules on others with no way to tell which.
        dao.clear()
        segments.chunked(INSERT_CHUNK).forEach { dao.insertAll(it) }
        settings.setAspDatasetVersion(manifest.version)
        true
    }

    private fun fetchManifest(): Manifest? = runCatching {
        http.newCall(Request.Builder().url("${BuildConfig.ASP_DATASET_BASE_URL}/manifest.json").build())
            .execute().use { response ->
                if (!response.isSuccessful) return null
                json.decodeFromString<Manifest>(response.body?.string() ?: return null)
            }
    }.getOrNull()

    private fun downloadSegments(manifest: Manifest): List<CurbSegmentEntity>? = runCatching {
        http.newCall(Request.Builder().url("${BuildConfig.ASP_DATASET_BASE_URL}/${manifest.path}").build())
            .execute().use { response ->
                if (!response.isSuccessful) return null
                val stream = response.body?.byteStream() ?: return null

                // Streamed and parsed line by line: the decompressed bundle is tens of megabytes
                // and holding it as a single String would be an out-of-memory kill on a small phone.
                GZIPInputStream(stream).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull null
                        runCatching { json.decodeFromString<SegmentRow>(line).toEntity() }.getOrNull()
                    }.toList()
                }
            }
    }.getOrNull()

    @Serializable
    private data class Manifest(
        val version: String,
        val path: String,
        @SerialName("segment_count") val segmentCount: Int = 0,
        @SerialName("built_at") val builtAt: String = "",
    )

    @Serializable
    private data class SegmentRow(
        val id: String,
        @SerialName("on") val onStreet: String,
        @SerialName("from") val fromStreet: String,
        @SerialName("to") val toStreet: String,
        val side: String,
        @SerialName("sign") val sideSign: Int,
        @SerialName("bbox") val bbox: List<Double>,
        @SerialName("geom") val geometry: String,
        @SerialName("rules") val rulesJson: String,
    ) {
        fun toEntity() = CurbSegmentEntity(
            id = id,
            onStreet = onStreet,
            fromStreet = fromStreet,
            toStreet = toStreet,
            side = side,
            sideSign = sideSign,
            minLat = bbox[0],
            minLon = bbox[1],
            maxLat = bbox[2],
            maxLon = bbox[3],
            geometry = geometry,
            rulesJson = rulesJson,
        )
    }

    companion object {
        private const val INSERT_CHUNK = 2_000

        /**
         * Schedules the periodic refresh. Monthly for the sign inventory, weekly for suspensions,
         * both on unmetered network only — neither is urgent enough to spend a user's data plan on.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DataMaintenanceWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DataMaintenanceWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}

/**
 * The app's only periodic work: refresh the suspension calendar, refresh the sign dataset if a new
 * one has been published, retry any share that never went out, and tidy up a drive that never
 * ended. Once a week, on wifi, on a charged battery.
 */
@HiltWorker
class DataMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val installer: AspDatasetInstaller,
    private val suspensions: SuspensionRepository,
    private val coordinator: nyc.curbside.detect.DriveCoordinator,
    private val share: nyc.curbside.share.ShareCoordinator,
    private val parkingDao: nyc.curbside.data.db.ParkingEventDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        suspensions.refresh()
        installer.installIfNeeded()
        share.enqueuePending(parkingDao)
        coordinator.reconcile()
        return Result.success()
    }

    companion object {
        const val NAME = "data-maintenance"
    }
}
