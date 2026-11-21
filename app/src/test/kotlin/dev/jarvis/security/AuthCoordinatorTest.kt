package dev.jarvis.security

import dev.jarvis.FakeSecretStore
import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCoordinatorTest {
    private class RecordingAudit : AuditSink {
        val events = mutableListOf<AuditEvent>()
        override suspend fun record(event: AuditEvent) { events += event }
    }

    private val secrets = FakeSecretStore()
    private var now = 1_000_000L
    private val sessions = SessionManager { now }
    private val pin = PinAuthenticator(secrets, PinHasher(1_000), EmptyCoroutineContext)
    private var bio = BiometricStatus.AVAILABLE
    private val audit = RecordingAudit()
    private var lockNotifications = 0
    private var security = SecuritySettings()
    private val auth = AuthCoordinator({ security }, sessions, pin, { bio }, audit, { lockNotifications++ })

    private val netWorth = ToolRegistry.byName("networth_snapshot")!!
    private val holdings = ToolRegistry.byName("networth_holdings")!!
    private val lookup = ToolRegistry.byName("lookup_ind_keys")!!
    private val price = ToolRegistry.byName("get_indian_stocks_details")!!
    private val watchlist = ToolRegistry.byName("user_watchlist")!!

    private suspend fun setPin() = pin.setPin("2580", "2580", StrongAuthProof(AuthMethod.BIOMETRIC), deviceSecure = true)
    private val stage get() = auth.prompt.value?.stage

    private fun TestScope.authorizeAsync(vararg tools: dev.jarvis.agent.ToolSpec, preferred: AuthMethod? = null) =
        async { auth.authorize(tools.toList(), preferred) }.also { runCurrent() }

    @Test fun openToolsNeedNoPrompt() = runTest {
        val r = auth.authorize(listOf(price, lookup), null) as AccessResult.Granted
        assertNull(r.method); assertFalse(r.sensitive); assertFalse(r.screenOnly)
        assertNull(auth.prompt.value)
    }

    @Test fun pinPathGrantsSpokenAndShownWithASession() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, lookup)
        assertEquals(AuthPrompt.Stage.CHOOSE, stage)
        auth.choose(AuthMethod.PIN); runCurrent()
        assertEquals(AuthPrompt.Stage.PIN, stage)
        auth.submitPin("1357"); runCurrent()
        assertEquals("Wrong PIN. 2 tries left.", auth.prompt.value!!.message)
        auth.submitPin("2580"); runCurrent()
        val g = r.await() as AccessResult.Granted
        assertEquals(AuthMethod.PIN, g.method)
        assertFalse(g.screenOnly) // PIN = spoken and shown
        assertTrue(g.sensitive)
        assertNull(auth.prompt.value)
        assertTrue(sessions.isValid(AuthTier.SENSITIVE, security.sessions))
        assertTrue(audit.events.any { it.action == "pin" && it.outcome == "wrong" })
        assertTrue(audit.events.none { it.toString().contains("2580") || it.toString().contains("1357") })
    }

    @Test fun validSessionIsReusedThenExpires() = runTest {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.BIOMETRIC)
        val g = auth.authorize(listOf(netWorth), null) as AccessResult.Granted
        assertEquals(AuthMethod.BIOMETRIC, g.method)
        assertTrue(g.screenOnly)
        assertNull(auth.prompt.value)
        now += 2 * 60_000L + 1
        val r = authorizeAsync(netWorth)
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage) // expired: asks again (no PIN set, so fingerprint)
        auth.cancel(); runCurrent()
        assertEquals(DenyReason.CANCELLED, (r.await() as AccessResult.Denied).reason)
    }

    @Test fun noAnswerInElevenSecondsMeansFingerprintAndScreenOnly() = runTest {
        setPin()
        val r = authorizeAsync(netWorth)
        assertEquals(11, auth.prompt.value!!.defaultInSeconds)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(AuthPrompt.Stage.CHOOSE, stage) // still waiting at 10 s
        advanceTimeBy(1_001); runCurrent()
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage)
        auth.onBiometricResult(BiometricOutcome.Success(AuthMethod.BIOMETRIC)); runCurrent()
        val g = r.await() as AccessResult.Granted
        assertEquals(AuthMethod.BIOMETRIC, g.method)
        assertTrue(g.screenOnly)
    }

    @Test fun preEmptedChoiceSkipsTheQuestion() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.BIOMETRIC)
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage)
        auth.onBiometricResult(BiometricOutcome.Success(AuthMethod.DEVICE_CREDENTIAL)); runCurrent()
        val g = r.await() as AccessResult.Granted
        assertEquals(AuthMethod.DEVICE_CREDENTIAL, g.method)
        assertTrue(g.screenOnly)

        sessions.lock()
        val r2 = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        assertEquals(AuthPrompt.Stage.PIN, stage)
        auth.submitPin("2580"); runCurrent()
        assertEquals(AuthMethod.PIN, (r2.await() as AccessResult.Granted).method)
    }

    @Test fun threeWrongPinsLockNotifyAndSwitchToFingerprintWhichResetsLockout() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        repeat(3) { auth.submitPin("1357"); runCurrent() }
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage)
        assertEquals(AuthCoordinator.PIN_LOCKED_MESSAGE, auth.prompt.value!!.message)
        assertEquals(1, lockNotifications)
        assertTrue(pin.isLocked())
        assertTrue(audit.events.any { it.category == AuditCategory.AUTH && it.action == "lockout" })

        auth.onBiometricResult(BiometricOutcome.Success(AuthMethod.BIOMETRIC)); runCurrent()
        assertTrue(r.await() is AccessResult.Granted)
        assertFalse(pin.isLocked())
        assertTrue(audit.events.any { it.action == "lockout_reset" })
    }

    @Test fun malformedPinAsksAgainWithoutPenalty() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        auth.submitPin("12"); runCurrent()
        assertEquals(AuthPrompt.Stage.PIN, stage)
        assertTrue(auth.prompt.value!!.message!!.contains("isn't a PIN"))
        assertEquals(0, pin.failedAttempts())
        auth.submitPin("2580"); runCurrent()
        assertTrue(r.await() is AccessResult.Granted)
    }

    @Test fun lockedPinIsNotOfferedAndChoosingItIsIgnored() = runTest {
        setPin()
        repeat(3) { pin.verify("1357") }
        val r = authorizeAsync(netWorth)
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage) // straight to fingerprint
        assertTrue(auth.prompt.value!!.pinLocked)
        auth.onBiometricResult(BiometricOutcome.Cancelled); runCurrent()
        assertEquals(DenyReason.CANCELLED, (r.await() as AccessResult.Denied).reason)
        assertTrue(pin.isLocked()) // cancelling doesn't unlock
    }

    @Test fun nothingAvailableOrLockedOut() = runTest {
        bio = BiometricStatus.NONE
        assertEquals(DenyReason.NO_AUTH_AVAILABLE, (auth.authorize(listOf(netWorth), null) as AccessResult.Denied).reason)
        setPin(); repeat(3) { pin.verify("1357") }
        assertEquals(DenyReason.LOCKED_OUT, (auth.authorize(listOf(netWorth), null) as AccessResult.Denied).reason)
        assertNull(auth.prompt.value)
    }

    @Test fun pinOnlyPhoneGoesStraightToPin() = runTest {
        bio = BiometricStatus.NONE
        setPin()
        val r = authorizeAsync(netWorth)
        assertEquals(AuthPrompt.Stage.PIN, stage)
        auth.submitPin("2580"); runCurrent()
        assertTrue(r.await() is AccessResult.Granted)
    }

    @Test fun policyDenyBlocksWithoutPromptingAndPartialDenyFiltersTools() = runTest {
        security = SecuritySettings(rules = mapOf("networth_snapshot" to ToolRule.DENY))
        val d = auth.authorize(listOf(netWorth, lookup), null) as AccessResult.Denied
        assertEquals(DenyReason.POLICY, d.reason)
        assertEquals(listOf("networth_snapshot"), d.toolNames)
        assertNull(auth.prompt.value)

        sessions.grant(AuthTier.SENSITIVE, AuthMethod.PIN)
        security = SecuritySettings(rules = mapOf("networth_holdings" to ToolRule.DENY))
        val g = auth.authorize(listOf(netWorth, holdings, lookup), null) as AccessResult.Granted
        assertEquals(listOf("networth_snapshot", "lookup_ind_keys"), g.tools.map { it.name })
    }

    @Test fun lowerTierAskIsAnAllowDontAllowConfirmation() = runTest {
        sessions.grant(AuthTier.PERSONAL, AuthMethod.DEVICE_UNLOCK)
        security = SecuritySettings(level = PolicyLevel.STRICT)
        val r = authorizeAsync(watchlist)
        assertEquals(AuthPrompt.Stage.CONFIRM, stage)
        assertEquals(listOf(watchlist.description), auth.prompt.value!!.confirmLabels)
        auth.confirm(true); runCurrent()
        assertTrue(r.await() is AccessResult.Granted)

        val r2 = authorizeAsync(watchlist)
        auth.confirm(false); runCurrent()
        assertEquals(DenyReason.CANCELLED, (r2.await() as AccessResult.Denied).reason)
    }

    @Test fun personalToolsUseTheDeviceUnlockSession() = runTest {
        sessions.grant(AuthTier.PERSONAL, AuthMethod.DEVICE_UNLOCK)
        val g = auth.authorize(listOf(watchlist), null) as AccessResult.Granted
        assertEquals(AuthMethod.DEVICE_UNLOCK, g.method)
        assertFalse(g.sensitive)
    }

    @Test fun unansweredPromptTimesOut() = runTest {
        val r = authorizeAsync(netWorth)
        advanceTimeBy(AuthCoordinator.PROMPT_TIMEOUT_MS + 1); runCurrent()
        assertEquals(DenyReason.TIMEOUT, (r.await() as AccessResult.Denied).reason)
        assertNull(auth.prompt.value)
    }

    @Test fun strongAuthAcceptsOnlyFingerprintOrDeviceCredential() = runTest {
        setPin()
        val r = async { auth.strongAuth() }.also { runCurrent() }
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage)
        assertFalse(auth.prompt.value!!.pinAvailable) // the PIN can't stand in for strong auth
        auth.onBiometricResult(BiometricOutcome.UsePin); runCurrent()
        assertNull(r.await())
        assertFalse(sessions.isValid(AuthTier.SENSITIVE, security.sessions))

        val r2 = async { auth.strongAuth() }.also { runCurrent() }
        auth.onBiometricResult(BiometricOutcome.Success(AuthMethod.BIOMETRIC)); runCurrent()
        assertEquals(AuthMethod.BIOMETRIC, r2.await()!!.method)
    }

    @Test fun lockEndsSessionsAndIsAudited() = runTest {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.PIN)
        auth.lock("manual")
        assertFalse(sessions.isValid(AuthTier.PERSONAL, security.sessions))
        assertTrue(audit.events.any { it.action == "session_lock" && it.detail == "manual" })
    }

    @Test fun screenOffKeepsRevealedMessagesButAnExplicitLockHidesThem() = runTest {
        sessions.reveal(listOf(7L))
        auth.lock(AuthCoordinator.SCREEN_OFF)
        assertEquals(setOf(7L), sessions.revealed.value)
        auth.lock("manual")
        assertTrue(sessions.revealed.value.isEmpty())
    }

    @Test fun spokenPinWithTheOwnersVoiceUnlocks() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        auth.submitSpokenPin(SpokenPin("2580", voiceMatched = true)); runCurrent()
        val g = r.await() as AccessResult.Granted
        assertEquals(AuthMethod.PIN, g.method)
        assertFalse(g.screenOnly) // spoken PIN path: spoken and shown
        assertTrue(audit.events.any { it.action == "voice_pin" && it.outcome == "ok" })
    }

    @Test fun spokenPinWithAStrangersVoiceIsNeverCheckedAndDoesNotCount() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        auth.submitSpokenPin(SpokenPin("2580", voiceMatched = false)); runCurrent() // right digits, wrong voice
        assertEquals(AuthPrompt.Stage.BIOMETRIC, stage)
        assertEquals(AuthCoordinator.VOICE_MISMATCH_MESSAGE, auth.prompt.value!!.message)
        assertEquals(0, pin.failedAttempts())
        assertTrue(audit.events.any { it.outcome == "voice_mismatch" })
        auth.cancel(); runCurrent()
        assertTrue(r.await() is AccessResult.Denied)
    }

    @Test fun spokenWrongPinCountsAndUnclearAudioDoesNot() = runTest {
        setPin()
        val r = authorizeAsync(netWorth, preferred = AuthMethod.PIN)
        auth.submitSpokenPin(SpokenPin(null, voiceMatched = true)); runCurrent()
        assertEquals(0, pin.failedAttempts())
        assertTrue(auth.prompt.value!!.message!!.contains("Didn't catch"))
        auth.submitSpokenPin(SpokenPin("1357", voiceMatched = true)); runCurrent()
        assertEquals(1, pin.failedAttempts())
        auth.cancel(); runCurrent()
        r.await()
    }

    @Test fun unlockForReadingSavedMessages() = runTest {
        val r = async { auth.unlock() }.also { runCurrent() }
        auth.choose(AuthMethod.BIOMETRIC); runCurrent()
        auth.onBiometricResult(BiometricOutcome.Success(AuthMethod.BIOMETRIC)); runCurrent()
        assertTrue(r.await())
        assertTrue(auth.unlock()) // session now valid: no second prompt
    }
}
