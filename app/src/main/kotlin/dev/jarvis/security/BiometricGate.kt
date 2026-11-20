package dev.jarvis.security

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Android glue for the fingerprint path, using androidx.biometric 1.4's
 * `registerForAuthenticationResult` (docs/decisions.md D15).
 *
 * - Android 11+: Class 3 biometric with the phone's PIN/pattern/password as fallback, or the
 *   device credential alone if no biometric is enrolled.
 * - Android 10: biometric + device credential can't be combined, so the prompt offers
 *   biometric with a "Use PIN" (JARVIS PIN) or Cancel button.
 */
class AndroidBiometricAvailability(private val context: Context) : BiometricAvailability {
    override fun status(): BiometricStatus {
        val bm = BiometricManager.from(context)
        return when {
            bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.CREDENTIAL_ONLY
            else -> BiometricStatus.NONE
        }
    }

    /** Whether the phone has a secure lock screen (PIN/pattern/password). */
    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun deviceLocked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
}

object BiometricGate {
    /** [message] (e.g. "PIN is locked, use fingerprint.") replaces the default subtitle so the user sees why. */
    fun request(status: BiometricStatus, pinFallback: Boolean, message: String? = null): AuthenticationRequest {
        val title = "Confirm it's you"
        val subtitle = message ?: SUBTITLE
        return if (status == BiometricStatus.CREDENTIAL_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AuthenticationRequest.credentialRequest(title) { setSubtitle(subtitle) }
        } else {
            val fallback: Biometric.Fallback = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Biometric.Fallback.DeviceCredential
                pinFallback -> Biometric.Fallback.CustomOption(USE_PIN)
                else -> Biometric.Fallback.CustomOption(CANCEL)
            }
            AuthenticationRequest.biometricRequest(title, fallback) {
                setSubtitle(subtitle)
                setMinStrength(Biometric.Strength.Class3()) // Class 3 = strong biometric; no CryptoObject needed here
            }
        }
    }

    fun outcome(result: AuthenticationResult): BiometricOutcome = when (result) {
        is AuthenticationResult.Success -> BiometricOutcome.Success(
            if (result.authType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) AuthMethod.DEVICE_CREDENTIAL
            else AuthMethod.BIOMETRIC
        )
        is AuthenticationResult.CustomFallbackSelected ->
            if (result.fallback.text == USE_PIN) BiometricOutcome.UsePin else BiometricOutcome.Cancelled
        is AuthenticationResult.Error -> when (result.errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED ->
                BiometricOutcome.Cancelled
            else -> BiometricOutcome.Error(result.errorCode)
        }
        else -> BiometricOutcome.Cancelled
    }

    private const val SUBTITLE = "JARVIS needs this to show sensitive information"
    const val USE_PIN = "Use PIN"
    private const val CANCEL = "Cancel"
}

/** Posts "PIN locked" (CLAUDE.md section 6: notify the user on lockout), if notifications are allowed. */
class AndroidLockoutNotifier(private val context: Context) : LockoutNotifier {
    override fun pinLocked() {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Security alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("JARVIS PIN locked")
            .setContentText("3 wrong PINs. Use your fingerprint to unlock it. If this wasn't you, check the audit log.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private companion object {
        const val CHANNEL = "security"
        const val NOTIFICATION_ID = 3001
    }
}
