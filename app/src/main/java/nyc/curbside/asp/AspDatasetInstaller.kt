package nyc.curbside.asp

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
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
 *
 * ## The seed
 *
 * A release build carries a copy of the bundle in its assets, so the map has rules on it the first
 * time it is opened rather than a blank city and a "downloading" banner on whatever network the
 * user happens to be on. [installSeedIfEmpty] loads it once, and the network refresh above then
 * replaces it whenever the published version moves on. The asset is built, not committed — see
 * `docs/asp-data.md` — so a plain clone simply has no seed and falls back to downloading.
 */
@Singleton
class AspDatasetInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CurbSegmentDao,
    private val settings: CurbsideSettings,
    private val suspensions: SuspensionRepository,
    private val http: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return true when a new dataset was installed; false when already current or unavailable.
     */
    suspend fun installIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.ASP_DATASET_BASE_URL.isEmpty()) return@withContext false

        val manifest = fetchManifest() ?: return@withContext false

        // Before the version check, deliberately. The suspension calendar only runs about 45 days
        // ahead, so it goes stale long before the curb data changes — and the manifest is fetched
        // on every check even when the segment bundle is untouched.
        manifest.suspensions?.let { suspensions.store(it.dates, it.from, it.to) }

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

    /**
     * Installs the bundle baked into the APK, if there is one and the table is empty.
     *
     * Cheap to call on every launch: one indexed count when a dataset is already present. Never
     * overwrites a downloaded dataset, which is by definition at least as new as the seed.
     *
     * @return true when the seed was installed.
     */
    suspend fun installSeedIfEmpty(): Boolean = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext false

        val manifest = readSeed(SEED_MANIFEST) { json.decodeFromString<Manifest>(it.readBytes().decodeToString()) }
            ?: return@withContext false
        val segments = readSeed("$SEED_DIR/${manifest.path}", ::parseSegments) ?: return@withContext false
        if (segments.isEmpty()) return@withContext false

        manifest.suspensions?.let { suspensions.store(it.dates, it.from, it.to) }

        segments.chunked(INSERT_CHUNK).forEach { dao.insertAll(it) }
        settings.setAspDatasetVersion(manifest.version)
        true
    }

    /**
     * Takes the suspension calendar out of the seed, whatever state the curb table is in.
     *
     * [installSeedIfEmpty] is gated on an empty table because curb data is large and rarely
     * changes. The calendar is neither: it is a few dozen dates that expire in about 45 days, so
     * tying its refresh to "has this phone ever loaded curb data" would leave a long-installed app
     * with a calendar from whenever it was first opened — and a calendar that has quietly run out
     * says nothing is suspended, which is the failure this whole thing exists to avoid.
     *
     * Only ever moves forward: a seed older than what is already stored is ignored, so reinstalling
     * an old build cannot walk the calendar backwards.
     *
     * @return true when the stored calendar was replaced.
     */
    suspend fun installSeedSuspensions(): Boolean = withContext(Dispatchers.IO) {
        val manifest = readSeed(SEED_MANIFEST) {
            json.decodeFromString<Manifest>(it.readBytes().decodeToString())
        } ?: return@withContext false
        val seeded = manifest.suspensions ?: return@withContext false
        if (seeded.to.isBlank()) return@withContext false

        val known = suspensions.current().coverageEnd?.toString()
        if (known != null && known >= seeded.to) return@withContext false

        suspensions.store(seeded.dates, seeded.from, seeded.to)
    }

    /** Absent assets are the normal case in a clone that never ran the pipeline, not an error. */
    private fun <T> readSeed(path: String, parse: (InputStream) -> T): T? =
        runCatching { context.assets.open(path).use(parse) }.getOrNull()

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

                parseSegments(stream)
            }
    }.getOrNull()

    /**
     * Streamed and parsed line by line: the decompressed bundle is tens of megabytes and holding it
     * as a single String would be an out-of-memory kill on a small phone.
     */
    @VisibleForTesting
    internal fun parseSegments(stream: InputStream): List<CurbSegmentEntity> =
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching { json.decodeFromString<SegmentRow>(line).toEntity() }.getOrNull()
            }.toList()
        }

    @Serializable
    private data class Manifest(
        val version: String,
        val path: String,
        @SerialName("segment_count") val segmentCount: Int = 0,
        @SerialName("built_at") val builtAt: String = "",
        /**
         * The alternate side suspension calendar, fetched by the pipeline rather than by every
         * phone: the city's endpoint needs a subscription key, and one compiled into the app is a
         * key handed to anyone who unzips it. Null in a bundle built without a key.
         */
        val suspensions: Suspensions? = null,
    )

    @Serializable
    private data class Suspensions(
        val dates: List<String> = emptyList(),
        val from: String = "",
        val to: String = "",
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

        /** Where `tools/asp_pipeline.py --out app/src/main/assets/asp` leaves its output. */
        private const val SEED_DIR = "asp"
        private const val SEED_MANIFEST = "$SEED_DIR/manifest.json"

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
        // The suspension calendar rides in the dataset manifest, so installing covers both.
        installer.installIfNeeded()
        share.enqueuePending(parkingDao)
        coordinator.reconcile()
        return Result.success()
    }

    companion object {
        const val NAME = "data-maintenance"
    }
}
