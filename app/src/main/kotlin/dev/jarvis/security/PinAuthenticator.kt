package dev.jarvis.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Proof that the user just passed a strong check: fingerprint (Class 3 biometric) or the phone's own
 * PIN/pattern/password. Only [BiometricGate] results create one. It is required to reset the PIN lockout
 * and to set/change/remove the PIN. The PIN path (and, later, voice) can never produce one.
 */
class StrongAuthProof internal constructor(val method: AuthMethod) {
    init { require(method == AuthMethod.BIOMETRIC || method == AuthMethod.DEVICE_CREDENTIAL) }
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    /** Not 4-8 digits: ask again, no penalty. */
    data object Malformed : PinResult
    /** 3 wrong PINs: the PIN path is locked until a fingerprint/device-credential success. */
    data object Locked : PinResult
    data object NotSet : PinResult
}

sealed interface PinChangeResult {
    data object Ok : PinChangeResult
    data object Malformed : PinChangeResult
    data object TooWeak : PinChangeResult
    data object Mismatch : PinChangeResult
    /** Setting, changing or removing the PIN needs a fresh fingerprint/device credential. */
    data object NeedsStrongAuth : PinChangeResult
}

/**
 * PIN storage, verification and the persisted 3-strike lockout (CLAUDE.md section 6).
 * The PIN itself is never stored, logged or returned; only its salted hash lives in [SecretStore].
 */
class PinAuthenticator(
    private val secrets: SecretStore,
    private val hasher: PinHasher = PinHasher(),
    /** PBKDF2 is deliberately slow; keep it off the main thread. */
    private val hashContext: CoroutineContext = Dispatchers.Default,
) {
    private val mutex = Mutex()

    val isSet: Flow<Boolean> = secrets.presence().map { SecretKey.PIN_HASH in it }

    suspend fun hasPin(): Boolean = secrets.get(SecretKey.PIN_HASH) != null

    suspend fun failedAttempts(): Int = secrets.get(SecretKey.PIN_FAILED_ATTEMPTS)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    suspend fun isLocked(): Boolean = failedAttempts() >= MAX_ATTEMPTS

    suspend fun verify(raw: String): PinResult = mutex.withLock {
        val stored = secrets.get(SecretKey.PIN_HASH) ?: return@withLock PinResult.NotSet
        // Locked: don't even evaluate the PIN, so a correct guess can't be confirmed.
        if (isLocked()) return@withLock PinResult.Locked
        val pin = PinFormat.normalize(raw) ?: return@withLock PinResult.Malformed
        val ok = withContext(hashContext) { hasher.verify(pin, stored) }
        if (ok) {
            // Consecutive-failure counter; a correct PIN before lockout clears it.
            secrets.remove(SecretKey.PIN_FAILED_ATTEMPTS)
            PinResult.Ok
        } else {
            val failures = failedAttempts() + 1
            secrets.put(SecretKey.PIN_FAILED_ATTEMPTS, failures.toString())
            if (failures >= MAX_ATTEMPTS) PinResult.Locked else PinResult.Wrong(MAX_ATTEMPTS - failures)
        }
    }

    /**
     * Sets or changes the PIN. Requires [proof] whenever the phone has a secure lock screen, so someone
     * holding an unlocked phone can't set a PIN of their own and use it to read sensitive data.
     */
    suspend fun setPin(newPin: String, confirm: String, proof: StrongAuthProof?, deviceSecure: Boolean): PinChangeResult = mutex.withLock {
        if (deviceSecure && proof == null) return@withLock PinChangeResult.NeedsStrongAuth
        val pin = PinFormat.normalize(newPin) ?: return@withLock PinChangeResult.Malformed
        if (PinFormat.normalize(confirm) != pin) return@withLock PinChangeResult.Mismatch
        if (PinFormat.isTooWeak(pin)) return@withLock PinChangeResult.TooWeak
        val hash = withContext(hashContext) { hasher.hash(pin) }
        secrets.put(SecretKey.PIN_HASH, hash)
        secrets.remove(SecretKey.PIN_FAILED_ATTEMPTS)
        PinChangeResult.Ok
    }

    suspend fun removePin(proof: StrongAuthProof?, deviceSecure: Boolean): PinChangeResult = mutex.withLock {
        if (deviceSecure && proof == null) return@withLock PinChangeResult.NeedsStrongAuth
        secrets.remove(SecretKey.PIN_HASH, SecretKey.PIN_FAILED_ATTEMPTS)
        PinChangeResult.Ok
    }

    /** Clears the lockout. Only a strong-auth success can call this; there is no other reset path. */
    suspend fun resetLockout(@Suppress("UNUSED_PARAMETER") proof: StrongAuthProof) = mutex.withLock {
        secrets.remove(SecretKey.PIN_FAILED_ATTEMPTS)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
