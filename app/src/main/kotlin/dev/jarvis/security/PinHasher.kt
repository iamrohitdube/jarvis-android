package dev.jarvis.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN shape rules. Only well-formed PINs count toward lockout (CLAUDE.md section 6). */
object PinFormat {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    /** Strips spaces and dashes; returns the digits if they form a well-formed PIN, otherwise null. */
    fun normalize(raw: String): String? {
        val digits = raw.filterNot { it == ' ' || it == '-' }
        return digits.takeIf { it.length in MIN_LENGTH..MAX_LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** Rejects PINs that are trivially guessed: all one digit, or a straight run like 1234 / 9876. */
    fun isTooWeak(pin: String): Boolean {
        if (pin.toSet().size == 1) return true
        val steps = pin.zipWithNext { a, b -> b - a }.toSet()
        return steps == setOf(1) || steps == setOf(-1)
    }
}

/**
 * Salted, slow PIN hashing: PBKDF2-HMAC-SHA256. OWASP's current recommendation is 600,000 iterations
 * (Password Storage Cheat Sheet, checked 2026-11-20). The iteration count is stored with the hash so it
 * can be raised later without breaking existing PINs. A 4-8 digit PIN is a small search space, so the
 * hash is also kept inside [SecretStore] (Keystore-encrypted), never in plain storage.
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val dk = derive(pin, salt, iterations)
        return listOf(SCHEME, iterations.toString(), b64(salt), b64(dk)).joinToString("$")
    }

    /** Constant-time comparison; malformed stored values never verify. */
    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val iter = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iter), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iter: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iter, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        const val SCHEME = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
