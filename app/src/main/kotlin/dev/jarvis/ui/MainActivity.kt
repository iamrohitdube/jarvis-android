package dev.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.jarvis.service.ListeningService
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jarvis.agent.AuthTier
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import dev.jarvis.security.BiometricGate
import dev.jarvis.security.SessionManager
import dev.jarvis.ui.security.AuthHost
import dev.jarvis.ui.security.SecurityScreen
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.data.AppSettings
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.audit.AuditScreen
import dev.jarvis.ui.chat.ChatScreen
import dev.jarvis.ui.history.HistoryScreen
import dev.jarvis.ui.onboarding.OnboardingScreen
import dev.jarvis.ui.settings.SettingsScreen
import dev.jarvis.ui.theme.JarvisTheme
import dev.jarvis.ui.usage.UsageScreen
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val USAGE = "usage"
    const val HISTORY = "history"
    const val AUDIT = "audit"
    const val SECURITY = "security"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var auth: AuthCoordinator
    @Inject lateinit var sendCoordinator: dev.jarvis.messaging.SendCoordinator
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var biometric: AndroidBiometricAvailability

    /** Registered as a field initializer, as androidx.biometric requires. Results go to the coordinator. */
    private val biometricLauncher = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) = auth.onBiometricResult(BiometricGate.outcome(result))
            override fun onAuthAttemptFailed() = Unit // a non-matching finger; the system prompt lets the user retry
        }
    )
    private var launchedPromptId = -1L
    /** Keeps the launch screen up until settings have loaded, so the first frame is the real UI. */
    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !ready }
        splash.setOnExitAnimationListener { provider ->
            // The orb grows and fades into the app.
            provider.iconView.animate().scaleX(1.6f).scaleY(1.6f).alpha(0f).setDuration(320).start()
            provider.view.animate().alpha(0f).setDuration(380).withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Launch the system fingerprint prompt whenever the coordinator reaches that stage.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.prompt.collect { p ->
                    if (p?.stage == AuthPrompt.Stage.BIOMETRIC && p.id != launchedPromptId) {
                        launchedPromptId = p.id
                        biometricLauncher.launch(BiometricGate.request(biometric.status(), pinFallback = p.pinAvailable && !p.pinLocked, message = p.message))
                    }
                }
            }
        }
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize()) {
                    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
                    settings?.let {
                        LaunchedEffect(Unit) { ready = true }
                        JarvisNavHost(it)
                    } ?: Box(Modifier.fillMaxSize())
                    AuthHost(auth)
                    dev.jarvis.ui.security.SendHost(sendCoordinator)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The app is in front of an unlocked phone: that's the "recent unlock" Tier 1 needs (never Tier 2).
        if (!biometric.deviceLocked()) sessions.grant(AuthTier.PERSONAL, AuthMethod.DEVICE_UNLOCK)
        // The microphone service may only start while JARVIS is visible (Android 14+), so (re)start it here.
        lifecycleScope.launch {
            if (settingsRepository.current().wakeWordEnabled &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) runCatching { ListeningService.start(this@MainActivity) }
        }
    }
}

@Composable
private fun JarvisNavHost(initial: AppSettings) {
    val nav = rememberNavController()
    // Decide the start screen once; later settings changes must not reset navigation.
    val start = remember { if (initial.onboardingComplete) Routes.CHAT else Routes.ONBOARDING }
    NavHost(
        navController = nav,
        startDestination = start,
        // Gentle slide + fade between screens, like production apps.
        enterTransition = { slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(280)) },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = {
                nav.navigate(Routes.CHAT) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenUsage = { nav.navigate(Routes.USAGE) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenAudit = { nav.navigate(Routes.AUDIT) },
                onOpenSecurity = { nav.navigate(Routes.SECURITY) },
            )
        }
        composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }, onOpened = { nav.popBackStack() }) }
        composable(Routes.AUDIT) { AuditScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onOpenSecurity = { nav.navigate(Routes.SECURITY) }) }
        composable(Routes.SECURITY) { SecurityScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.USAGE) { UsageScreen(onBack = { nav.popBackStack() }) }
    }
}
