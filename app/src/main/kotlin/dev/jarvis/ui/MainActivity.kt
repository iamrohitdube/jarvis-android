package dev.jarvis.ui

import android.os.Bundle
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
import dev.jarvis.ui.chat.ChatScreen
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
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize()) {
                    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
                    settings?.let { JarvisNavHost(it) } ?: Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun JarvisNavHost(initial: AppSettings) {
    val nav = rememberNavController()
    // Decide the start screen once; later settings changes must not reset navigation.
    val start = remember { if (initial.onboardingComplete) Routes.CHAT else Routes.ONBOARDING }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = {
                nav.navigate(Routes.CHAT) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenUsage = { nav.navigate(Routes.USAGE) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.USAGE) { UsageScreen(onBack = { nav.popBackStack() }) }
    }
}
