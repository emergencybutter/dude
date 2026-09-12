package nyc.curbside.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import nyc.curbside.R
import nyc.curbside.asp.CurbEvaluation
import nyc.curbside.asp.CurbStatus
import nyc.curbside.asp.NYC
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.ui.CurbsideActivity

object Notifications {

    const val CHANNEL_PARKED = "parked"
    const val CHANNEL_MOVE = "move"
    const val CHANNEL_SHARED = "shared"
    const val CHANNEL_CAPTURE = "capture"

    const val CAPTURE_NOTIFICATION_ID = 1
    const val PARKED_NOTIFICATION_ID = 2
    const val MOVE_NOTIFICATION_ID = 3
    const val SHARED_NOTIFICATION_ID = 4
    const val FAILED_NOTIFICATION_ID = 5

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val DAY_AND_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE h:mm a")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                // Silent: you were there when you parked, you do not need a chime about it.
                channel(context, CHANNEL_PARKED, R.string.channel_parked, NotificationManager.IMPORTANCE_LOW),
                // The one channel allowed to interrupt. Missing it costs sixty-five dollars.
                channel(context, CHANNEL_MOVE, R.string.channel_move, NotificationManager.IMPORTANCE_HIGH),
                channel(context, CHANNEL_SHARED, R.string.channel_shared, NotificationManager.IMPORTANCE_DEFAULT),
                // The transient "finding your car" notice the capture worker is required to show.
                channel(context, CHANNEL_CAPTURE, R.string.channel_capture, NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    private fun channel(context: Context, id: String, nameRes: Int, importance: Int) =
        NotificationChannel(id, context.getString(nameRes), importance)

    fun capturingNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(context.getString(R.string.notification_capturing))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun postParked(
        context: Context,
        event: ParkingEventEntity,
        evaluation: CurbEvaluation?,
        needsSideConfirmation: Boolean,
    ) {
        val where = event.address ?: context.getString(R.string.notification_parked_fallback)
        val detail = when {
            needsSideConfirmation -> context.getString(R.string.notification_confirm_side)
            evaluation == null -> null
            evaluation.status == CurbStatus.UNKNOWN -> context.getString(R.string.notification_no_rules)
            else -> evaluation.moveBy
                ?.let { context.getString(R.string.notification_move_by, DAY_AND_TIME.format(it)) }
                ?: evaluation.status.label
        }

        notify(
            context,
            PARKED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_PARKED)
                .setSmallIcon(R.drawable.ic_pin)
                .setContentTitle(context.getString(R.string.notification_parked_title))
                .setContentText(where)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(where, detail).joinToString("\n")))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The one that matters. Full-screen-adjacent urgency, a countdown the user can read from the
     * lock screen, and a direct action to walk to the car.
     */
    fun postMoveReminder(context: Context, event: ParkingEventEntity, moveBy: Instant) {
        val local = moveBy.atZone(NYC)
        val minutes = Duration.between(Instant.now(), moveBy).toMinutes().coerceAtLeast(0)

        notify(
            context,
            MOVE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_MOVE)
                .setSmallIcon(R.drawable.ic_pin)
                .setContentTitle(context.getString(R.string.notification_move_title, minutes))
                .setContentText(
                    context.getString(
                        R.string.notification_move_text,
                        event.address ?: context.getString(R.string.notification_parked_fallback),
                        TIME.format(local),
                    ),
                )
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(context))
                .addAction(
                    R.drawable.ic_pin,
                    context.getString(R.string.action_walk_to_car),
                    navigateTo(context, event),
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun postSharedByPartner(context: Context, partnerName: String, where: String) {
        notify(
            context,
            SHARED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_SHARED)
                .setSmallIcon(R.drawable.ic_pin)
                .setContentTitle(context.getString(R.string.notification_partner_parked, partnerName))
                .setContentText(where)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun postCaptureFailed(context: Context) {
        notify(
            context,
            FAILED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_PARKED)
                .setSmallIcon(R.drawable.ic_pin)
                .setContentTitle(context.getString(R.string.notification_failed_title))
                .setContentText(context.getString(R.string.notification_failed_text))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Posting without the runtime permission throws on API 33+, and there is nothing useful to do
     * about it from a background worker, so it is swallowed. The in-app UI shows the same
     * information regardless.
     */
    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, CurbsideActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun navigateTo(context: Context, event: ParkingEventEntity): PendingIntent {
        val uri = android.net.Uri.parse(
            "geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}(${
                android.net.Uri.encode(context.getString(R.string.your_car))
            })",
        )
        return PendingIntent.getActivity(
            context,
            1,
            Intent(Intent.ACTION_VIEW, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
