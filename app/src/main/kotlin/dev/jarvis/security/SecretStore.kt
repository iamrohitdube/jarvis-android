package dev.jarvis.security

import kotlinx.coroutines.flow.Flow

/** Names of secrets. Values are never logged, never sent to the model, never shown in full in the UI. */
enum class SecretKey(val id: String) {
    ANTHROPIC_API_KEY("anthropic_api_key"),
    INDMONEY_ACCESS_TOKEN("indmoney_access_token"),
    INDMONEY_REFRESH_TOKEN("indmoney_refresh_token"),
    INDMONEY_TOKEN_EXPIRES_AT("indmoney_token_expires_at"),
    INDMONEY_CLIENT_ID("indmoney_client_id"),
    INDMONEY_CLIENT_SECRET("indmoney_client_secret"),
    INDMONEY_PENDING_STATE("indmoney_pending_state"),
    INDMONEY_PENDING_VERIFIER("indmoney_pending_verifier"),
    /** Salted PBKDF2 hash of the user's PIN ("pbkdf2-sha256$iterations$salt$hash"). Never the PIN itself. */
    PIN_HASH("pin_hash"),
    /** Consecutive well-formed wrong PINs; 3 = PIN path locked. Encrypted so it can't be edited to unlock. */
    PIN_FAILED_ATTEMPTS("pin_failed_attempts"),
}

/** Encrypted key/value storage for secrets (CLAUDE.md: Storage interface, swappable). */
interface SecretStore {
    suspend fun get(key: SecretKey): String?
    suspend fun put(key: SecretKey, value: String)
    suspend fun remove(vararg keys: SecretKey)
    /** Emits which secrets are present (never their values). */
    fun presence(): Flow<Set<SecretKey>>
}
