package dev.jarvis

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.HiltAndroidApp
import dev.jarvis.claude.UsageMeter
import dev.jarvis.data.AuditLog
import dev.jarvis.di.AppScope
import dev.jarvis.di.UsageStore
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

    override fun onCreate() {
        super.onCreate()
        // Housekeeping off the main thread: one-time Phase 1 import, then retention pruning.
        appScope.launch {
            runCatching { usageMeter.importLegacy(legacyUsageStore, json) }
            runCatching { usageMeter.prune() }
            runCatching { auditLog.prune() }
        }
    }
}
