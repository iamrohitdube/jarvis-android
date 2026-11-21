package dev.jarvis.service

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.wake.WakeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Makes JARVIS eligible as the phone's default digital assistant (docs/decisions.md D22).
 * When the user selects it, Android keeps this service running and binds it with foreground priority,
 * which lets JARVIS start its microphone listener after a reboot and show the wake screen from the
 * background, with no tap needed. It does no listening itself: [ListeningService] runs the wake word.
 */
@AndroidEntryPoint
class JarvisVoiceInteractionService : VoiceInteractionService() {
    @Inject lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReady() {
        super.onReady()
        // Documented exemption: a service started by the app that provides the VoiceInteractionService may use
        // the microphone even though no JARVIS screen is visible (e.g. right after boot).
        scope.launch {
            val micOk = ContextCompat.checkSelfPermission(this@JarvisVoiceInteractionService, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (settings.current().wakeWordEnabled && micOk) runCatching { ListeningService.start(this@JarvisVoiceInteractionService) }
        }
    }

    /** Assist gesture / headset button on the lock screen: open the wake screen over the keyguard. */
    override fun onLaunchVoiceAssistFromKeyguard() {
        runCatching { startActivity(WakeActivity.intent(this)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isDefaultAssistant(context: Context): Boolean =
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }
}

/** Creates a session when the user invokes the assistant (long-press, gesture, headset button). */
class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

/** An assistant invocation opens the same wake screen as "Hey Jarvis". */
class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching { startAssistantActivity(WakeActivity.intent(context)) }
        hide()
    }
}

/**
 * Android requires an assistant to name a recognition service, though the attribute has no effect from
 * Android 12 on. JARVIS uses the system recognizer, so this stub just declines.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
