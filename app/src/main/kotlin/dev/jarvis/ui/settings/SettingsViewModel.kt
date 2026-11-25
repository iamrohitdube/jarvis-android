package dev.jarvis.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmException
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.data.AppSettings
import dev.jarvis.service.WakeBus
import dev.jarvis.voice.Tts
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.SnapshotStore
import dev.jarvis.memory.HistoryStore
import dev.jarvis.ui.chat.CurrentConversation
import dev.jarvis.data.OnboardingStep
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.StepStatus
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import dev.jarvis.ui.SignInEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val llm: LlmClient,
    private val indMoney: IndMoneyConnector,
    private val gmail: dev.jarvis.connectors.google.GmailConnector,
    private val google: dev.jarvis.connectors.google.GoogleAuthorizer,
    private val signInEvents: SignInEvents,
    private val snapshots: SnapshotStore,
    private val history: HistoryStore,
    private val current: CurrentConversation,
    private val audit: AuditLog,
    private val tts: Tts,
    private val wakeBus: WakeBus,
    private val wakeEngine: dev.jarvis.voice.WakeWordEngine,
    private val recorder: dev.jarvis.voice.VoicePinEngine,
    private val stt: dev.jarvis.voice.Stt,
    private val cue: dev.jarvis.voice.WakeCue,
    private val heyJarvis: dev.jarvis.voice.OpenWakeWordEngine,
    private val models: dev.jarvis.voice.ModelDownloader,
) : ViewModel() {

    /** openWakeWord model state: installed, and download progress (null = not downloading). */
    val wakeModelReady = MutableStateFlow(models.installed(dev.jarvis.voice.OwwModelFiles.ALL))
    val wakeModelProgress = MutableStateFlow<Float?>(null)
    val wakeModelError = MutableStateFlow<String?>(null)

    val hasPersonalCheck = MutableStateFlow(heyJarvis.hasVerifier())
    val ownTrainPrompt = MutableStateFlow<String?>(null)
    val ownTrainResult = MutableStateFlow<String?>(null)

    /**
     * "Teach JARVIS your voice" for the wake model: records "Hey Jarvis" 6 times, then a sentence of ordinary speech
     * (with sound-alikes) and a few seconds of the room, and trains the personal check on the phone.
     */
    fun trainOwnVoice(context: android.content.Context) = viewModelScope.launch {
        if (ownTrainPrompt.value != null) return@launch
        if (!heyJarvis.isReady()) { ownTrainResult.value = "Download the wake model first."; return@launch }
        ownTrainResult.value = null
        wakeBus.micBusy.value = true
        val wake = ArrayList<FloatArray>(); val other = ArrayList<FloatArray>()
        try {
            kotlinx.coroutines.delay(600) // let the listener release the mic
            val wakeStyles = listOf(
                "normally", "normally", "normally", "a bit quicker", "a bit quieter",
                "with the phone at arm's length", "as if you're busy", "followed by a question (\"Hey Jarvis, what's the time?\")",
            )
            for ((i, style) in wakeStyles.withIndex()) {
                ownTrainPrompt.value = "${i + 1}/${wakeStyles.size}: say \"Hey Jarvis\" $style"
                wake += recorder.recordFor(if (i == wakeStyles.lastIndex) 3_500 else 2_200)
                ownTrainPrompt.value = "Got it"
                kotlinx.coroutines.delay(450)
            }
            val sentences = listOf(
                "Hey Travis, have you seen the service van? The harvest jars are on the table.",
                "I was just telling my friend about the match last night. It was a really close game.",
                "Hey, can you call Jason? Ask Javier if we're still on for dinner on Friday.",
                "Okay, so first we need groceries: rice, lentils, milk and bananas. Then we head home.",
            )
            for ((i, line) in sentences.withIndex()) {
                ownTrainPrompt.value = "Read aloud (${i + 1}/${sentences.size}):\n\"$line\""
                other += recorder.recordFor(7_500)
                kotlinx.coroutines.delay(300)
            }
            ownTrainPrompt.value = "Now just talk normally for 15 seconds: your day, what you're doing, anything (don't say \"Jarvis\")."
            other += recorder.recordFor(15_000)
            ownTrainPrompt.value = "Now stay quiet for 4 seconds…"
            other += recorder.recordFor(4_000)
            ownTrainPrompt.value = "Learning your voice…"
            val (r, peaks) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { heyJarvis.trainVerifier(wake, other) }
            hasPersonalCheck.value = true
            ownTrainResult.value = "Done. The standard model scored your \"Hey Jarvis\" %.2f–%.2f. ".format(peaks.minOrNull() ?: 0f, peaks.maxOrNull() ?: 0f) +
                "Your personal check recognises ${r.positivesAccepted}/${r.positives} of your samples and ignores " +
                "${(100 * r.negativesRejected / r.negatives.coerceAtLeast(1))}% of your other speech."
        } catch (e: Exception) {
            ownTrainResult.value = "Training failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            ownTrainPrompt.value = null
            wakeBus.micBusy.value = false
        }
        if (repo.current().wakeWordEnabled) runCatching { dev.jarvis.service.ListeningService.restart(context) }
    }

    fun removeOwnVoice(context: android.content.Context) = viewModelScope.launch {
        heyJarvis.removeVerifier()
        hasPersonalCheck.value = false
        ownTrainResult.value = "Personal voice check removed."
        if (repo.current().wakeWordEnabled) runCatching { dev.jarvis.service.ListeningService.restart(context) }
    }

    fun downloadWakeModel(context: android.content.Context) = viewModelScope.launch {
        if (wakeModelProgress.value != null) return@launch
        wakeModelError.value = null
        wakeModelProgress.value = 0f
        try {
            models.download(dev.jarvis.voice.OwwModelFiles.ALL).collect { p -> wakeModelProgress.value = p.done.toFloat() / p.total }
            wakeModelReady.value = models.installed(dev.jarvis.voice.OwwModelFiles.ALL)
            if (repo.current().wakeWordEnabled) runCatching { dev.jarvis.service.ListeningService.restart(context) }
        } catch (e: Exception) {
            wakeModelError.value = "Download failed: ${e.message ?: e.javaClass.simpleName}. Check the connection and try again."
        } finally {
            wakeModelProgress.value = null
        }
    }

    /** Training state: the current prompt (null = not training) and the result summary. */
    val trainingPrompt = MutableStateFlow<String?>(null)
    val trainingResult = MutableStateFlow<String?>(null)

    /**
     * "Teach JARVIS your voice": asks for "Hey Jarvis" x3 and "Jarvis" x2, records every guess the phone's
     * recognizer makes, and saves the phrasings it uses for the user's voice as extra wake phrases.
     */
    fun trainWakeWord(context: android.content.Context) = viewModelScope.launch {
        if (trainingPrompt.value != null) return@launch
        val prompts = listOf("Hey Jarvis", "Hey Jarvis", "Hey Jarvis", "Jarvis", "Jarvis")
        val attempts = mutableListOf<List<String>>()
        trainingResult.value = null
        wakeBus.micBusy.value = true
        try {
            kotlinx.coroutines.delay(500)
            val onDevice = repo.current().preferOnDeviceStt
            for ((i, p) in prompts.withIndex()) {
                trainingPrompt.value = "${i + 1}/${prompts.size}: say \"$p\""
                kotlinx.coroutines.delay(600)
                val result = stt.listen(onDevice, 5)
                    .firstOrNull { it is dev.jarvis.voice.SttEvent.Final || it is dev.jarvis.voice.SttEvent.Failed }
                if (result is dev.jarvis.voice.SttEvent.Final) attempts += listOf(result.text) + result.alternatives
                kotlinx.coroutines.delay(300)
            }
        } finally {
            trainingPrompt.value = null
            wakeBus.micBusy.value = false
        }
        if (attempts.isEmpty()) {
            trainingResult.value = "Didn't hear anything. Try again somewhere quieter."
            return@launch
        }
        val before = repo.current().learnedWakePhrases
        val learned = dev.jarvis.voice.WakeTraining.learn(attempts, before)
        repo.setLearnedWakePhrases(learned)
        if (repo.current().wakeWordEnabled) runCatching { dev.jarvis.service.ListeningService.restart(context) }
        val understood = dev.jarvis.voice.WakeTraining.understood(attempts)
        val added = learned.size - before.size
        trainingResult.value = "Your phone understood \"Jarvis\" $understood of ${attempts.size} times. " +
            (if (added > 0) "Learned $added new way${if (added == 1) "" else "s"} you say it." else "Nothing new to learn.")
    }

    fun removeLearnedPhrase(phrase: String) = viewModelScope.launch {
        repo.setLearnedWakePhrases(repo.current().learnedWakePhrases - phrase)
    }

    fun setWakeAck(v: dev.jarvis.voice.WakeAck) = viewModelScope.launch { repo.setWakeAck(v) }
    fun setWakeAckPhrase(v: String) = viewModelScope.launch { repo.setWakeAckPhrase(v) }

    /** Plays the chosen wake acknowledgement so the user can hear it. */
    fun previewWakeAck() = viewModelScope.launch {
        val s = repo.current()
        runCatching { cue.play(dev.jarvis.voice.AckPlan.of(s.wakeAck, s.wakeAckPhrase, preheard = false, speakReplies = true), tts) }
    }

    fun clearLearnedPhrases() = viewModelScope.launch { repo.setLearnedWakePhrases(emptyList()) }

    /** The last Test-now recording (raw), kept in memory only until the user saves it or leaves. */
    private var lastTestClip: FloatArray? = null
    val canSaveClip = MutableStateFlow(false)

    /**
     * Saves the last Test-now clip as a WAV in Downloads, only when the user taps "Save recording" (to share it
     * for tuning). It's just the user saying "Hey Jarvis"; nothing is saved or sent otherwise.
     */
    fun saveTestClip(context: android.content.Context) = viewModelScope.launch {
        val toSave: Map<String, FloatArray> =
            if (compareClips.isNotEmpty()) compareClips.mapKeys { it.key.name.lowercase() }
            else lastTestClip?.let { mapOf("test" to it) } ?: return@launch
        val stamp = System.currentTimeMillis() / 1000
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            toSave.all { (label, clip) ->
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "jarvis-wake-$label-$stamp.wav")
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(dev.jarvis.voice.Wav.encode16k(clip)) }
                }.isSuccess
            }
        }
        wakeTest.value = if (ok) "Saved ${toSave.size} recording(s) to Downloads (jarvis-wake-…-$stamp.wav)" else "Couldn't save the recordings."
    }

    /** Clips from the last "Compare microphones" run, by source (memory only, saved only on request). */
    private var compareClips: Map<dev.jarvis.voice.MicSource, FloatArray> = emptyMap()

    /**
     * Records "Hey Jarvis" once on each microphone path, checks which one the detector recognises (and how loud
     * it is), then switches the wake word and spoken PIN to the best one and restarts the listener.
     */
    fun compareMicrophones(context: android.content.Context) = viewModelScope.launch {
        if (wakeTesting.value) return@launch
        wakeTesting.value = true
        wakeBus.micBusy.value = true
        val results = mutableListOf<Triple<dev.jarvis.voice.MicSource, Float, String?>>()
        val clips = mutableMapOf<dev.jarvis.voice.MicSource, FloatArray>()
        try {
            val sources = dev.jarvis.voice.MicSource.candidates(context.getSystemService(android.media.AudioManager::class.java))
            val sensitivity = repo.current().wakeSensitivity
            sources.forEachIndexed { i, src ->
                wakeTest.value = "${i + 1}/${sources.size} (${src.label}): say \"Hey Jarvis\" now…"
                kotlinx.coroutines.delay(700)
                val raw = runCatching { recorder.recordFor(3_000, src) }.getOrNull() ?: return@forEachIndexed
                clips[src] = raw
                var peak = 0f
                var j = 0
                while (j < raw.size) { val e = minOf(j + 1600, raw.size); peak = maxOf(peak, dev.jarvis.voice.EnergyGate.rms(raw.copyOfRange(j, e))); j = e }
                val hit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { wakeEngine.spot(dev.jarvis.voice.AutoGain.normalize(raw).first, sensitivity) }.getOrNull()
                }
                results += Triple(src, peak, hit)
            }
        } finally {
            wakeBus.micBusy.value = false
            wakeTesting.value = false
        }
        compareClips = clips
        lastTestClip = clips.values.firstOrNull()
        canSaveClip.value = clips.isNotEmpty()
        val best = results.filter { it.third != null }.maxByOrNull { it.second }?.first
        val summary = results.joinToString("\n") { (s, peak, hit) ->
            "• ${s.label}: " + (if (hit != null) "recognised" else "not recognised") + " (loudness %.4f)".format(peak)
        }
        if (best != null) {
            repo.setWakeMicSource(best)
            if (repo.current().wakeWordEnabled) runCatching { dev.jarvis.service.ListeningService.restart(context) }
            wakeTest.value = "Using the ${best.label} from now on.\n$summary"
        } else {
            wakeTest.value = "None recognised you yet. Save the recordings and send them to me.\n$summary"
        }
    }

    /** Result of the "Test now" check (null = not run). */
    val wakeTest = MutableStateFlow<String?>(null)
    val wakeTesting = MutableStateFlow(false)

    /** Records one utterance and runs the detector on it, so we can tell voice vs. live-audio problems apart. */
    private var lastTestScore = 0f

    fun testWakeWord() = viewModelScope.launch {
        if (wakeTesting.value) return@launch
        wakeTesting.value = true
        wakeTest.value = "Say \"Hey Jarvis\" now (3 seconds)…"
        wakeBus.micBusy.value = true
        try {
            kotlinx.coroutines.delay(400) // let the live listener release the mic
            val raw = recorder.recordFor(3_000)
            compareClips = emptyMap()
            lastTestClip = raw
            canSaveClip.value = true
            var peak = 0f
            var i = 0
            while (i < raw.size) { val e = minOf(i + 1600, raw.size); peak = maxOf(peak, dev.jarvis.voice.EnergyGate.rms(raw.copyOfRange(i, e))); i = e }
            val (boosted, gain) = dev.jarvis.voice.AutoGain.normalize(raw)
            val sensitivity = repo.current().wakeSensitivity
            val useOww = repo.current().wakeEngine == dev.jarvis.voice.WakeEngineChoice.HEY_JARVIS
            val hit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    if (useOww) {
                        if (!heyJarvis.isReady()) "error: download the wake model first"
                        else {
                            val score = heyJarvis.bestScore(raw)
                            lastTestScore = score
                            heyJarvis.spot(raw, sensitivity)
                        }
                    } else wakeEngine.spot(boosted, sensitivity)
                }.getOrElse { "error: ${it.javaClass.simpleName}" }
            }
            if (useOww && hit == null) {
                wakeTest.value = "Not recognised (score %.2f; needs %.2f). Try again, a bit closer or louder."
                    .format(lastTestScore, dev.jarvis.voice.OpenWakeWordEngine.thresholdOf(sensitivity))
                return@launch
            }
            val loud = "mic loudness %.4f, boosted %.0fx".format(peak, gain)
            wakeTest.value = when {
                peak < 0.0003f -> "The mic gave almost silent audio ($loud). Another app may be using the mic."
                hit == null -> "Not recognised ($loud). Try again, or switch to Sensitive."
                hit.startsWith("error") -> "Detector failed: $hit"
                else -> "Recognised \"${hit.replace('_', ' ').lowercase()}\" ($loud). The detector works with your voice."
            }
        } finally {
            wakeBus.micBusy.value = false
            wakeTesting.value = false
        }
    }

    /** Result line for the Data section. */
    val dataMessage = MutableStateFlow<String?>(null)

    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasApiKey: StateFlow<Boolean> = secrets.presence().map { SecretKey.ANTHROPIC_API_KEY in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val indMoneyStatus: StateFlow<ConnectorStatus> = indMoney.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectorStatus.NotConnected)

    val gmailStatus: StateFlow<ConnectorStatus> = gmail.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectorStatus.NotConnected)
    /** Result line for the Gmail card. */
    val gmailMessage = MutableStateFlow<String?>(null)

    /** Result line for the key test ("Key works." / error). */
    val keyMessage = MutableStateFlow<String?>(null)
    /** Result line for connector actions; also receives browser sign-in outcomes. */
    val connectorMessage: StateFlow<String?> = signInEvents.last
    val busy = MutableStateFlow(false)

    fun saveAndTestKey(raw: String, onOk: () -> Unit = {}) {
        val key = raw.trim()
        if (key.isEmpty()) { keyMessage.value = "Paste a key first."; return }
        launchBusy {
            keyMessage.value = "Testing…"
            llm.testKey(key).fold(
                onSuccess = {
                    secrets.put(SecretKey.ANTHROPIC_API_KEY, key)
                    keyMessage.value = "Key works and is saved (encrypted)."
                    onOk()
                },
                onFailure = { e ->
                    keyMessage.value = when (e) {
                        is LlmException.Unauthorized -> "Anthropic rejected this key. Not saved."
                        is LlmException.Network -> "Couldn't reach Anthropic. Check your connection. Not saved."
                        else -> "Test failed: ${e.message}. Not saved."
                    }
                },
            )
        }
    }

    fun testSavedKey() = launchBusy {
        val key = secrets.get(SecretKey.ANTHROPIC_API_KEY)
        if (key == null) { keyMessage.value = "No key saved."; return@launchBusy }
        keyMessage.value = "Testing…"
        keyMessage.value = llm.testKey(key).fold({ "Key works." }, { "Test failed: ${it.message}" })
    }

    fun removeKey() = launchBusy {
        secrets.remove(SecretKey.ANTHROPIC_API_KEY)
        keyMessage.value = "Key removed."
    }

    fun startIndMoneySignIn(openUrl: (String) -> Unit) = launchBusy {
        runCatching { indMoney.startSignIn() }
            .onSuccess(openUrl)
            .onFailure { signInEvents.post("Couldn't start INDmoney sign-in: ${it.message}") }
    }

    fun testIndMoney(onOk: () -> Unit = {}) = launchBusy {
        signInEvents.post("Testing INDmoney…")
        when (val r = indMoney.test()) {
            ConnectorTestResult.Ok -> { signInEvents.post("INDmoney works."); onOk() }
            is ConnectorTestResult.Failed -> signInEvents.post(r.message)
        }
    }

    fun setIndMoneyManualToken(token: String) = launchBusy {
        if (token.isBlank()) return@launchBusy
        indMoney.setManualToken(token)
        signInEvents.post("Token saved (encrypted). Tap Test to check it.")
    }

    /** Google sign-in: Play services either grants at once or returns a consent screen for the UI to launch. */
    fun startGoogleSignIn(launchConsent: (android.app.PendingIntent) -> Unit, onOk: () -> Unit = {}) = launchBusy {
        gmailMessage.value = "Asking Google…"
        when (val r = google.authorize()) {
            dev.jarvis.connectors.google.GoogleAuthorizeOutcome.Granted -> { gmail.markLinked(); testGmailNow(onOk) }
            is dev.jarvis.connectors.google.GoogleAuthorizeOutcome.NeedsUser -> { gmailMessage.value = null; launchConsent(r.pendingIntent) }
            is dev.jarvis.connectors.google.GoogleAuthorizeOutcome.Failed ->
                gmailMessage.value = "Couldn't start Google sign-in: ${r.message}. Check the Android client in Google Cloud (docs/setup-keys.md 3.3)."
        }
    }

    /** The consent screen's result. */
    fun completeGoogleSignIn(data: android.content.Intent?, onOk: () -> Unit = {}) = launchBusy {
        if (google.completeFromIntent(data)) { gmail.markLinked(); testGmailNow(onOk) }
        else gmailMessage.value = "Google sign-in was cancelled or failed."
    }

    fun testGmail(onOk: () -> Unit = {}) = launchBusy { testGmailNow(onOk) }

    private suspend fun testGmailNow(onOk: () -> Unit) {
        gmailMessage.value = "Testing Gmail…"
        when (val r = gmail.test()) {
            ConnectorTestResult.Ok -> { gmailMessage.value = "Gmail works."; onOk() }
            is ConnectorTestResult.Failed -> gmailMessage.value = "Couldn't read Gmail: ${r.message}"
        }
    }

    fun signOutGmail() = launchBusy {
        gmail.signOut()
        gmailMessage.value = "Gmail disconnected on this phone. To remove access fully: myaccount.google.com/connections."
    }

    fun setEmailExcludedSenders(v: String) = viewModelScope.launch { repo.setEmailExcludedSenders(v) }

    fun signOutIndMoney() = launchBusy {
        indMoney.signOut()
        signInEvents.post("INDmoney signed out.")
    }

    fun setChatModel(v: String) = viewModelScope.launch { if (v.isNotBlank()) repo.setChatModel(v) }
    fun setQuickModel(v: String) = viewModelScope.launch { if (v.isNotBlank()) repo.setQuickModel(v) }
    fun setRouteSimple(v: Boolean) = viewModelScope.launch { repo.setRouteSimpleToQuick(v) }
    fun setServerFallback(v: Boolean) = viewModelScope.launch { repo.setServerFallback(v) }
    fun setBudget(text: String) = viewModelScope.launch { text.toDoubleOrNull()?.let { repo.setMonthlyBudget(it) } }
    fun setSnapshotMinutes(text: String) = viewModelScope.launch {
        val v = text.trim().toIntOrNull()
        if (v == null || v < 0) { dataMessage.value = "Enter minutes as a whole number (0 = always live)."; return@launch }
        repo.setSnapshotMaxAgeMinutes(v)
        dataMessage.value = if (v == 0) "Portfolio data will always be fetched live." else "Saved portfolio data will be reused for $v min."
    }

    fun clearSnapshots() = viewModelScope.launch {
        snapshots.clear()
        audit.record(AuditEvent(AuditCategory.DATA, "clear_saved_data", "ok"))
        dataMessage.value = "Saved portfolio data cleared."
    }

    fun deleteAllHistory() = viewModelScope.launch {
        history.deleteAll()
        current.open(null)
        audit.record(AuditEvent(AuditCategory.DATA, "delete_all_history", "ok"))
        dataMessage.value = "All chat history deleted."
    }

    fun setWakeWordEnabled(v: Boolean) = viewModelScope.launch { repo.setWakeWordEnabled(v) }
    fun setWakeEngine(v: dev.jarvis.voice.WakeEngineChoice, onSaved: () -> Unit) = viewModelScope.launch {
        repo.setWakeEngine(v); onSaved()
    }
    fun setWakeSensitivity(v: dev.jarvis.voice.WakeSensitivity, onSaved: () -> Unit) = viewModelScope.launch {
        repo.setWakeSensitivity(v); onSaved()
    }
    /** Live listener diagnostics (status, mic level, detections, last error). */
    val wake: WakeBus get() = wakeBus

    fun setSpeakReplies(v: Boolean) = viewModelScope.launch { repo.setSpeakReplies(v) }
    fun setPreferOnDeviceStt(v: Boolean) = viewModelScope.launch { repo.setPreferOnDeviceStt(v) }
    fun setSpeechRate(v: Float) = viewModelScope.launch { repo.setSpeechRate(v); tts.setRate(v) }

    /** Speaks a short line so the user can hear the voice and speed. */
    fun testVoice() {
        tts.stop()
        tts.speak("Hello, I'm JARVIS. This is how I'll sound.")
    }

    fun setStopAtBudget(v: Boolean) = viewModelScope.launch { repo.setStopAtBudget(v) }
    fun setStep(step: OnboardingStep, status: StepStatus) = viewModelScope.launch { repo.setStep(step, status) }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } finally { busy.value = false }
    }
}
