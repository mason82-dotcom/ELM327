package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** Bus-/Verbindungsfehler des ELM327 dürfen nicht als „keine Daten / keine Codes“ gelten. */
public class LinkErrorTest {

    @Test public void noDataIsNotALinkError() {
        assertTrue(ObdParser.isNoData("NO DATA"));
        assertFalse(ObdParser.isLinkError("NO DATA"));
    }

    @Test public void adapterErrorsAreLinkErrorsNotNoData() {
        for (String raw : new String[] {"UNABLE TO CONNECT", "BUS ERROR", "CAN ERROR",
                "BUS INIT: ...ERROR", "FB ERROR", "DATA ERROR", "BUFFER FULL", "STOPPED",
                "SEARCHING...\rUNABLE TO CONNECT"}) {
            assertTrue(raw, ObdParser.isLinkError(raw));
            assertFalse(raw, ObdParser.isNoData(raw));
        }
    }

    @Test public void validResponsesAreNoLinkError() {
        assertFalse(ObdParser.isLinkError("41 0C 1A F8"));
        assertFalse(ObdParser.isLinkError("43 00"));
        assertFalse(ObdParser.isLinkError(null));
    }

    private static final class Transport implements InspectionReader.Transport {
        final Map<String, String> answers = new HashMap<>();

        @Override public String send(String cmd, int timeoutMs) {
            return answers.getOrDefault(cmd, "NO DATA");
        }

        @Override public boolean resync() { return true; }
    }

    private static Transport healthyCar() {
        Transport t = new Transport();
        t.answers.put("0101", "41 01 00 07 65 00");
        t.answers.put("03", "43 00");
        t.answers.put("07", "47 00");
        t.answers.put("0A", "4A 00");
        return t;
    }

    @Test public void busErrorOnStoredDtcsIsNotReady() throws IOException {
        Transport t = healthyCar();
        t.answers.put("03", "CAN ERROR");
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> false);
        assertFalse(in.storedKnown);
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
    }

    @Test public void unableToConnectOnPendingDtcsIsNotReady() throws IOException {
        Transport t = healthyCar();
        t.answers.put("07", "SEARCHING...\rUNABLE TO CONNECT");
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> false);
        assertFalse(in.pendingKnown);
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
    }

    @Test public void noDataOnStoredDtcsStillCountsAsRead() throws IOException {
        Transport t = healthyCar();
        t.answers.put("03", "NO DATA");
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> false);
        assertTrue(in.storedKnown);
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in));
    }
}
