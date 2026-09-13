package nyc.curbside.asp

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyc.curbside.BuildConfig
import nyc.curbside.data.CurbsideSettings
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps the alternate side suspension calendar fresh.
 *
 * The city suspends alternate side for about thirty days a year — major holidays, religious
 * observances, snow. Getting this wrong is the app's most embarrassing possible failure: telling
 * someone to go out at 7am on Yom Kippur to move a car that did not need moving.
 *
 * The 311 API publishes roughly a quarter ahead, so a weekly refresh is ample and the cached copy
 * stays useful for months if the network is unavailable. Everything outside the fetched window is
 * reported as unknown rather than assumed — see [SuspensionCalendar.knows].
 */
@Singleton
class SuspensionRepository @Inject constructor(
    private val settings: CurbsideSettings,
    private val http: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: SuspensionCalendar? = null

    /** The calendar as last fetched. Never hits the network; the refresh worker does that. */
    suspend fun current(): SuspensionCalendar {
        cached?.let { return it }
        val stored = settings.readSuspensionsJson() ?: return SuspensionCalendar.EMPTY
        val parsed = runCatching { json.decodeFromString<StoredCalendar>(stored) }.getOrNull()
            ?: return SuspensionCalendar.EMPTY

        return parsed.toCalendar().also { cached = it }
    }

    /**
     * Fetches the next quarter and caches it.
     *
     * @return true when the calendar was refreshed. A false is not an error worth surfacing: the
     *   previous copy remains valid and the worker will try again.
     */
    suspend fun refresh(today: LocalDate = LocalDate.now(NYC)): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.NYC_311_API_KEY.isEmpty()) return@withContext false

        val from = today.minusDays(1)
        val to = today.plusDays(QUARTER_DAYS)
        val url = "$BASE_URL?fromdate=${from.format(API_DATE)}&todate=${to.format(API_DATE)}"

        val body = runCatching {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Ocp-Apim-Subscription-Key", BuildConfig.NYC_311_API_KEY)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return@withContext false

        val days = runCatching { json.decodeFromString<List<CalendarDay>>(body) }.getOrNull()
            ?: return@withContext false

        val suspended = days.mapNotNull { day ->
            val date = runCatching { LocalDate.parse(day.date, API_DATE) }.getOrNull() ?: return@mapNotNull null
            val parking = day.items.firstOrNull { it.type.equals(ALTERNATE_SIDE, ignoreCase = true) }
                ?: return@mapNotNull null
            // The API reports "IN EFFECT", "SUSPENDED", or "NOT IN EFFECT". Only the middle one is
            // a suspension of a rule that would otherwise apply.
            if (parking.status.contains(SUSPENDED, ignoreCase = true)) date else null
        }.toSet()

        val stored = StoredCalendar(
            dates = suspended.map(LocalDate::toString).sorted(),
            from = from.toString(),
            to = to.toString(),
            fetchedAtEpochMillis = Instant.now().toEpochMilli(),
        )
        settings.setSuspensionsJson(json.encodeToString(stored))
        cached = stored.toCalendar()
        true
    }

    @Serializable
    private data class StoredCalendar(
        val dates: List<String>,
        val from: String,
        val to: String,
        val fetchedAtEpochMillis: Long,
    ) {
        fun toCalendar() = SuspensionCalendar(
            suspendedDates = dates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet(),
            coverageStart = runCatching { LocalDate.parse(from) }.getOrNull(),
            coverageEnd = runCatching { LocalDate.parse(to) }.getOrNull(),
            fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
        )
    }

    @Serializable
    private data class CalendarDay(
        @SerialName("today_id") val date: String,
        @SerialName("items") val items: List<CalendarItem> = emptyList(),
    )

    @Serializable
    private data class CalendarItem(
        @SerialName("type") val type: String = "",
        @SerialName("status") val status: String = "",
        @SerialName("details") val details: String = "",
    )

    private companion object {
        const val BASE_URL = "https://api.nyc.gov/public/api/GetCalendar"
        const val ALTERNATE_SIDE = "Alternate Side Parking"
        const val SUSPENDED = "SUSPENDED"
        const val QUARTER_DAYS = 89L
        val API_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    }
}
