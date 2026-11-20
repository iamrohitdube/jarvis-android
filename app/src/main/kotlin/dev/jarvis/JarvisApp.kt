package dev.jarvis

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.HiltAndroidApp
import dev.jarvis.agent.AuthTier
import dev.jarvis.claude.UsageMeter
import dev.jarvis.data.AuditLog
import dev.jarvis.di.AppScope
import dev.jarvis.di.UsageStore
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltAndroidApp
class JarvisApp : Application() {
    @Inject @AppScope lateinit var appScope: CoroutineScope
    @Inject lateinit var usageMeter: UsageMeter
    @Inject lateinit var auditLog: AuditLog
    @Inject @UsageStore lateinit var legacyUsageStore: DataStore<Preferences>
    @Inject lateinit var json: Json
    @Inject lateinit var auth: AuthCoordinator
    @Inject lateinit var sessions: SessionManager

    /**
     * Screen off ends every auth session (CLAUDE.md section 6). Unlocking the phone counts as the
     * "recent unlock" Tier 1 (personal) data needs; it never grants Tier 2.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> appScope.launch { auth.lock(AuthCoordinator.SCREEN_OFF) }
                Intent.ACTION_USER_PRESENT -> sessions.grant(AuthTier.PERSONAL, AuthMethod.DEVICE_UNLOCK)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // System broadcasts only; not exported to other apps.
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Housekeeping off the main thread: one-time Phase 1 import, then retention pruning.
        appScope.launch {
            runCatching { usageMeter.importLegacy(legacyUsageStore, json) }
            runCatching { usageMeter.prune() }
            runCatching { auditLog.prune() }
        }
    }
}
