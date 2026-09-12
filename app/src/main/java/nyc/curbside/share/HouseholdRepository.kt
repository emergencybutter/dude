package nyc.curbside.share

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import nyc.curbside.data.CurbsideSettings

data class HouseholdMember(val uid: String, val displayName: String)

data class ReceivedParking(
    val eventId: String,
    val fromUid: String,
    val fromName: String,
    val payload: SharedParkingPayload,
)

/**
 * The household: two (or more) people who want to know where the car is.
 *
 * Firestore is doing three jobs here — durable storage, real-time fan-out to the partner's device,
 * and an offline write queue — and it does all three without a backend of our own. What it
 * deliberately is not doing is reading the data: every document body is a [SealedEnvelope], so the
 * server's copy is a timestamp, two user ids, and an opaque blob.
 *
 * ## Pairing
 *
 * One person creates a household, which mints a 256-bit key and a short-lived invite. The other
 * scans the QR code, which carries the household id and the key. The invite doc lets the joiner add
 * themselves to the member list once, and then expires. The key itself never passes through
 * Firestore — only through the camera.
 */
@Singleton
class HouseholdRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val crypto: HouseholdCrypto,
    private val keys: HouseholdKeyStore,
    private val settings: CurbsideSettings,
) {

    private val random = SecureRandom()

    suspend fun signInIfNeeded(): String? {
        auth.currentUser?.let { return it.uid }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    /** Creates a household and returns the payload to render as a QR code. */
    suspend fun createHousehold(displayName: String): PairingInvite? {
        val uid = signInIfNeeded() ?: return null
        val householdId = firestore.collection(HOUSEHOLDS).document().id
        val key = crypto.generateHouseholdKey()
        keys.store(key)

        firestore.collection(HOUSEHOLDS).document(householdId)
            .set(
                mapOf(
                    "createdAt" to Instant.now().toEpochMilli(),
                    "members" to mapOf(uid to displayName),
                ),
            ).await()

        settings.setHouseholdId(householdId)

        val code = inviteCode()
        firestore.collection(INVITES).document(code)
            .set(
                mapOf(
                    "householdId" to householdId,
                    "createdBy" to uid,
                    "expiresAt" to Instant.now().plusSeconds(INVITE_TTL_SECONDS).toEpochMilli(),
                ),
            ).await()

        return PairingInvite(
            householdId = householdId,
            inviteCode = code,
            // The key travels in the QR code and nowhere else.
            keyBase64 = keys.exportForPairing() ?: return null,
        )
    }

    /** Joins a household from a scanned invite. */
    suspend fun join(invite: PairingInvite, displayName: String): Boolean {
        val uid = signInIfNeeded() ?: return false
        if (!keys.importFromPairing(invite.keyBase64)) return false

        return runCatching {
            val inviteDoc = firestore.collection(INVITES).document(invite.inviteCode).get().await()
            val householdId = inviteDoc.getString("householdId") ?: return false
            val expiresAt = inviteDoc.getLong("expiresAt") ?: 0L
            if (householdId != invite.householdId) return false
            if (expiresAt < Instant.now().toEpochMilli()) return false

            firestore.collection(HOUSEHOLDS).document(householdId)
                .set(mapOf("members" to mapOf(uid to displayName)), SetOptions.merge())
                .await()

            settings.setHouseholdId(householdId)
            // Single-use: burning the invite stops a photographed QR code from being a standing
            // invitation to somebody's parking history.
            firestore.collection(INVITES).document(invite.inviteCode).delete().await()
            true
        }.getOrDefault(false)
    }

    suspend fun leave() {
        val householdId = settings.readHouseholdId() ?: return
        val uid = auth.currentUser?.uid
        if (uid != null) {
            runCatching {
                firestore.collection(HOUSEHOLDS).document(householdId)
                    .update(mapOf("members.$uid" to com.google.firebase.firestore.FieldValue.delete()))
                    .await()
            }
        }
        settings.setHouseholdId(null)
        keys.clear()
    }

    /**
     * Publishes one parking event.
     *
     * Firestore's offline queue means this succeeds immediately when there is no signal and syncs
     * when there is, which covers the underground-garage case without any retry logic of ours.
     */
    suspend fun publish(eventId: String, payload: SharedParkingPayload): Boolean {
        val householdId = settings.readHouseholdId() ?: return false
        val uid = signInIfNeeded() ?: return false
        val sealed = crypto.seal(payload) ?: return false

        return runCatching {
            firestore.collection(HOUSEHOLDS).document(householdId)
                .collection(PARKINGS).document(eventId)
                .set(
                    mapOf(
                        "from" to uid,
                        // Plaintext so the partner's device can order and expire records without
                        // decrypting every one; it reveals when, never where.
                        "at" to payload.parkedAtEpochMillis,
                        "envelope" to mapOf(
                            "nonce" to sealed.nonce,
                            "ciphertext" to sealed.ciphertext,
                            "version" to sealed.version,
                        ),
                    ),
                ).await()
            true
        }.getOrDefault(false)
    }

    /** Live stream of the partner's parkings, decrypted locally. */
    fun observeIncoming(householdId: String): Flow<List<ReceivedParking>> = callbackFlow {
        val myUid = auth.currentUser?.uid
        val registration = firestore.collection(HOUSEHOLDS).document(householdId)
            .collection(PARKINGS)
            .whereGreaterThan("at", Instant.now().minusSeconds(HISTORY_WINDOW_SECONDS).toEpochMilli())
            .addSnapshotListener { snapshot, _ ->
                val docs = snapshot?.documents ?: return@addSnapshotListener
                val received = docs.mapNotNull { doc ->
                    val from = doc.getString("from") ?: return@mapNotNull null
                    if (from == myUid) return@mapNotNull null

                    @Suppress("UNCHECKED_CAST")
                    val envelope = doc.get("envelope") as? Map<String, Any> ?: return@mapNotNull null
                    val sealed = SealedEnvelope(
                        nonce = envelope["nonce"] as? String ?: return@mapNotNull null,
                        ciphertext = envelope["ciphertext"] as? String ?: return@mapNotNull null,
                        version = (envelope["version"] as? Long)?.toInt() ?: return@mapNotNull null,
                    )
                    // An envelope we cannot open is skipped, not surfaced as an error: it means a
                    // key rotation or a record from before this device joined.
                    val payload = crypto.open(sealed) ?: return@mapNotNull null

                    ReceivedParking(doc.id, from, from, payload)
                }
                trySend(received)
            }

        awaitClose { registration.remove() }
    }

    private fun inviteCode(): String = (1..INVITE_CODE_LENGTH)
        .map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }
        .joinToString("")

    private companion object {
        const val HOUSEHOLDS = "households"
        const val PARKINGS = "parkings"
        const val INVITES = "invites"
        const val INVITE_TTL_SECONDS = 15L * 60L
        const val INVITE_CODE_LENGTH = 8

        /** No I, O, 0 or 1: the code may have to be read aloud. */
        const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Two weeks of history is plenty and keeps the listener's snapshot small. */
        const val HISTORY_WINDOW_SECONDS = 14L * 24 * 3600
    }
}

data class PairingInvite(
    val householdId: String,
    val inviteCode: String,
    val keyBase64: String,
) {
    /**
     * The QR payload. A custom scheme rather than an https link on purpose: a link would be
     * followable, and this is a secret that should never be pasted into a browser or a chat.
     */
    fun toQrPayload(): String = "curbside://pair?h=$householdId&c=$inviteCode&k=$keyBase64"

    companion object {
        fun parse(raw: String): PairingInvite? {
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return null
            if (uri.scheme != "curbside" || uri.host != "pair") return null
            return PairingInvite(
                householdId = uri.getQueryParameter("h") ?: return null,
                inviteCode = uri.getQueryParameter("c") ?: return null,
                keyBase64 = uri.getQueryParameter("k") ?: return null,
            )
        }
    }
}
