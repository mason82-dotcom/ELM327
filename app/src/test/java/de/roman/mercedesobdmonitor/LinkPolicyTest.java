package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class LinkPolicyTest {
    @Test public void protocolSelectUsesDetectedStandardProtocol() {
        assertEquals("ATSP6", LinkPolicy.protocolSelect("A6"));
        assertEquals("ATSP6", LinkPolicy.protocolSelect("6"));
        assertEquals("ATSP3", LinkPolicy.protocolSelect(" a3 "));
    }

    @Test public void protocolSelectFallsBackToAutoSearch() {
        assertEquals("ATSP0", LinkPolicy.protocolSelect(null));
        assertEquals("ATSP0", LinkPolicy.protocolSelect(""));
        assertEquals("ATSP0", LinkPolicy.protocolSelect("0"));
        assertEquals("ATSP0", LinkPolicy.protocolSelect("AB"));   // benutzerdefiniert B
        assertEquals("ATSP0", LinkPolicy.protocolSelect("?"));
        assertEquals("ATSP0", LinkPolicy.protocolSelect("NO DATA"));
    }

    @Test public void reconnectInitRestoresFullAdapterSetup() {
        List<String> init = LinkPolicy.reconnectInit("A6");
        assertTrue(init.contains("ATAT1"));
        assertTrue(init.contains("ATST64"));
        assertTrue(init.contains("ATSP6"));
        assertFalse(init.contains("ATSP0"));
        for (String cmd : init) {
            assertTrue("nicht erlaubt: " + cmd, CommandSafety.isAllowed(cmd));
        }
    }

    @Test public void singleTimeoutWithResyncKeepsConnection() {
        LinkPolicy.TimeoutTracker t = new LinkPolicy.TimeoutTracker();
        assertFalse(t.onTimeout(true));
        t.onResponse();
        assertFalse(t.onTimeout(true));
        assertEquals(1, t.consecutive());
    }

    @Test public void secondConsecutiveTimeoutReconnects() {
        LinkPolicy.TimeoutTracker t = new LinkPolicy.TimeoutTracker();
        assertFalse(t.onTimeout(true));
        assertTrue(t.onTimeout(true));
    }

    @Test public void failedResyncReconnectsImmediately() {
        LinkPolicy.TimeoutTracker t = new LinkPolicy.TimeoutTracker();
        assertTrue(t.onTimeout(false));
    }
}
