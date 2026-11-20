package dev.jarvis.connectors.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) with the S256 method, plus random OAuth `state` values. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes -> 86-char URL-safe verifier (within the 43..128 limit). */
    fun newVerifier(): String = randomToken(64)

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newState(): String = randomToken(32)

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(b64::encodeToString)
}
