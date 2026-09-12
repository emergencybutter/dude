package nyc.curbside.share

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.di.ApplicationScope
import nyc.curbside.notify.Notifications

/**
 * Wakes the app when a partner parks.
 *
 * The push carries no location — it cannot, because the server has no key. It carries only "there
 * is a new record, id X", and the device then reads the encrypted document and opens it locally.
 * That keeps the end-to-end property intact through the notification path, which is usually where
 * such schemes quietly leak.
 */
@AndroidEntryPoint
class CurbsideMessagingService : FirebaseMessagingService() {

    @Inject lateinit var firestore: FirebaseFirestore

    @Inject lateinit var crypto: HouseholdCrypto

    @Inject lateinit var settings: CurbsideSettings

    @Inject lateinit var dao: ParkingEventDao

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        val eventId = message.data["eventId"] ?: return
        val fromName = message.data["fromName"].orEmpty()

        scope.launch {
            val householdId = settings.readHouseholdId() ?: return@launch
            val doc = runCatching {
                firestore.collection("households").document(householdId)
                    .collection("parkings").document(eventId)
                    .get().await()
            }.getOrNull() ?: return@launch

            @Suppress("UNCHECKED_CAST")
            val envelope = doc.get("envelope") as? Map<String, Any> ?: return@launch
            val payload = crypto.open(
                SealedEnvelope(
                    nonce = envelope["nonce"] as? String ?: return@launch,
                    ciphertext = envelope["ciphertext"] as? String ?: return@launch,
                    version = (envelope["version"] as? Long)?.toInt() ?: return@launch,
                ),
            ) ?: return@launch

            dao.upsert(
                ParkingEventEntity(
                    id = eventId,
                    parkedAt = payload.parkedAtEpochMillis,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    accuracyMeters = payload.accuracyMeters,
                    fixQuality = "SHARED",
                    endedBy = "SHARED",
                    address = payload.address,
                    note = payload.note,
                    moveByEpochMillis = payload.moveByEpochMillis,
                    receivedFrom = doc.getString("from") ?: UUID.randomUUID().toString(),
                ),
            )

            Notifications.postSharedByPartner(
                this@CurbsideMessagingService,
                fromName,
                payload.address ?: payload.curbLabel.orEmpty(),
            )
        }
    }

    override fun onNewToken(token: String) {
        scope.launch {
            val householdId = settings.readHouseholdId() ?: return@launch
            runCatching {
                firestore.collection("households").document(householdId)
                    .collection("tokens").document(token)
                    .set(mapOf("updatedAt" to Instant.now().toEpochMilli()))
                    .await()
            }
        }
    }
}
