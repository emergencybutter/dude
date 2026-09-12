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
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.di.ApplicationScope

/**
 * Schedules the "move your car" alarm.
 *
 * One alarm per parking event, cancelled when the car moves. There is no polling and no periodic
 * work behind this — the reminder is a single wake-up at a known moment, which is as close to free
 * as a scheduled notification gets.
 */
@Singleton
class MoveReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
) {

    /**
     * Fires [CurbsideSettings.remindMinutesBefore] ahead of [moveBy], defaulting to an hour.
     *
     * A reminder that would already be in the past is skipped rather than fired immediately: being
     * told to move a car for a window that opened twenty minutes ago is noise, and the map already
     * shows the curb in red.
     */
    suspend fun schedule(eventId: String, moveBy: ZonedDateTime) {
        val lead = Duration.ofMinutes(settings.readRemindMinutesBefore())
        val fireAt = moveBy.toInstant().minus(lead)
        if (fireAt.isBefore(Instant.now())) return

        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = intentFor(eventId)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

        if (canBeExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt.toEpochMilli(), intent)
        } else {
            // Without the exact-alarm permission, land the window early rather than late: a warning
            // ten minutes early is useful, ten minutes late is a ticket.
            alarms.setWindow(
                AlarmManager.RTC_WAKEUP,
                fireAt.minus(INEXACT_LEAD).toEpochMilli(),
                INEXACT_WINDOW.toMillis(),
                intent,
            )
        }
    }

    fun cancel(eventId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intentFor(eventId))
    }

    private fun intentFor(eventId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        eventId.hashCode(),
        Intent(context, MoveReminderReceiver::class.java).putExtra(MoveReminderReceiver.EXTRA_EVENT_ID, eventId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        val INEXACT_LEAD: Duration = Duration.ofMinutes(10)
        val INEXACT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

@AndroidEntryPoint
class MoveReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var dao: ParkingEventDao

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val event = dao.byId(eventId) ?: return@launch
                // The user may have moved the car without the app noticing, or corrected the curb.
                if (event.clearedAt != null) return@launch
                val moveBy = event.moveByEpochMillis ?: return@launch
                Notifications.postMoveReminder(context, event, Instant.ofEpochMilli(moveBy))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
