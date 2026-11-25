package dev.jarvis.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.ClaudeRecipeWriter
import dev.jarvis.agent.PhoneInfo
import dev.jarvis.claude.AnthropicClient
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.UsageMeter
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.McpClient
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.connectors.google.EmailFilter
import dev.jarvis.connectors.google.GmailApi
import dev.jarvis.connectors.google.GmailConnector
import dev.jarvis.connectors.google.GoogleAuthorizer
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.data.AuditLog
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.SnapshotStore
import dev.jarvis.data.db.JarvisDatabase
import dev.jarvis.memory.HistoryStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.security.AndroidLockoutNotifier
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.KeystoreSecretStore
import dev.jarvis.security.PinAuthenticator
import dev.jarvis.security.SessionManager
import dev.jarvis.service.HeadsetAudioRouter
import dev.jarvis.voice.HeadlessAccess
import dev.jarvis.voice.ModelDownloader
import dev.jarvis.voice.VoicePinEngine
import dev.jarvis.voice.PocketDetector
import dev.jarvis.voice.ProximityPocketDetector
import dev.jarvis.voice.SherpaWakeWordEngine
import dev.jarvis.voice.SystemSpeechWakeEngine
import dev.jarvis.voice.WakeWordEngine
import dev.jarvis.voice.AndroidStt
import dev.jarvis.voice.AndroidTts
import dev.jarvis.voice.Stt
import dev.jarvis.voice.Tts
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")
private val Context.usageStore: DataStore<Preferences> by preferencesDataStore(name = "usage")

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SecretsStore
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SettingsStore
/** Phase 1 usage log; only read once to import into Room (see UsageMeter.importLegacy). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class UsageStore
/** The agent used hands-free (pocket/locked): private tools need the phone in hand (HeadlessAccess). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class Headless
/** Process-lifetime scope for work that must not die with a screen (e.g. OAuth token exchange). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** No logging interceptor on purpose: requests carry API keys and connector tokens. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // max gap between SSE bytes; the API sends pings
        .build()

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton @SettingsStore
    fun settingsDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.settingsStore

    @Provides @Singleton @SecretsStore
    fun secretsDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.secretsStore

    @Provides @Singleton @UsageStore
    fun usageDataStore(@ApplicationContext c: Context): DataStore<Preferences> = c.usageStore

    @Provides @Singleton
    fun secretStore(@SecretsStore store: DataStore<Preferences>): SecretStore = KeystoreSecretStore(store)

    @Provides @Singleton
    fun settings(@SettingsStore store: DataStore<Preferences>) = SettingsRepository(store)

    @Provides @Singleton
    fun database(@ApplicationContext c: Context): JarvisDatabase =
        Room.databaseBuilder<JarvisDatabase>(c, JarvisDatabase.NAME)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Provides @Singleton
    fun usageMeter(db: JarvisDatabase) = UsageMeter(db.usage())

    @Provides @Singleton
    fun historyStore(db: JarvisDatabase) = HistoryStore(db.history())

    @Provides @Singleton
    fun snapshotStore(db: JarvisDatabase) = SnapshotStore(db.snapshots())

    @Provides @Singleton
    fun auditLog(db: JarvisDatabase) = AuditLog(db.audit())

    @Provides @Singleton
    fun llmClient(http: OkHttpClient, json: Json, secrets: SecretStore): LlmClient =
        AnthropicClient(http, json, apiKeyProvider = { secrets.get(SecretKey.ANTHROPIC_API_KEY) })

    @Provides @Singleton
    fun oauthClient(http: OkHttpClient, json: Json) = OAuthClient(http, json)

    @Provides @Singleton
    fun mcpClient(http: OkHttpClient, json: Json) = McpClient(http, json)

    @Provides @Singleton
    fun indMoney(secrets: SecretStore, oauth: OAuthClient, mcp: McpClient, audit: AuditLog) =
        IndMoneyConnector(secrets, oauth, mcp, audit = audit)

    @Provides @Singleton
    fun googleAuthorizer(@ApplicationContext c: Context) = GoogleAuthorizer(c)

    @Provides @Singleton
    fun gmail(secrets: SecretStore, google: GoogleAuthorizer, http: OkHttpClient, json: Json, settings: SettingsRepository, audit: AuditLog) =
        GmailConnector(
            secrets, google, GmailApi(http, json), json,
            excludedSenders = { EmailFilter.parseSenders(settings.settings.first().emailExcludedSenders) },
            audit = audit,
        )

    @Provides @Singleton
    fun connectors(indMoney: IndMoneyConnector, gmail: GmailConnector): Map<String, @JvmSuppressWildcards Connector> =
        mapOf(indMoney.id to indMoney, gmail.id to gmail)

    @Provides @Singleton
    fun sessionManager() = SessionManager()

    @Provides @Singleton
    fun stt(@ApplicationContext c: Context): Stt = AndroidStt(c)

    /** The on-device keyword model (beta). Also used by the Settings "Test now" check. */
    @Provides @Singleton
    fun wakeWordEngine(@ApplicationContext c: Context): WakeWordEngine = SherpaWakeWordEngine(c)

    /** "Hey Jarvis" via the phone's own speech recognizer (default). */
    @Provides @Singleton
    fun systemWakeEngine(stt: Stt, settings: SettingsRepository) =
        SystemSpeechWakeEngine(stt, { settings.current().preferOnDeviceStt }, { settings.current().learnedWakePhrases })

    @Provides @Singleton
    fun pocketDetector(@ApplicationContext c: Context): PocketDetector = ProximityPocketDetector(c)

    @Provides @Singleton
    fun modelDownloader(@ApplicationContext c: Context, http: OkHttpClient) = ModelDownloader(c, http)

    @Provides @Singleton
    fun transcriber(stt: Stt, voicePin: VoicePinEngine): dev.jarvis.voice.Transcriber = dev.jarvis.voice.FallbackTranscriber(stt, voicePin)

    @Provides @Singleton
    fun voiceCapture(settings: SettingsRepository): dev.jarvis.voice.VoiceCapture =
        dev.jarvis.voice.MicVoiceCapture { settings.current().wakeMicSource }

    @Provides @Singleton
    fun heyJarvisEngine(models: ModelDownloader) = dev.jarvis.voice.OpenWakeWordEngine({ models.dir })

    @Provides @Singleton
    fun voicePinEngine(models: ModelDownloader, secrets: SecretStore, settings: SettingsRepository, stt: Stt) =
        VoicePinEngine(
            models, secrets,
            micSource = { settings.current().wakeMicSource },
            requireVoiceMatch = { settings.current().voiceMatchForPin },
            onDeviceRecognizer = { pcm -> stt.transcribeOnDeviceOnly(pcm) },
        )

    @Provides @Singleton
    fun headsetRouter(@ApplicationContext c: Context) = HeadsetAudioRouter(c)

    @Provides @Singleton @Headless
    fun headlessAgent(
        llm: LlmClient,
        connectors: Map<String, @JvmSuppressWildcards Connector>,
        usage: UsageMeter,
        auth: AuthCoordinator,
        sessions: SessionManager,
        settings: SettingsRepository,
        device: AndroidBiometricAvailability,
        snapshots: SnapshotStore,
        audit: AuditLog,
        voicePin: VoicePinEngine,
        pin: dev.jarvis.security.PinAuthenticator,
        @ApplicationContext c: Context,
    ) = AgentLoop(
        llm, connectors, usage,
        access = HeadlessAccess(
            auth, sessions, { settings.current().security.sessions }, { device.deviceLocked() },
            spokenPinReady = { settings.current().speakReplies && voicePin.ready() && !pin.isLocked() },
        ),
        snapshots = snapshots, audit = audit, requestContext = { AgentLoop.localTimeLine() }, phoneInfo = { phoneInfo(c) },
        debugLog = shapeLog, recipes = ClaudeRecipeWriter(llm),
    )

    /** Battery level and charging state from the system (no permission needed). */
    private fun phoneInfo(c: Context): PhoneInfo {
        val bm = c.getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return PhoneInfo(pct, bm?.isCharging)
    }

    /** One TTS engine for the app: initialising it is slow. */
    @Provides @Singleton
    fun tts(@ApplicationContext c: Context): Tts = AndroidTts(c)

    @Provides @Singleton
    fun wakeCue(@ApplicationContext c: Context): dev.jarvis.voice.WakeCue = dev.jarvis.voice.AndroidWakeCue(c)

    @Provides @Singleton
    fun pinAuthenticator(secrets: SecretStore) = PinAuthenticator(secrets)

    @Provides @Singleton
    fun biometricAvailability(@ApplicationContext c: Context) = AndroidBiometricAvailability(c)

    @Provides @Singleton
    fun authCoordinator(
        @ApplicationContext c: Context,
        settings: SettingsRepository,
        sessions: SessionManager,
        pin: PinAuthenticator,
        biometric: AndroidBiometricAvailability,
        audit: AuditLog,
    ) = AuthCoordinator(
        settings = { settings.current().security },
        sessions = sessions, pin = pin, biometric = biometric, audit = audit,
        notifier = AndroidLockoutNotifier(c),
    )

    @Provides @Singleton
    fun agentLoop(
        llm: LlmClient,
        connectors: Map<String, @JvmSuppressWildcards Connector>,
        usage: UsageMeter,
        auth: AuthCoordinator,
        snapshots: SnapshotStore,
        audit: AuditLog,
        @ApplicationContext c: Context,
    ) = AgentLoop(
        llm, connectors, usage, access = auth, snapshots = snapshots, audit = audit,
        requestContext = { AgentLoop.localTimeLine() }, phoneInfo = { phoneInfo(c) }, debugLog = shapeLog,
        recipes = ClaudeRecipeWriter(llm),
    )

    /** Debug builds only: result shapes (field names, never values) for writing on-phone templates. */
    private val shapeLog: ((String) -> Unit)? =
        if (dev.jarvis.BuildConfig.DEBUG) { msg -> android.util.Log.d("JarvisShape", msg) } else null
}
