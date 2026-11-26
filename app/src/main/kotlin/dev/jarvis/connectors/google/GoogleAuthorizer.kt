package dev.jarvis.connectors.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Short-lived Google access tokens for the phone's own Gmail calls (D29). Google Play services keeps the grant;
 * the app never stores a Google token or refresh token. Swappable so connectors can be tested without Play services.
 */
interface GoogleTokenSource {
    /** A valid access token, or null when Google needs the user (not linked, or the grant was revoked/expired). */
    suspend fun accessToken(): String?

    /** Drops a token Gmail rejected from Play services' cache, so the next [accessToken] gets a fresh one. */
    suspend fun clearToken(token: String)
}

/** One authoriser per Google service (Gmail, Drive), for the Settings sign-in buttons. */
class GoogleAuth(val gmail: GoogleAuthorizer, val drive: GoogleAuthorizer, val gmailSend: GoogleAuthorizer)

/** What a sign-in attempt from the UI needs to do next. */
sealed interface GoogleAuthorizeOutcome {
    data object Granted : GoogleAuthorizeOutcome
    /** Launch this with ActivityResultContracts.StartIntentSenderForResult, then call [GoogleAuthorizer.completeFromIntent]. */
    class NeedsUser(val pendingIntent: PendingIntent) : GoogleAuthorizeOutcome
    data class Failed(val message: String) : GoogleAuthorizeOutcome
}

/**
 * Google Identity Services Authorization API (play-services-auth), for one service's [scopes] (D29): Gmail and Drive
 * are authorised separately, so adding Drive never breaks a working Gmail. Each user creates their own Android
 * OAuth client (package + SHA-1); see docs/setup-keys.md section 3.
 */
class GoogleAuthorizer(private val context: Context, private val scopes: List<String>) : GoogleTokenSource {
    private val client get() = Identity.getAuthorizationClient(context)

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    override suspend fun accessToken(): String? {
        val result = client.authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    override suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /** Sign-in from Settings: either already granted, or a consent screen the UI must launch. */
    suspend fun authorize(): GoogleAuthorizeOutcome = try {
        val result = client.authorize(request()).await()
        val pending = result.pendingIntent
        when {
            !result.hasResolution() && result.accessToken != null -> GoogleAuthorizeOutcome.Granted
            pending != null -> GoogleAuthorizeOutcome.NeedsUser(pending)
            else -> GoogleAuthorizeOutcome.Failed("Google didn't return access")
        }
    } catch (e: Exception) {
        GoogleAuthorizeOutcome.Failed(e.message ?: "Google sign-in failed")
    }

    /** The consent screen's result. Returns true when access was granted. */
    fun completeFromIntent(data: Intent?): Boolean = runCatching {
        val result: AuthorizationResult = client.getAuthorizationResultFromIntent(data)
        result.accessToken != null
    }.getOrDefault(false)

    companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        /** Send only (D36): can't read, delete or change mail. Asked for separately so reading never depends on it. */
        const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
        const val DRIVE_METADATA_READONLY = "https://www.googleapis.com/auth/drive.metadata.readonly"
    }
}

/** Task -> coroutine without pulling in kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
