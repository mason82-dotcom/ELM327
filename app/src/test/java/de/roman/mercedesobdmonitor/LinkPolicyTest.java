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

    @Test public void terminalFormatChangesAreReverted() {
        assertEquals(List.of("ATH0"), LinkPolicy.restoreAfterTerminal("ath1", "A6"));
        assertEquals(List.of("ATE0"), LinkPolicy.restoreAfterTerminal("AT E1", "A6"));
        assertEquals(List.of("ATS0"), LinkPolicy.restoreAfterTerminal("ATS1", "A6"));
        assertEquals(List.of("ATL0"), LinkPolicy.restoreAfterTerminal("ATL1", "A6"));
        assertEquals(List.of("ATAT1"), LinkPolicy.restoreAfterTerminal("ATAT2", "A6"));
        assertEquals(List.of("ATST64"), LinkPolicy.restoreAfterTerminal("ATSTFF", "A6"));
    }

    @Test public void terminalResetRestoresFullSetupWithDetectedProtocol() {
        assertEquals(LinkPolicy.reconnectInit("A6"), LinkPolicy.restoreAfterTerminal("ATZ", "A6"));
    }

    @Test public void harmlessOrIntentionalTerminalCommandsNeedNoRestore() {
        for (String cmd : new String[] {"ATI", "ATRV", "ATDPN", "ATH0", "ATE0", "ATST64", "ATAT1",
                "ATSP6", "ATSP0", "0100", "03"}) {
            assertTrue(cmd, LinkPolicy.restoreAfterTerminal(cmd, "A6").isEmpty());
        }
    }

    @Test public void restoreCommandsPassReadOnlyGate() {
        for (String cmd : new String[] {"ATZ", "ATE1", "ATL1", "ATS1", "ATH1", "ATAT0", "ATST20"}) {
            for (String restore : LinkPolicy.restoreAfterTerminal(cmd, "A6")) {
                assertTrue(restore, CommandSafety.isAllowed(restore));
            }
        }
    }
}
