package dev.jarvis.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JcaSecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with an AES-256-GCM key that lives in the Android Keystore (non-exportable),
 * persisted as Base64(iv || ciphertext) in a dedicated Preferences DataStore. The key alias name is
 * used as associated data so ciphertexts cannot be swapped between entries.
 *
 * (androidx.security:security-crypto / EncryptedSharedPreferences is deprecated, so this uses the
 * platform Keystore directly.)
 */
class KeystoreSecretStore(private val dataStore: DataStore<Preferences>) : SecretStore {

    override suspend fun get(key: SecretKey): String? = withContext(Dispatchers.IO) {
        val stored = dataStore.data.first()[pref(key)] ?: return@withContext null
        runCatching { decrypt(stored, key.id) }.getOrNull() // undecryptable (e.g. key wiped) -> treat as absent
    }

    override suspend fun put(key: SecretKey, value: String) = withContext(Dispatchers.IO) {
        val enc = encrypt(value, key.id)
        dataStore.edit { it[pref(key)] = enc }
        Unit
    }

    override suspend fun remove(vararg keys: SecretKey) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> keys.forEach { prefs.remove(pref(it)) } }
        Unit
    }

    override fun presence(): Flow<Set<SecretKey>> = dataStore.data.map { prefs ->
        SecretKey.entries.filter { prefs[pref(it)] != null }.toSet()
    }

    private fun pref(key: SecretKey) = stringPreferencesKey(key.id)

    private fun encrypt(plain: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        cipher.updateAAD(aad.toByteArray())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String, aad: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray())
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    @Synchronized
    private fun masterKey(): JcaSecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? JcaSecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
