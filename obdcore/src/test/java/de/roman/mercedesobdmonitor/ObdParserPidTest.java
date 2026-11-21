package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
}
