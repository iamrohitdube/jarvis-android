package dev.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.data.SettingsRepository
import dev.jarvis.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * After a reboot Android won't let JARVIS start its microphone service by itself (Android 14+), so if
 * "Hey Jarvis" was on, post a one-tap "resume listening" notification instead.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        scope.launch {
            try {
                // As default assistant, Android restarts JARVIS's assistant service, which resumes listening itself.
                if (settings.current().wakeWordEnabled && !JarvisVoiceInteractionService.isDefaultAssistant(context)) {
                    WakeNotifications.createChannels(context)
                    WakeNotifications.postResume(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
