package nyc.curbside.share

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The plaintext of a shared parking event. Never leaves the device unencrypted. */
@Serializable
data class SharedParkingPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val parkedAtEpochMillis: Long,
    val address: String? = null,
    val note: String? = null,
    val curbLabel: String? = null,
    val moveByEpochMillis: Long? = null,
    val vehicleLabel: String? = null,
)

/**
 * End-to-end encryption for household sharing.
 *
 * ## Why bother
 *
 * Auto-sharing means a server would otherwise hold a running record of where a couple parks their
 * car, timestamped, for years. That is a genuinely sensitive dataset — it reveals where you live,
 * where you work, and where you were on any given evening — and there is no product reason for it
 * to be readable by anyone but the two people involved. So the server holds ciphertext and a
 * timestamp, and nothing else.
 *
 * ## The scheme
 *
 * A household has one symmetric key, generated on the device that creates it and transferred to the
 * partner's device by QR code — that is, over the air gap of one person holding a phone up to
 * another, which is the strongest channel available and needs no key agreement protocol.
 *
 * Each event is sealed with AES-256-GCM under a fresh random 96-bit nonce. GCM authenticates as
 * well as encrypts, so a tampered or truncated record fails to open rather than decoding to
 * plausible coordinates.
 *
 * ## What it does not protect
 *
 * The server still sees who shares with whom and when a car was parked, because it has to route the
 * push. Hiding traffic patterns is a different and much larger project; this hides the locations,
 * which is the part that matters here.
 *
 * The key is stored in Android Keystore-backed preferences on each device. Losing both devices
 * means losing the history, which is the correct trade: there is no server-side recovery because
 * there is no server-side key.
 */
@Singleton
class HouseholdCrypto @Inject constructor(
    private val keyStore: HouseholdKeyStore,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val random = SecureRandom()

    fun seal(payload: SharedParkingPayload): SealedEnvelope? {
        val key = keyStore.key() ?: return null
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(json.encodeToString(payload).toByteArray(Charsets.UTF_8))

        return SealedEnvelope(
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            version = VERSION,
        )
    }

    /** Returns null for a tampered, truncated, or wrong-key envelope. Never throws. */
    fun open(envelope: SealedEnvelope): SharedParkingPayload? {
        if (envelope.version != VERSION) return null
        val key = keyStore.key() ?: return null

        return runCatching {
            val nonce = Base64.decode(envelope.nonce, Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            }
            json.decodeFromString<SharedParkingPayload>(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrNull()
    }

    fun generateHouseholdKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION = 1
    }
}

@Serializable
data class SealedEnvelope(
    val nonce: String,
    val ciphertext: String,
    val version: Int,
)
