package nyc.curbside.share

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the household key at rest.
 *
 * The household key itself cannot live in Android Keystore directly — it has to be exportable, so
 * it can be shown as a QR code when pairing a second device. So it is kept wrapped: a
 * hardware-backed AES key that never leaves the secure element encrypts the household key, and the
 * wrapped blob sits in ordinary preferences. Extracting it needs both a copy of the file and the
 * device's secure hardware, which is materially better than a plaintext string in shared prefs and
 * a good deal simpler than a full keystore-only design that could not support pairing at all.
 */
@Singleton
class HouseholdKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Volatile
    private var cached: ByteArray? = null

    fun hasKey(): Boolean = prefs.contains(KEY_WRAPPED)

    fun key(): ByteArray? {
        cached?.let { return it }
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        val nonce = prefs.getString(KEY_NONCE, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey(),
                    GCMParameterSpec(TAG_BITS, Base64.decode(nonce, Base64.NO_WRAP)),
                )
            }
            cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }.getOrNull()?.also { cached = it }
    }

    fun store(householdKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.doFinal(householdKey)

        prefs.edit {
            putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            putString(KEY_NONCE, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        cached = householdKey
    }

    /** Leaving a household destroys the key, which makes every stored envelope permanently opaque. */
    fun clear() {
        prefs.edit { clear() }
        cached = null
    }

    /**
     * Base64 of the raw household key, for the pairing QR code.
     *
     * URL-safe, because the key travels as a query parameter of a `curbside://pair` URI and the
     * standard alphabet's `+` comes back out of the other phone's URI parser as a space.
     */
    fun exportForPairing(): String? =
        key()?.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    fun importFromPairing(encoded: String): Boolean = runCatching {
        // Accept either alphabet: the URL-safe one this app emits, and the standard one, so a code
        // from an older build still pairs.
        val decoded = Base64.decode(encoded.replace('-', '+').replace('_', '/'), Base64.NO_WRAP)
        require(decoded.size == KEY_BYTES) { "household key must be 256 bits" }
        store(decoded)
        true
    }.getOrDefault(false)

    private fun wrappingKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(WRAPPING_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    WRAPPING_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not requiring user authentication: the parking notification has
                    // to be readable from the lock screen while walking to the car.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_BYTES = 32
        const val PREFS = "household_key"
        const val KEY_WRAPPED = "wrapped"
        const val KEY_NONCE = "nonce"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_ALIAS = "curbside_household_wrapping"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
