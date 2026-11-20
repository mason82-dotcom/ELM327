package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanMonitorTest {
    @Test public void bitsFollowMbuxPortConvention() {
        byte[] d = {(byte) 0x5A, (byte) 0x3C, (byte) 0x6E, 0x28, 0x00, 0x12, 0x34, 0x00};
        assertEquals(0x5A, CanMonitor.bits(d, 0, 8));    // Byte 0
        assertEquals(0x6E, CanMonitor.bits(d, 16, 8));   // Byte 2
        assertEquals(0x1234, CanMonitor.bits(d, 40, 16)); // Bytes 5–6 big-endian
        assertEquals(0x5, CanMonitor.bits(d, 0, 4));     // oberes Nibble
        assertEquals(0xA, CanMonitor.bits(d, 4, 4));     // unteres Nibble
        assertEquals(0x1A3C & 0x3FFF, CanMonitor.bits(d, 2, 14)); // über die Bytegrenze
        assertEquals(-1, CanMonitor.bits(new byte[2], 8, 16));    // Frame zu kurz
    }

    @Test public void parsesFramesWithAndWithoutSpaces() {
        CanMonitor.Result r = CanMonitor.parse("30D5A3C6E2800123400\r\n30D 5A 3C 6F 28 00 12 34 00\r\n2F1 64 00 20 00\r\n");
        assertEquals(3, r.frames);
        assertEquals(2, r.ids.get(0x30D).count);
        assertEquals(1, r.ids.get(0x30D).changes);
        assertEquals(4, r.ids.get(0x2F1).last.length);
        assertEquals(0, r.unparsed);
    }

    @Test public void recognisesAdapterStatusLines() {
        CanMonitor.Result r = CanMonitor.parse("ATMA\r30D5A3C\rBUFFER FULL\r");
        assertTrue(r.bufferFull);
        assertEquals(1, r.frames);
        assertTrue(CanMonitor.parse("?").unsupported);
        assertTrue(CanMonitor.parse("CAN ERROR").canError);
        assertEquals(2, CanMonitor.parse("HALLO\r30D5\r").unparsed); // Text und ungerade Datenlänge
    }

    @Test public void emptyBusExplainsGateway() {
        String rep = CanMonitor.report(CanMonitor.parse(""), 5000, false);
        assertTrue(rep, rep.contains("Keine Bus-Botschaften"));
        assertTrue(rep, rep.contains("Gateway"));
    }

    @Test public void onlyDiagnosticResponsesAreNotBusTraffic() {
        String rep = CanMonitor.report(CanMonitor.parse("7E8 03 41 0C 1A\r"), 5000, false);
        assertTrue(rep, rep.contains("Nur Diagnose-Antworten"));
    }

    @Test public void knownFramesShowRawValuesOnly() {
        String rep = CanMonitor.report(CanMonitor.parse("30D5A3C6E2800123400\r0F332\r"), 5000, false);
        assertTrue(rep, rep.contains("30D ECM_A1"));
        assertTrue(rep, rep.contains("Motoröltemperatur: 110 (°C, Versatz unbekannt)"));
        assertTrue(rep, rep.contains("Kraftstoffverbrauch: 4660"));
        assertTrue(rep, rep.contains("Istgang: 2"));
        assertTrue(rep, rep.contains("Zielgang: 3"));
        // Signal hinter dem Frameende: kein Wert, keine Ausnahme
        String shortFrame = CanMonitor.report(CanMonitor.parse("30D5A\r"), 1000, false);
        assertTrue(shortFrame, shortFrame.contains("Motorölstand: –"));
    }

    @Test public void unsupportedAdapterStopsReport() {
        String rep = CanMonitor.report(CanMonitor.parse("?"), 50, false);
        assertTrue(rep, rep.contains("kennt ATMA nicht"));
        assertFalse(rep, rep.contains("Gateway"));
    }

    /** Datenlimit oder Stopp-Zeichen mitten im Frame: der Rest zählt nicht als kürzerer Frame. */
    @Test public void dropsCutOffLastLine() {
        String raw = "30D5A3C6E2800123400\r30D5A3C6E";
        assertEquals("30D5A3C6E2800123400", CanMonitor.completeLines(raw));
        CanMonitor.Result r = CanMonitor.parse(CanMonitor.completeLines(raw));
        assertEquals(1, r.frames);
        assertEquals(0, r.ids.get(0x30D).changes);
        assertEquals(8, r.ids.get(0x30D).last.length);
        assertEquals("", CanMonitor.completeLines("30D5A"));
        assertEquals("?", CanMonitor.completeLines("?\r\r"));
    }

    @Test public void reportsNoDataFromAdapter() {
        assertTrue(CanMonitor.report(CanMonitor.parse("NO DATA\r"), 5000, false).contains("NO DATA"));
        assertFalse(CanMonitor.report(CanMonitor.parse(""), 5000, false).contains("NO DATA"));
    }
}
