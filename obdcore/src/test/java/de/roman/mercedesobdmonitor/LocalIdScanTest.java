package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class LocalIdScanTest {
    @Test
    public void classifiesPositiveRejectedSilentAndServiceUnavailable() {
        LocalIdScan s = new LocalIdScan();
        assertEquals(LocalIdScan.Kind.POSITIVE, s.add(0x01, "61 01 7A 10 00"));
        assertEquals(LocalIdScan.Kind.REJECTED, s.add(0x02, "7F 21 12"));        // Kennung nicht unterstützt
        assertEquals(LocalIdScan.Kind.SILENT, s.add(0x03, "NO DATA"));
        assertEquals(LocalIdScan.Kind.SILENT, s.add(0x04, "61 05 11"));         // falsches Echo
        assertEquals(LocalIdScan.Kind.SERVICE_UNAVAILABLE, s.add(0x05, "7F 21 11"));
        assertEquals(LocalIdScan.Kind.SERVICE_UNAVAILABLE, s.add(0x06, "7F 21 80"));
        s.timeout();
        String r = s.report(81, 6, "Test", null, Double.NaN);
        assertTrue(r, r.contains("Antworten 1 · abgelehnt 1 · ohne Antwort 3"));
        assertTrue(r, r.contains("21 01 [3]: 7A 10 00"));
        assertTrue(r, r.contains("Abgelehnt: 02"));
        assertTrue(r, r.contains("Dienst 21 nicht verfügbar"));
        assertTrue(r, r.contains("VORZEITIG BEENDET: Test"));
    }

    @Test
    public void warmWindowReachesAboveCoolant() {
        LocalIdScan s = new LocalIdScan();
        s.add(0x10, "61 10 8E");    // A−40 = 102 °C: 14 K über 88 °C Kühlmittel
        s.add(0x20, "61 20 FF 00"); // nichts im Fenster
        List<LocalIdScan.Candidate> warm = s.candidates(88);
        assertEquals(1, warm.size());
        assertEquals(102, warm.get(0).value, 1e-9);
        // kalt ist das Fenster eng: 102 °C wäre bei 20 °C Kühlmittel kein Kandidat
        assertTrue(s.candidates(20).isEmpty());
        assertTrue(s.report(88, 2, null, null, Double.NaN).contains("Fenster 83…113 °C: 1"));
    }

    @Test
    public void secondRunConfirmsOnlyCandidatesThatFollowedCoolant() {
        LocalIdScan cold = new LocalIdScan();
        cold.add(0x2A, "61 2A 00 3C 3D");    // Byte 1: 20 °C (A−40), Byte 2: 21 °C
        LocalIdScan warm = new LocalIdScan();
        warm.add(0x2A, "61 2A 00 8E 3D");    // Byte 1 wandert auf 102 °C, Byte 2 bleibt 21 °C
        List<LocalIdScan.Candidate> c = warm.confirmed(88, cold, 18);
        assertEquals(1, c.size());
        assertEquals(1, c.get(0).index);
        String r = warm.report(88, 254, null, cold, 18);
        assertTrue(r, r.contains("BESTÄTIGT gegen den Lauf bei 18 °C"));
        assertTrue(r, r.contains("21 2A Byte 1: A − 40 = 102,0 °C"));
        // zu kleiner Temperaturunterschied: keine Bestätigung
        assertTrue(warm.confirmed(30, cold, 18).isEmpty());
        assertTrue(warm.report(30, 254, null, cold, 18).contains("weniger als 20 K"));
    }

    @Test
    public void serializeRoundTripAndCoolantFromReport() {
        LocalIdScan s = new LocalIdScan();
        s.add(0x2A, "61 2A 00 3C");
        s.add(0x47, "61 47");
        LocalIdScan.Saved back = LocalIdScan.parse(s.serialize(18.0));
        assertEquals(18.0, back.coolantC, 1e-9);
        assertEquals(2, back.scan.records().size());
        assertEquals(0x3C, back.scan.records().get(0).data[1] & 0xFF);
        assertEquals(0, back.scan.records().get(1).data.length);
        assertNull(LocalIdScan.parse("kaputt"));
        assertNull(LocalIdScan.parse(""));
        assertEquals(18.0, LocalIdScan.coolantFromReport(s.report(18, 2, null, null, Double.NaN)), 1e-9);
        assertTrue(Double.isNaN(LocalIdScan.coolantFromReport("ohne")));
    }

    @Test
    public void unknownCoolantGivesNoCandidates() {
        LocalIdScan s = new LocalIdScan();
        s.add(0x10, "61 10 7A");
        assertTrue(s.candidates(Double.NaN).isEmpty());
        String r = s.report(Double.NaN, 254, null, null, Double.NaN);
        assertTrue(r, r.contains("Kühlmittel: unbekannt"));
        assertFalse(r, r.contains("Kandidaten im Fenster"));
    }
}
