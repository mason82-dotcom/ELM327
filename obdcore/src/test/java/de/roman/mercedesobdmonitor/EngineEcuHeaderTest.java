package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Live-Polling mit CAN-Header (ATH1) auf 11-bit-CAN: Am C350 beantworten Motor (7E8) und Getriebe (7E9)
 * u. a. 01 0C/0D/04/05/42/49 (Log 07.11.2026). Gewertet wird die Antwort des Motorsteuergeräts, unabhängig
 * von der Empfangsreihenfolge; ohne Header (ATH0) bleibt alles wie bisher.
 */
public class EngineEcuHeaderTest {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("PID missing: " + id);
    }

    @Test
    public void engineAnswerWinsRegardlessOfOrder() {
        assertEquals(953.75, pid(0x0C).parse("7E804410C0EE7\r7E904410C0F08\r"), 1e-9);
        assertEquals(953.75, pid(0x0C).parse("7E904410C0F08\r7E804410C0EE7\r"), 1e-9);
        // mit Leerzeichen (ATS1) und Prompt
        assertEquals(953.75, pid(0x0C).parse("7E9 04 41 0C 0F 08 \r7E8 04 41 0C 0E E7 \r\r>"), 1e-9);
        assertEquals(14.135, pid(0x42).parse("7E90441423539\r7E80441423737"), 1e-9);
    }

    @Test
    public void otherEcuIsFallbackWhenEngineIsSilent() {
        assertEquals(1005.0, pid(0x0C).parse("7E904410C0FB4"), 1e-9);
    }

    @Test
    public void pciLengthCutsPadding() {
        assertEquals(32.0, pid(0x0D).parse("7E803410D20AAAAAAAA"), 1e-9);
        assertNull(pid(0x0C).parse("7E804410C0E"));            // PCI verspricht mehr, als ankam
    }

    @Test
    public void withoutHeadersNothingChanges() {
        assertEquals(953.75, pid(0x0C).parse("410C0EE7"), 1e-9);
        assertEquals(953.75, pid(0x0C).parse("410C0EE7\r410C0F08"), 1e-9);
        assertEquals(List.of("4300"), ObdParser.canMessages("4300"));
    }

    @Test
    public void supportedPidsAreUnitedAcrossEcusWithHeaders() {
        Set<Integer> s = ObdParser.supportedPids("7E8064100BE3FA813\r7E906410098180001", 0x00);
        assertTrue(s.contains(0x0C));
        assertTrue(s.contains(0x05));
        assertTrue(s.contains(0x20));                           // Bit 32 → nächste Bitmap
    }

    @Test
    public void readinessAndOtherCanParsersSeePayloadWithoutHeader() {
        List<String> msgs = ObdParser.canMessages("7E806414100076504\r7E906414100040000");
        assertEquals("414100076504", msgs.get(0));
        assertNotNull(Readiness.parseDriveCycle("7E806414100076504\r7E906414100040000"));
    }

    @Test
    public void multiFrameWithHeaderIsNotMistakenForSingleFrame() {
        assertNull(ObdParser.splitCanHeader("7E8101449020157444"));
        assertNull(ObdParser.splitCanHeader("410C0EE7"));
        assertNull(ObdParser.splitCanHeader("7DF02010C"));      // Anfrage-Kennung, keine Antwort
    }
}
