package nyc.curbside.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nyc.curbside.CurbsideLog
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.di.ApplicationScope

/** Which of an event's two warnings an alarm carries. */
enum class ReminderKind { DAY_AHEAD, FINAL }

/**
 * Schedules the "move your car" alarms.
 *
 * Two per parking event, not one. The hour's notice is the one that saves the ticket, but it is
 * also the one that can only be acted on by dropping whatever you are doing. A day out there are
 * still options — move the car on the way home, swap it with the other car, ask the person who is
 * actually near it — and those options are the whole reason a household shares a parking spot at
 * all. Both alarms are cancelled together when the car moves.
 *
 * There is still no polling and no periodic work behind this: each reminder is a single wake-up at
 * a known moment, which is as close to free as a scheduled notification gets.
 */
@Singleton
class MoveReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
) {

    /**
     * Arms both warnings for [moveBy]: one [DAY_AHEAD] ahead of it, one
     * [CurbsideSettings.remindMinutesBefore] ahead of it, defaulting to an hour.
     *
     * A reminder that would already be in the past is skipped rather than fired immediately: being
     * told to move a car for a window that opened twenty minutes ago is noise, and the map already
     * shows the curb in red.
     */
    suspend fun schedule(eventId: String, moveBy: ZonedDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val deadline = moveBy.toInstant()
        val now = Instant.now()

        val finalAt = deadline.minus(Duration.ofMinutes(settings.readRemindMinutesBefore()))
        val dayAheadAt = deadline.minus(DAY_AHEAD)

        // Rescheduling — a corrected curb, a re-armed alarm after a reboot — must not leave the
        // previous pair armed alongside the new one. Extras do not distinguish PendingIntents, but
        // request codes do, so clearing both by hand is the only way to be sure.
        cancel(eventId)

        // The day's notice is worth having only while it is still well clear of the other one.
        // Warned twice inside an hour, the first one is not a warning, it is a duplicate.
        if (dayAheadAt.isAfter(now) && dayAheadAt.isBefore(finalAt.minus(MIN_SEPARATION))) {
            // Exactness buys nothing a day out, and an alarm the system is free to batch with
            // whatever else it is already waking up for is the cheaper of the two.
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                dayAheadAt.toEpochMilli(),
                intentFor(eventId, ReminderKind.DAY_AHEAD),
            )
            CurbsideLog.i("day-ahead reminder armed for ${Duration.between(now, dayAheadAt).toMinutes()}m from now")
        } else {
            CurbsideLog.d("no day-ahead reminder: the cleaning window is not far enough off")
        }

        if (finalAt.isBefore(now)) {
            CurbsideLog.i("no reminder armed: the lead time is already past")
            return
        }

        val intent = intentFor(eventId, ReminderKind.FINAL)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

        CurbsideLog.i(
            "final reminder armed for ${Duration.between(now, finalAt).toMinutes()}m from now" +
                if (canBeExact) "" else " (inexact: no exact-alarm permission)",
        )

        if (canBeExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalAt.toEpochMilli(), intent)
        } else {
            // Without the exact-alarm permission, land the window early rather than late: a warning
            // ten minutes early is useful, ten minutes late is a ticket.
            alarms.setWindow(
                AlarmManager.RTC_WAKEUP,
                finalAt.minus(INEXACT_LEAD).toEpochMilli(),
                INEXACT_WINDOW.toMillis(),
                intent,
            )
        }
    }

    fun cancel(eventId: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        ReminderKind.entries.forEach { alarms.cancel(intentFor(eventId, it)) }
    }

    private fun intentFor(eventId: String, kind: ReminderKind): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(eventId, kind),
        Intent(context, MoveReminderReceiver::class.java)
            .putExtra(MoveReminderReceiver.EXTRA_EVENT_ID, eventId)
            .putExtra(MoveReminderReceiver.EXTRA_KIND, kind.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Extras are not part of a PendingIntent's identity, so an event's two reminders have to differ
     * in their request code or the second would silently replace the first.
     */
    private fun requestCode(eventId: String, kind: ReminderKind) = 31 * eventId.hashCode() + kind.ordinal

    private companion object {
        val DAY_AHEAD: Duration = Duration.ofHours(24)
        val MIN_SEPARATION: Duration = Duration.ofHours(1)
        val INEXACT_LEAD: Duration = Duration.ofMinutes(10)
        val INEXACT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

@AndroidEntryPoint
class MoveReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var dao: ParkingEventDao

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        // An alarm armed by an older build carries no kind; it is the hour's warning, which is the
        // only one that version knew how to set.
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
            ?: ReminderKind.FINAL
        val pending = goAsync()
        scope.launch {
            try {
                val event = dao.byId(eventId) ?: return@launch
                // The user may have moved the car without the app noticing, or corrected the curb.
                if (event.clearedAt != null) return@launch
                val moveBy = Instant.ofEpochMilli(event.moveByEpochMillis ?: return@launch)
                CurbsideLog.i("$kind reminder firing, ${Duration.between(Instant.now(), moveBy).toMinutes()}m to go")
                when (kind) {
                    // A day-ahead alarm that arrives after the deadline has nothing left to warn
                    // about — Doze held it, or the curb was re-evaluated under it.
                    ReminderKind.DAY_AHEAD ->
                        if (moveBy.isAfter(Instant.now())) Notifications.postDayAheadReminder(context, event, moveBy)

                    ReminderKind.FINAL -> Notifications.postMoveReminder(context, event, moveBy)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_KIND = "kind"
    }
}
