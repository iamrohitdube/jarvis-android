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
    fun googleAuth(@ApplicationContext c: Context) = dev.jarvis.connectors.google.GoogleAuth(
        gmail = GoogleAuthorizer(c, listOf(GoogleAuthorizer.GMAIL_READONLY)),
        drive = GoogleAuthorizer(c, listOf(GoogleAuthorizer.DRIVE_METADATA_READONLY)),
        gmailSend = GoogleAuthorizer(c, listOf(GoogleAuthorizer.GMAIL_SEND)),
    )

    @Provides @Singleton
    fun drive(secrets: SecretStore, google: dev.jarvis.connectors.google.GoogleAuth, http: OkHttpClient, json: Json, audit: AuditLog) =
        dev.jarvis.connectors.google.DriveConnector(secrets, google.drive, dev.jarvis.connectors.google.DriveApi(http, json), json, audit)

    @Provides @Singleton
    fun gmail(secrets: SecretStore, google: dev.jarvis.connectors.google.GoogleAuth, http: OkHttpClient, json: Json, settings: SettingsRepository, audit: AuditLog) =
        GmailConnector(
            secrets, google.gmail, GmailApi(http, json), json,
            excludedSenders = { EmailFilter.parseSenders(settings.settings.first().emailExcludedSenders) },
            audit = audit,
        )

    @Provides @Singleton
    fun calendar(@ApplicationContext c: Context, json: Json) = dev.jarvis.connectors.phone.CalendarConnector(
        dev.jarvis.connectors.phone.AndroidCalendarSource(c.contentResolver), json,
        granted = { hasPermissions(c, dev.jarvis.connectors.phone.CalendarConnector.PERMISSIONS) },
    )

    @Provides @Singleton
    fun callLog(@ApplicationContext c: Context, json: Json) = dev.jarvis.connectors.phone.CallLogConnector(
        dev.jarvis.connectors.phone.AndroidCallLogSource(c.contentResolver), json,
        granted = { hasPermissions(c, dev.jarvis.connectors.phone.CallLogConnector.PERMISSIONS) },
    )

    @Provides @Singleton
    fun transcriptDao(db: JarvisDatabase) = db.transcripts()

    @Provides @Singleton
    fun capturedMessageDao(db: JarvisDatabase) = db.capturedMessages()

    @Provides @Singleton
    fun capturedNotificationDao(db: JarvisDatabase) = db.capturedNotifications()

    @Provides @Singleton
    fun sentMessageDao(db: JarvisDatabase) = db.sentMessages()

    /** The recordings folder is the app's one persisted read grant (Storage Access Framework). */
    private fun recordingsFolder(c: Context): android.net.Uri? =
        c.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission }?.uri

    @Provides @Singleton
    fun recordingIndexer(@ApplicationContext c: Context, dao: dev.jarvis.data.db.TranscriptDao, voicePin: VoicePinEngine) =
        dev.jarvis.connectors.phone.RecordingIndexer(
            store = { recordingsFolder(c)?.let { dev.jarvis.connectors.phone.SafRecordingStore(c, it) } },
            dao = dao,
            transcriber = { uri ->
                voicePin.withTranscriber { transcribe ->
                    val text = StringBuilder()
                    val ms = dev.jarvis.voice.AudioDecoder.decode(c, android.net.Uri.parse(uri)) { chunk ->
                        text.append(transcribe(chunk).trim()).append(' ')
                    }
                    text.toString() to ms
                }
            },
        )

    @Provides @Singleton
    fun recordings(@ApplicationContext c: Context, dao: dev.jarvis.data.db.TranscriptDao, indexer: dev.jarvis.connectors.phone.RecordingIndexer, json: Json) =
        dev.jarvis.connectors.phone.RecordingsConnector(dao, indexer, json, granted = { recordingsFolder(c) != null })

    @Provides @Singleton
    fun contacts(@ApplicationContext c: Context) = dev.jarvis.messaging.Contacts(c.contentResolver)

    @Provides @Singleton
    fun sms(@ApplicationContext c: Context, contacts: dev.jarvis.messaging.Contacts, json: Json) =
        dev.jarvis.messaging.SmsConnector(c.contentResolver, contacts, json, granted = { hasPermissions(c, dev.jarvis.messaging.SmsConnector.PERMISSIONS) })

    @Provides @Singleton
    fun chats(@ApplicationContext c: Context, dao: dev.jarvis.data.db.CapturedMessageDao, json: Json) =
        dev.jarvis.messaging.ChatsConnector(dao, json, granted = { dev.jarvis.messaging.JarvisNotificationListener.enabled(c) })

    /** Other apps' notifications (D35): notification access AND the Settings switch. */
    @Provides @Singleton
    fun alerts(@ApplicationContext c: Context, dao: dev.jarvis.data.db.CapturedNotificationDao, json: Json, settings: SettingsRepository) =
        dev.jarvis.messaging.AlertsConnector(dao, json, granted = {
            dev.jarvis.messaging.JarvisNotificationListener.enabled(c) && kotlinx.coroutines.runBlocking { settings.current().readAppNotifications }
        })

    @Provides @Singleton
    fun sendCoordinator() = dev.jarvis.messaging.SendCoordinator()

    @Provides @Singleton
    fun messenger(
        @ApplicationContext c: Context, contacts: dev.jarvis.messaging.Contacts, captured: dev.jarvis.data.db.CapturedMessageDao,
        coordinator: dev.jarvis.messaging.SendCoordinator, sent: dev.jarvis.data.db.SentMessageDao, audit: AuditLog,
        settings: SettingsRepository, biometric: AndroidBiometricAvailability,
        google: dev.jarvis.connectors.google.GoogleAuth, http: OkHttpClient, json: Json,
    ) = dev.jarvis.messaging.Messenger(
        finder = dev.jarvis.messaging.AndroidRecipientFinder(
            contacts, captured,
            email = dev.jarvis.messaging.EmailRecipientFinder(
                { q -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { contacts.findEmails(q) } }, google.gmail, GmailApi(http, json),
            ),
        ),
        senders = mapOf(
            dev.jarvis.messaging.Channel.SMS to dev.jarvis.messaging.SmsSender(c),
            dev.jarvis.messaging.Channel.CHAT to dev.jarvis.messaging.ReplySender(c),
            dev.jarvis.messaging.Channel.EMAIL to dev.jarvis.messaging.EmailSender(google.gmailSend, GmailApi(http, json)),
        ),
        coordinator = coordinator, sent = sent, audit = audit,
        settings = {
            val s = settings.current()
            dev.jarvis.messaging.MessagingSettings(
                sendingEnabled = s.sendingEnabled,
                trusted = s.trustedContacts.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                maxPerHour = s.maxMessagesPerHour,
            )
        },
        deviceLocked = { biometric.deviceLocked() },
    )

    /** Voice calling on the chat/wake screen (D36): the Call dialog, and "call" said in a voice turn. */
    @Provides @Singleton
    fun caller(
        @ApplicationContext c: Context, contacts: dev.jarvis.messaging.Contacts, coordinator: dev.jarvis.messaging.SendCoordinator,
        sent: dev.jarvis.data.db.SentMessageDao, audit: AuditLog,
    ) = dev.jarvis.messaging.Caller(
        finder = dev.jarvis.messaging.ContactCallFinder(contacts),
        placer = dev.jarvis.messaging.AndroidCallPlacer(c),
        confirm = { t ->
            coordinator.confirm(dev.jarvis.messaging.Recipient(t.name, t.number, dev.jarvis.messaging.Channel.CALL, t.detail), "", voiceAllowed = true)
        },
        log = sent, audit = audit,
    )

    @Provides @Singleton
    fun consentCoordinator() = dev.jarvis.agent.ConsentCoordinator()

    /** Consent for the on-screen agent: the first send of each feature is previewed in a dialog. */
    @Provides @Singleton
    fun consentGate(settings: SettingsRepository, coordinator: dev.jarvis.agent.ConsentCoordinator): dev.jarvis.agent.ConsentGate =
        object : dev.jarvis.agent.ConsentGate {
            override suspend fun allowed(group: dev.jarvis.agent.ToolGroup) =
                group in dev.jarvis.agent.ConsentFeatures.decode(settings.current().claudeConsent)

            override suspend fun confirmFirstSend(group: dev.jarvis.agent.ToolGroup, preview: String): Boolean {
                val seen = dev.jarvis.agent.ConsentFeatures.decode(settings.current().consentPreviewed)
                if (group in seen) return true
                if (!coordinator.ask(group, preview)) return false
                settings.setConsentPreviewed(dev.jarvis.agent.ConsentFeatures.encode(seen + group))
                return true
            }
        }

    @Provides @Singleton
    fun connectors(
        indMoney: IndMoneyConnector, gmail: GmailConnector, drive: dev.jarvis.connectors.google.DriveConnector,
        calendar: dev.jarvis.connectors.phone.CalendarConnector, callLog: dev.jarvis.connectors.phone.CallLogConnector,
        recordings: dev.jarvis.connectors.phone.RecordingsConnector, sms: dev.jarvis.messaging.SmsConnector,
        chats: dev.jarvis.messaging.ChatsConnector, alerts: dev.jarvis.messaging.AlertsConnector,
    ): Map<String, @JvmSuppressWildcards Connector> =
        listOf<Connector>(indMoney, gmail, drive, calendar, callLog, recordings, sms, chats, alerts).associateBy { it.id }

    private fun hasPermissions(c: Context, permissions: List<String>) = permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

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
        tts: Tts,
        stt: Stt,
        contacts: dev.jarvis.messaging.Contacts,
        sent: dev.jarvis.data.db.SentMessageDao,
    ) = AgentLoop(
        llm, connectors, usage,
        access = HeadlessAccess(
            auth, sessions, { settings.current().security.sessions }, { device.deviceLocked() },
            spokenPinReady = { settings.current().speakReplies && voicePin.ready() && !pin.isLocked() },
            trustUnlocked = { settings.current().trustUnlockedPhone },
        ),
        snapshots = snapshots, audit = audit, requestContext = { AgentLoop.localTimeLine() }, phoneInfo = { phoneInfo(c) },
        debugLog = shapeLog, recipes = ClaudeRecipeWriter(llm),
        // Hands-free calling (D36): asked and confirmed by voice only ("call"), since there's no screen.
        placeCall = { who ->
            val spoken = dev.jarvis.voice.SpokenConfirm(tts, stt, { settings.current().preferOnDeviceStt })
            dev.jarvis.messaging.Caller(
                finder = dev.jarvis.messaging.ContactCallFinder(contacts),
                placer = dev.jarvis.messaging.AndroidCallPlacer(c),
                confirm = { t -> spoken.ask("Call ${t.name}, ${t.detail}? Say call.", dev.jarvis.messaging.CallPhrase::approves) },
                log = sent, audit = audit,
            ).handle(who)
        },
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
        deviceUnlocked = { !biometric.deviceLocked() },
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
        messenger: dev.jarvis.messaging.Messenger,
        consent: dev.jarvis.agent.ConsentGate,
        caller: dev.jarvis.messaging.Caller,
    ) = AgentLoop(
        llm, connectors, usage, access = auth, snapshots = snapshots, audit = audit,
        requestContext = { AgentLoop.localTimeLine() }, phoneInfo = { phoneInfo(c) }, debugLog = shapeLog,
        recipes = ClaudeRecipeWriter(llm),
        // Only the on-screen agent can send: the confirmation needs the screen (hands-free sending is off for now).
        sendMessage = { input -> messenger.handle(input) },
        // Consent sends need the preview dialog, so only the on-screen agent has them.
        consent = consent,
        placeCall = { who -> caller.handle(who) },
    )

    /** Debug builds only: result shapes (field names, never values) for writing on-phone templates. */
    private val shapeLog: ((String) -> Unit)? =
        if (dev.jarvis.BuildConfig.DEBUG) { msg -> android.util.Log.d("JarvisShape", msg) } else null
}
