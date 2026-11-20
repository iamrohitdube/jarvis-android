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
import dev.jarvis.claude.AnthropicClient
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.UsageMeter
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.McpProbe
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.data.SettingsRepository
import dev.jarvis.security.KeystoreSecretStore
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class UsageStore
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
    fun usageMeter(@UsageStore store: DataStore<Preferences>, json: Json) = UsageMeter(store, json)

    @Provides @Singleton
    fun llmClient(http: OkHttpClient, json: Json, secrets: SecretStore): LlmClient =
        AnthropicClient(http, json, apiKeyProvider = { secrets.get(SecretKey.ANTHROPIC_API_KEY) })

    @Provides @Singleton
    fun oauthClient(http: OkHttpClient, json: Json) = OAuthClient(http, json)

    @Provides @Singleton
    fun mcpProbe(http: OkHttpClient) = McpProbe(http)

    @Provides @Singleton
    fun indMoney(secrets: SecretStore, oauth: OAuthClient, probe: McpProbe) = IndMoneyConnector(secrets, oauth, probe)

    @Provides @Singleton
    fun connectors(indMoney: IndMoneyConnector): Map<String, @JvmSuppressWildcards Connector> =
        mapOf(indMoney.id to indMoney)

    @Provides @Singleton
    fun agentLoop(llm: LlmClient, connectors: Map<String, @JvmSuppressWildcards Connector>, usage: UsageMeter) =
        AgentLoop(llm, connectors, usage)
}
