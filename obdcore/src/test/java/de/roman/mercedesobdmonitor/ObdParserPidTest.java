package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public class ObdParserPidTest {
    @Test public void parsesMode01Payload() {
        assertArrayEquals(new byte[] {0x1A, (byte) 0xF8},
                ObdParser.mode01Data("41 0C 1A F8", 0x0C, 2));
    }

    @Test public void unionsSupportedPidBitmapsFromMultipleEcus() {
        // ECU 1: PID 01; ECU 2: PID 02. Union must contain both.
        Set<Integer> pids = ObdParser.supportedPids(
                "410080000000\r410040000000\r", 0x00);
        assertTrue(pids.contains(0x01));
        assertTrue(pids.contains(0x02));
    }

    /** ADR-008: mehrteilige Antwort mit Header (Live-Polling mit ATH1) ergibt dieselbe Nachricht wie ohne Header. */
    @Test public void assemblesMultiFrameAnswerWithCanHeaders() {
        java.util.List<String> withHeaders = ObdParser.canMessages(TransmissionLiveTest.DRIVE_ATH1);
        java.util.List<String> without = ObdParser.canMessages(TransmissionLiveTest.DRIVE_ATH0);
        assertEquals(without, withHeaders);
        // 24 Bytes laut Erstrahmen (0x18), Füllbytes des letzten Folgerahmens abgeschnitten
        assertEquals("61300156002D03C801070102327000A300A2027B03810090", withHeaders.get(0));
    }

    /** Zwei Steuergeräte antworten mehrteilig und verschränkt: zwei getrennte Nachrichten, nicht eine vermischte. */
    @Test public void keepsMultiFrameAnswersOfTwoEcusApart() {
        String raw = "7E9 10 0A 49 02 01 41 42 43\r7EA 10 0A 49 02 01 58 59 5A\r"
                + "7E9 21 44 45 46 47 00 00 00\r7EA 21 31 32 33 34 00 00 00";
        java.util.List<String> m = ObdParser.canMessages(raw);
        assertEquals(java.util.List.of("49020141424344454647", "49020158595A31323334"), m);
    }

    @Test public void isoTpRejectsMissingSwappedAndShortSegments() {
        String ok = "00A\r0:490201313233\r1:34353637";
        assertEquals(1, ObdParser.canMessages(ok).size());
        assertFalse(ObdParser.hasIncompleteMessage(ok));
        for (String bad : new String[] {
                "018\r0:613001560000\r2:70000000000000\r3:00000000000000",
                "018\r0:613001560000\r2:70000000000000\r1:00000000000000\r3:00000000000000",
                "018\r0:613001560000\r1:00000000000000",
                "018\r0:613001560000\r1:00000000000000<RX ERROR\r2:00000000000000\r3:00000000000000",
                "1:34353637"}) {
            assertTrue(bad, ObdParser.canMessages(bad).isEmpty());
            assertTrue(bad, ObdParser.hasIncompleteMessage(bad));
        }
        // Zählerüberlauf F → 0 bei langen Antworten
        StringBuilder sb = new StringBuilder("07A\r0:4902AAAAAAAA");
        for (int i = 1; i <= 17; i++) sb.append('\r').append(String.format("%X", i & 15)).append(":AAAAAAAAAAAAAA");
        assertEquals(1, ObdParser.canMessages(sb.toString()).size());
    }

    @Test public void singleFrameOfAnotherEcuDoesNotCutAMultiframe() {
        java.util.List<String> m = ObdParser.canMessages("00A\r0:430401710172\r4300\r1:01740175");
        assertTrue(m.toString(), m.contains("4300") && m.contains("43040171017201740175"));
    }

    @Test public void corruptedLinesAreNoValues() {
        assertNull(ObdParser.mode01Data("410C0FA0<RX ERROR", 0x0C, 2));
        assertNull(ObdParser.mode01Data("0A410C0FA0", 0x0C, 2));
        assertNull(ObdParser.mode01Data("410C0FA", 0x0C, 1));
        assertNotNull(ObdParser.mode01Data("41 0C 0F A0", 0x0C, 2));
        for (String e : new String[] {"410C0FA0 <RX ERROR", "LV RESET", "ACT ALERT", "ERR94"}) {
            assertTrue(e, ObdParser.isLinkError(e));
        }
        assertFalse(ObdParser.isLinkError("410C0FA0"));
    }

    @Test public void engineAnswerNeedsTheEngineEcu() {
        assertTrue(ObdParser.isEngineAnswer("7E904414226A4\r7E80441422CEC", 0x42));
        assertFalse(ObdParser.isEngineAnswer("7E90441422A94", 0x42));
        assertFalse(ObdParser.isEngineAnswer("7E80441422CEC<RX ERROR\r7E90441422A94", 0x42));
        assertTrue(ObdParser.isEngineAnswer("41422CEC", 0x42));
        assertFalse("ohne Header zwei Absender", ObdParser.isEngineAnswer("41422A94\r41422CEC", 0x42));
    }

    @Test public void corruptedLinesDropOutForEveryMode() {
        assertNull(ObdParser.mode02Data("42050?05B", 0x05, 1));
        assertTrue(ObdParser.supportedPids("4100BE?3EA813", 0).isEmpty());
        assertTrue(ObdParser.canMessages("430?10171").isEmpty());
    }
}
