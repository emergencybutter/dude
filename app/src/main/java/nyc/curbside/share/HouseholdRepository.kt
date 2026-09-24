package nyc.curbside.share

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
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
 *
 * ## When there is no Firebase project
 *
 * Sharing is the one feature with a dependency the rest of the app does not have, so a build with
 * no `google-services.json` has to stay usable rather than take the process down with it. Both
 * handles are [Lazy]: resolving either one initialises Firebase, which throws when no config was
 * supplied, so nothing here touches them until [isAvailable] confirms a default [FirebaseApp]
 * exists. Every entry point then returns the same nothing it returns when the network is down.
 */
@Singleton
class HouseholdRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreProvider: Lazy<FirebaseFirestore>,
    private val authProvider: Lazy<FirebaseAuth>,
    private val crypto: HouseholdCrypto,
    private val keys: HouseholdKeyStore,
    private val settings: CurbsideSettings,
) {

    private val random = SecureRandom()

    /** False in a build with no `google-services.json`: sharing is off, the rest of the app is not. */
    val isAvailable: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    private val firestore: FirebaseFirestore get() = firestoreProvider.get()

    private val auth: FirebaseAuth get() = authProvider.get()

    suspend fun signInIfNeeded(): String? {
        if (!isAvailable) return null
        auth.currentUser?.let { return it.uid }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    /** Creates a household and returns the payload to render as a QR code. */
    suspend fun createHousehold(displayName: String): PairingInvite? {
        if (!isAvailable) return null
        val uid = signInIfNeeded() ?: return null
        val householdId = firestore.collection(HOUSEHOLDS).document().id
        val key = crypto.generateHouseholdKey()

        return runCatching {
            firestore.collection(HOUSEHOLDS).document(householdId)
                .set(
                    mapOf(
                        "createdAt" to Instant.now().toEpochMilli(),
                        "members" to mapOf(uid to displayName),
                    ),
                ).await()

            // Only once the household exists on the server, so a write that fails does not leave
            // this device holding a key for a household that was never created — which would make
            // every envelope it has already stored unreadable.
            keys.store(key)
            settings.setHouseholdId(householdId)

            invite()
        }.getOrNull()
    }

    /**
     * Mints a fresh short-lived invite for the household this device is already in.
     *
     * Separate from [createHousehold] because showing a code a second time — pairing a third
     * device, or re-pairing after a phone is replaced — must not mint a new household and a new
     * key. That would orphan the first one and make every spot already shared unreadable.
     */
    suspend fun invite(): PairingInvite? {
        if (!isAvailable) return null
        val householdId = settings.readHouseholdId() ?: return null
        val uid = signInIfNeeded() ?: return null
        // The key travels in the QR code and nowhere else.
        val keyBase64 = keys.exportForPairing() ?: return null
        val code = inviteCode()
        val expiresAt = Instant.now().plusSeconds(INVITE_TTL_SECONDS).toEpochMilli()

        return runCatching {
            firestore.collection(INVITES).document(code)
                .set(
                    mapOf(
                        "householdId" to householdId,
                        "createdBy" to uid,
                        "expiresAt" to expiresAt,
                    ),
                ).await()

            PairingInvite(
                householdId = householdId,
                inviteCode = code,
                keyBase64 = keyBase64,
                expiresAtEpochMillis = expiresAt,
            )
        }.getOrNull()
    }

    /** Joins a household from a scanned invite. */
    suspend fun join(invite: PairingInvite, displayName: String): Boolean {
        if (!isAvailable) return false
        val uid = signInIfNeeded() ?: return false

        return runCatching {
            val inviteDoc = firestore.collection(INVITES).document(invite.inviteCode).get().await()
            val householdId = inviteDoc.getString("householdId") ?: return false
            val expiresAt = inviteDoc.getLong("expiresAt") ?: 0L
            if (householdId != invite.householdId) return false
            if (expiresAt < Instant.now().toEpochMilli()) return false

            // Only once the invite has checked out. Storing it first would leave a device that
            // scanned an expired code holding the key to a household it never joined.
            if (!keys.importFromPairing(invite.keyBase64)) return false

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
        // Forgetting the household locally has to work even when the server cannot be told, or a
        // device whose Firebase config went away could never leave.
        val uid = if (isAvailable) auth.currentUser?.uid else null
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
    fun observeIncoming(householdId: String): Flow<List<ReceivedParking>> =
        if (isAvailable) incoming(householdId) else flowOf(emptyList())

    private fun incoming(householdId: String): Flow<List<ReceivedParking>> = callbackFlow {
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
    /**
     * When the invite document stops being redeemable, so the screen can say how long the code is
     * good for. Local only — it is not in the QR, because the server's copy is what [join] is
     * checked against and a number carried in the code would just be a number the scanner chose.
     */
    val expiresAtEpochMillis: Long = 0L,
) {
    /**
     * The QR payload. A custom scheme rather than an https link on purpose: a link would be
     * followable, and this is a secret that should never be pasted into a browser or a chat.
     *
     * Nothing here is escaped, and nothing needs to be: the household id is a Firestore
     * auto-id, the invite code comes from [HouseholdRepository]'s alphabet, and the key is
     * URL-safe Base64. All three are query-safe as they stand.
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
