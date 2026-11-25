package dev.jarvis.agent

import dev.jarvis.ui.common.dataLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DataStampTest {
    @Test fun dataStampsRoundTripAndIgnoreJunk() {
        val stamps = listOf(DataStamp("INDmoney", "networth_snapshot", 123L, cached = false), DataStamp("IND|money;", "mf_sips", 9L, cached = true))
        val decoded = DataStamp.decode(DataStamp.encode(stamps))
        assertEquals(listOf("INDmoney", "INDmoney"), decoded.map { it.source })
        assertEquals(listOf(false, true), decoded.map { it.cached })
        assertNull(DataStamp.encode(emptyList()))
        assertTrue(DataStamp.decode("garbage;a|b|notanumber|live").isEmpty())
    }

    @Test fun dataLabelSaysLiveOrSaved() {
        val utc = ZoneOffset.UTC
        assertNull(dataLabel(emptyList(), utc))
        val live = dataLabel(listOf(DataStamp("INDmoney", "networth_snapshot", 0L, cached = false)), utc)!!
        assertTrue(live, live.startsWith("INDmoney · updated "))
        val saved = dataLabel(listOf(DataStamp("INDmoney", "networth_snapshot", 0L, cached = true)), utc)!!
        assertTrue(saved, saved.startsWith("INDmoney · saved data from "))
    }

    @Test fun ageLabels() {
        assertEquals("just now", AgentLoop.ageLabel(30_000))
        assertEquals("12 min ago", AgentLoop.ageLabel(12 * 60_000L))
        assertEquals("2 h ago", AgentLoop.ageLabel(125 * 60_000L))
        assertEquals("just now", AgentLoop.ageLabel(-5))
    }
}
