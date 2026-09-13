package nyc.curbside.data

import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyc.curbside.asp.CurbExtent
import nyc.curbside.asp.Regulation
import nyc.curbside.asp.RegulationKind
import nyc.curbside.asp.TimeWindow

/**
 * Wire and storage form of a parsed sign rule.
 *
 * Deliberately terse: this is repeated across roughly 150k segment rows, so weekdays are a bitmask
 * and times are minutes past midnight rather than formatted strings. The offline pipeline emits
 * exactly this shape, so the app never parses sign text on device.
 */
@Serializable
data class RegulationDto(
    @SerialName("k") val kind: String,
    /** Bit 0 is Monday through bit 6 Sunday, matching [DayOfWeek.getValue] minus one. */
    @SerialName("d") val days: Int,
    /** Minutes past midnight; null for an all-day rule. */
    @SerialName("s") val startMinute: Int? = null,
    @SerialName("e") val endMinute: Int? = null,
    @SerialName("r") val raw: String = "",
    @SerialName("i") val daysInferred: Boolean = false,
    /**
     * The stretch of curb this rule governs, in whole metres along the segment's polyline. Absent
     * on rules that cover the whole block, which is most of them — and on every rule in a bundle
     * built before the pipeline learned to read the arrows on signs.
     */
    @SerialName("a") val startMeters: Int? = null,
    @SerialName("b") val endMeters: Int? = null,
)

object RegulationCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun toDto(regulation: Regulation) = RegulationDto(
        kind = regulation.kind.name,
        days = regulation.days.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) },
        startMinute = regulation.window?.start?.let { it.hour * 60 + it.minute },
        endMinute = regulation.window?.end?.let { it.hour * 60 + it.minute },
        raw = regulation.raw,
        daysInferred = regulation.daysInferred,
        startMeters = regulation.extent?.startMeters?.let { Math.round(it).toInt() },
        endMeters = regulation.extent?.endMeters?.let { Math.round(it).toInt() },
    )

    fun fromDto(dto: RegulationDto): Regulation {
        val days = DayOfWeek.entries.filter { dto.days and (1 shl (it.value - 1)) != 0 }.toSet()
        val window = if (dto.startMinute != null && dto.endMinute != null) {
            // A rule that runs to the end of the day is stored as 23:59; rebuild it as such rather
            // than letting a 1439-minute end wrap to midnight and blow up TimeWindow's invariant.
            TimeWindow(minuteOfDay(dto.startMinute), minuteOfDay(dto.endMinute))
        } else {
            null
        }
        return Regulation(
            kind = runCatching { RegulationKind.valueOf(dto.kind) }.getOrDefault(RegulationKind.OTHER),
            days = days,
            window = window,
            raw = dto.raw,
            daysInferred = dto.daysInferred,
            extent = if (dto.startMeters != null && dto.endMeters != null) {
                CurbExtent(dto.startMeters.toDouble(), dto.endMeters.toDouble())
            } else {
                null
            },
        )
    }

    private fun minuteOfDay(minutes: Int): LocalTime =
        if (minutes >= 24 * 60 - 1) LocalTime.of(23, 59, 59) else LocalTime.of(minutes / 60, minutes % 60)

    fun encode(regulations: List<Regulation>): String =
        json.encodeToString(regulations.map(::toDto))

    /** Never throws: a corrupt row degrades that curb to "no sign data" rather than the whole map. */
    fun decode(raw: String): List<Regulation> =
        runCatching { json.decodeFromString<List<RegulationDto>>(raw).map(::fromDto) }
            .getOrDefault(emptyList())
}
