package dev.jarvis.security

import dev.jarvis.FakeSecretStore
import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.RiskLevel
import dev.jarvis.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class PinHasherTest {
    private val hasher = PinHasher(iterations = 1_000) // fast for tests; production uses 600k

    @Test fun verifiesOnlyTheRightPin() {
        val h = hasher.hash("2580")
        assertTrue(hasher.verify("2580", h))
        assertFalse(hasher.verify("2581", h))
        assertFalse(hasher.verify("", h))
    }

    @Test fun saltMakesEveryHashDifferentAndNoPlainPin() {
        val a = hasher.hash("2580"); val b = hasher.hash("2580")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("pbkdf2-sha256$1000$"))
        assertFalse(a.contains("2580"))
    }

    @Test fun tamperedOrMalformedStoredValuesNeverVerify() {
        val h = hasher.hash("2580")
        assertFalse(hasher.verify("2580", h.replace("pbkdf2-sha256", "md5")))
        assertFalse(hasher.verify("2580", "garbage"))
        assertFalse(hasher.verify("2580", "pbkdf2-sha256\$0\$AAAA\$BBBB"))
        assertFalse(hasher.verify("2580", h.dropLast(4) + "AAAA"))
    }

    @Test fun productionIterationsFollowOwasp() = assertEquals(600_000, PinHasher.DEFAULT_ITERATIONS)
}

class PinFormatTest {
    @Test fun normalizeAcceptsOnly4To8Digits() {
        assertEquals("1234", PinFormat.normalize("12 34"))
        assertEquals("12345678", PinFormat.normalize("1234-5678"))
        assertNull(PinFormat.normalize("123"))
        assertNull(PinFormat.normalize("123456789"))
        assertNull(PinFormat.normalize("12a4"))
        assertNull(PinFormat.normalize("one two three four"))
    }

    @Test fun weakPinsAreRejected() {
        listOf("0000", "1111", "1234", "4321", "98765", "123456").forEach { assertTrue(it, PinFormat.isTooWeak(it)) }
        listOf("2580", "1357", "9024", "1122").forEach { assertFalse(it, PinFormat.isTooWeak(it)) }
    }
}

class PinAuthenticatorTest {
    private val secrets = FakeSecretStore()
    private fun newAuth() = PinAuthenticator(secrets, PinHasher(1_000), EmptyCoroutineContext)
    private val pin = newAuth()
    private val proof = StrongAuthProof(AuthMethod.BIOMETRIC)

    @Test fun notSetUntilConfigured() = runBlocking {
        assertEquals(PinResult.NotSet, pin.verify("2580"))
        assertFalse(pin.hasPin())
    }

    @Test fun settingAPinNeedsStrongAuthOnASecurePhone() = runBlocking {
        assertEquals(PinChangeResult.NeedsStrongAuth, pin.setPin("2580", "2580", null, deviceSecure = true))
        assertEquals(PinChangeResult.Ok, pin.setPin("2580", "2580", null, deviceSecure = false))
        assertEquals(PinChangeResult.Ok, pin.setPin("1357", "1357", proof, deviceSecure = true))
        assertEquals(PinResult.Ok, pin.verify("1357"))
    }

    @Test fun setPinValidates() = runBlocking {
        assertEquals(PinChangeResult.Malformed, pin.setPin("12", "12", proof, true))
        assertEquals(PinChangeResult.Mismatch, pin.setPin("2580", "2581", proof, true))
        assertEquals(PinChangeResult.TooWeak, pin.setPin("1111", "1111", proof, true))
        assertFalse(pin.hasPin())
    }

    @Test fun onlyTheHashIsStored() = runBlocking {
        pin.setPin("2580", "2580", proof, true)
        val stored = secrets.values.value[SecretKey.PIN_HASH]!!
        assertFalse(stored.contains("2580"))
        assertTrue(stored.startsWith(PinHasher.SCHEME))
    }

    @Test fun threeWrongPinsLockAndMalformedDoesNotCount() = runBlocking {
        pin.setPin("2580", "2580", proof, true)
        assertEquals(PinResult.Malformed, pin.verify("12"))
        assertEquals(PinResult.Malformed, pin.verify("abcd"))
        assertEquals(0, pin.failedAttempts())
        assertEquals(PinResult.Wrong(2), pin.verify("1357"))
        assertEquals(PinResult.Wrong(1), pin.verify("9024"))
        assertEquals(PinResult.Locked, pin.verify("1122"))
        // Locked: even the right PIN is refused.
        assertEquals(PinResult.Locked, pin.verify("2580"))
        assertTrue(pin.isLocked())
    }

    @Test fun lockoutSurvivesRestartAndOnlyStrongAuthResetsIt() = runBlocking {
        pin.setPin("2580", "2580", proof, true)
        repeat(3) { pin.verify("1357") }
        val afterRestart = newAuth() // same encrypted store, new process
        assertTrue(afterRestart.isLocked())
        assertEquals(PinResult.Locked, afterRestart.verify("2580"))
        afterRestart.resetLockout(StrongAuthProof(AuthMethod.DEVICE_CREDENTIAL))
        assertEquals(PinResult.Ok, afterRestart.verify("2580"))
    }

    @Test fun correctPinBeforeLockoutClearsTheCounter() = runBlocking {
        pin.setPin("2580", "2580", proof, true)
        pin.verify("1357"); pin.verify("9024")
        assertEquals(PinResult.Ok, pin.verify("2580"))
        assertEquals(0, pin.failedAttempts())
    }

    @Test fun pinPathCannotMintAStrongAuthProof() {
        val r = runCatching { StrongAuthProof(AuthMethod.PIN) }
        assertTrue(r.isFailure)
        assertTrue(runCatching { StrongAuthProof(AuthMethod.DEVICE_UNLOCK) }.isFailure)
    }

    @Test fun removePinNeedsStrongAuth() = runBlocking {
        pin.setPin("2580", "2580", proof, true)
        assertEquals(PinChangeResult.NeedsStrongAuth, pin.removePin(null, deviceSecure = true))
        assertEquals(PinChangeResult.Ok, pin.removePin(proof, deviceSecure = true))
        assertFalse(pin.hasPin())
    }
}

class SessionManagerTest {
    private var now = 0L
    private val sessions = SessionManager { now }
    private val policy = SessionPolicy(personalMs = 5 * 60_000L, sensitiveMs = 2 * 60_000L)

    @Test fun sensitiveSessionLastsTwoMinutesAndCoversPersonal() {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.PIN)
        assertTrue(sessions.isValid(AuthTier.SENSITIVE, policy))
        assertTrue(sessions.isValid(AuthTier.PERSONAL, policy))
        assertEquals(AuthMethod.PIN, sessions.current(AuthTier.SENSITIVE, policy)!!.method)
        now = 2 * 60_000L + 1
        assertFalse(sessions.isValid(AuthTier.SENSITIVE, policy))
        assertTrue(sessions.isValid(AuthTier.PERSONAL, policy))
        now = 5 * 60_000L + 1
        assertFalse(sessions.isValid(AuthTier.PERSONAL, policy))
    }

    @Test fun personalNeverCoversSensitiveAndOpenIsAlwaysValid() {
        sessions.grant(AuthTier.PERSONAL, AuthMethod.DEVICE_UNLOCK)
        assertFalse(sessions.isValid(AuthTier.SENSITIVE, policy))
        assertTrue(sessions.isValid(AuthTier.OPEN, policy))
    }

    @Test fun actionsUseTheSensitiveSession() {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.BIOMETRIC)
        assertTrue(sessions.isValid(AuthTier.ACTIONS, policy))
    }

    @Test fun lockEndsEverything() {
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.BIOMETRIC)
        sessions.lock()
        assertFalse(sessions.isValid(AuthTier.PERSONAL, policy))
    }

    @Test fun clockGoingBackwardsDoesNotExtendASession() {
        now = 10_000
        sessions.grant(AuthTier.SENSITIVE, AuthMethod.PIN)
        now = 0
        assertFalse(sessions.isValid(AuthTier.SENSITIVE, policy))
    }
}

class ToolPolicyTest {
    private val netWorth = ToolRegistry.byName("networth_snapshot")!! // SENSITIVE read
    private val watchlist = ToolRegistry.byName("user_watchlist")!! // PERSONAL read
    private val price = ToolRegistry.byName("get_indian_stocks_details")!! // OPEN read
    private val sendSms = price.copy(name = "send_sms", risk = RiskLevel.ACTION, tier = AuthTier.ACTIONS, confirmationRequired = true)
    private val createNote = price.copy(name = "create_note", risk = RiskLevel.WRITE, tier = AuthTier.PERSONAL)

    private fun d(spec: dev.jarvis.agent.ToolSpec, level: PolicyLevel, rules: Map<String, ToolRule> = emptyMap()) =
        ToolPolicy.decide(spec, level, rules)

    @Test fun smartDefaults() {
        assertEquals(PolicyDecision.ALLOW, d(price, PolicyLevel.SMART))
        assertEquals(PolicyDecision.ALLOW, d(watchlist, PolicyLevel.SMART))
        assertEquals(PolicyDecision.ASK, d(netWorth, PolicyLevel.SMART))
        assertEquals(PolicyDecision.ASK, d(createNote, PolicyLevel.SMART))
    }

    @Test fun strictAsksForEverythingButOpenReads() {
        assertEquals(PolicyDecision.ALLOW, d(price, PolicyLevel.STRICT))
        assertEquals(PolicyDecision.ASK, d(watchlist, PolicyLevel.STRICT))
        assertEquals(PolicyDecision.ASK, d(netWorth, PolicyLevel.STRICT))
    }

    @Test fun allowOverrideIsHonouredForWritesOnlyInAuto() {
        val rules = mapOf("create_note" to ToolRule.ALLOW)
        assertEquals(PolicyDecision.ASK, d(createNote, PolicyLevel.SMART, rules))
        assertEquals(PolicyDecision.ALLOW, d(createNote, PolicyLevel.AUTO, rules))
        assertEquals(PolicyDecision.ASK, d(createNote, PolicyLevel.AUTO)) // not allowlisted
    }

    @Test fun denyAlwaysWinsAndConfirmationToolsAlwaysAsk() {
        PolicyLevel.entries.forEach { level ->
            assertEquals(PolicyDecision.DENY, d(price, level, mapOf("get_indian_stocks_details" to ToolRule.DENY)))
            assertEquals(PolicyDecision.ASK, d(sendSms, level, mapOf("send_sms" to ToolRule.ALLOW)))
            assertEquals(PolicyDecision.ASK, d(price, level, mapOf("get_indian_stocks_details" to ToolRule.ASK)))
        }
    }

    @Test fun rulesRoundTripThroughStorage() {
        val rules = mapOf("a" to ToolRule.DENY, "b" to ToolRule.ALLOW, "c" to ToolRule.DEFAULT)
        assertEquals(mapOf("a" to ToolRule.DENY, "b" to ToolRule.ALLOW), ToolPolicy.decode(ToolPolicy.encode(rules)))
        assertTrue(ToolPolicy.decode("junk,x=NOPE,=ALLOW").isEmpty())
    }
}

class AuthMethodParserTest {
    @Test fun trailingOrLeadingMethodIsExtracted() {
        assertEquals("net worth" to AuthMethod.BIOMETRIC, AuthMethodParser.extract("net worth, fingerprint"))
        assertEquals("what's my net worth" to AuthMethod.PIN, AuthMethodParser.extract("what's my net worth, use PIN"))
        assertEquals("net worth" to AuthMethod.BIOMETRIC, AuthMethodParser.extract("Fingerprint: net worth"))
        assertEquals("show holdings" to AuthMethod.BIOMETRIC, AuthMethodParser.extract("show holdings with biometric."))
    }

    @Test fun ordinaryTextIsLeftAlone() {
        assertEquals("what's the pin code of Pune" to null, AuthMethodParser.extract("what's the pin code of Pune"))
        assertEquals("pin" to null, AuthMethodParser.extract("pin"))
        assertEquals("spinning wheel" to null, AuthMethodParser.extract("spinning wheel"))
    }
}
