package com.notifvault.app.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes and verifies the app-lock PIN.
 *
 * The PIN is never stored. What is stored is a PBKDF2-HMAC-SHA256 hash with a
 * per-install random salt, so a PIN of 1234 cannot be recovered from the
 * preferences file - it can only be brute-forced, and slowly.
 */
object PinHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    /** Creates a new "salt:hash" string for [pin]. */
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        return "${salt.toBase64()}:${hash.toBase64()}"
    }

    /** Constant-time-ish check of [pin] against a stored "salt:hash" string. */
    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = try { parts[0].fromBase64() } catch (e: Exception) { return false }
        val expected = try { parts[1].fromBase64() } catch (e: Exception) { return false }
        val actual = derive(pin, salt)
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
