package nyc.curbside.asp

import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyc.curbside.data.CurbsideSettings

/**
 * Keeps the alternate side suspension calendar fresh.
 *
 * The city suspends alternate side for about thirty days a year — major holidays, religious
 * observances, snow. Getting this wrong is the app's most embarrassing possible failure: telling
 * someone to go out at 7am on Yom Kippur to move a car that did not need moving.
 *
 * The dates arrive with the curb dataset rather than from the city directly: the 311 endpoint
 * needs a subscription key, and one compiled into the app would ship to every phone that installs
 * it. The calendar only runs about 45 days ahead however long a window is requested, so the bundle
 * has to be rebuilt to stay current — and everything outside the window it did cover is reported as
 * unknown rather than assumed, see [SuspensionCalendar.knows].
 */
@Singleton
class SuspensionRepository @Inject constructor(
    private val settings: CurbsideSettings,
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
     * Stores the calendar that arrived with the curb dataset.
     *
     * The city's own endpoint needs a subscription key, and a key compiled into an app is a key
     * published to anyone who unzips it — for an answer that is the same thirty-odd days a year
     * for the whole city. So the pipeline asks once on a build machine and the dates travel in the
     * dataset manifest, which the app already fetches on every check.
     *
     * @return true when something was stored.
     */
    suspend fun store(
        dates: List<String>,
        from: String,
        to: String,
        reasons: Map<String, String> = emptyMap(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (from.isBlank() || to.isBlank()) return@withContext false

        val stored = StoredCalendar(
            dates = dates.sorted(),
            from = from,
            to = to,
            fetchedAtEpochMillis = Instant.now().toEpochMilli(),
            reasons = reasons,
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
        val reasons: Map<String, String> = emptyMap(),
    ) {
        fun toCalendar() = SuspensionCalendar(
            suspendedDates = dates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet(),
            coverageStart = runCatching { LocalDate.parse(from) }.getOrNull(),
            coverageEnd = runCatching { LocalDate.parse(to) }.getOrNull(),
            fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
            reasons = reasons.mapNotNull { (date, why) ->
                runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to why }
            }.toMap(),
        )
    }
}
