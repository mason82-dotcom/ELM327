package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ADR-004: Antwortformen nach ecu_diagnostics (Daimler-KWP2000); am C350 ungeprüft. */
public class KwpReadoutTest {
    /** 5A 86 + 16 Bytes, als ISO-TP-Mehrframe wie vom ELM327 (Länge 0x012 = 18). */
    @Test public void identFromMultiFrame() {
        String raw = "012\r0: 5A 86 27 21 53 07\r1: 79 05 07 12 07 03 00\r2: 01 02 07 05 21 00 00\r";
        String t = KwpReadout.ident("Getriebe (7E1)", raw);
        assertTrue(t, t.contains("Teilenummer: A 272 153 07 79"));
        assertTrue(t, t.contains("Hardware: KW 05/07 · Software: KW 12/07"));
        assertTrue(t, t.contains("Lieferant: 03"));
        assertTrue(t, t.contains("Diagnose-Info: 00 01 (Serie)"));
        assertTrue(t, t.contains("Herstelldatum: 21.05.2007"));
        assertTrue(t, t.contains("Rohantwort: 5A 86 27 21"));
    }

    @Test public void identDeviatingLengthShowsOnlyRaw() {
        String t = KwpReadout.ident("Motor (7E0)", "5A 86 01 02 03");
        assertTrue(t, t.contains("Länge 5 statt 18"));
        assertTrue(t, t.contains("Rohantwort: 5A 86 01 02 03"));
    }

    @Test public void rejectionsCarryTheCode() {
        assertTrue(KwpReadout.ident("Motor (7E0)", "7F 1A 31").contains("Code 31: Kennung nicht vorhanden"));
        assertTrue(KwpReadout.ident("Motor (7E0)", "7F 1A 78").contains("nicht nachgewartet"));
        assertTrue(KwpReadout.dtcs("7F 18 11").contains("Dienst nicht unterstützt"));
        assertTrue(KwpReadout.dtcs("NO DATA").contains("keine Antwort"));
        assertTrue(KwpReadout.ident("Motor (7E0)", "7F 1A 13").contains("falsche Nachrichtenlänge"));
        // ATF-Abfrage nennt den Code ebenfalls
        assertTrue(AtfTemperature.parse("7F 21 12").problem.contains("Code 12"));
    }

    @Test public void dtcList() {
        String t = KwpReadout.dtcs("58 02 07 15 E0 21 01 20");
        assertTrue(t, t.contains("2 gespeichert:"));
        assertTrue(t, t.contains("0715 · Status E0 (aktuell vorhanden, Warnlampe) – nicht in der 722.9-Tabelle (ISO-Lesart P0715"));
        assertTrue(t, t.contains("2101 · Status 20 (gespeichert, derzeit nicht vorhanden) – nicht in der 722.9-Tabelle"));
        assertTrue(t, t.contains("Codes ohne Eintrag"));
        assertFalse(t, t.contains("Kurztexte"));      // kein Tabellentext gezeigt, keine Quellenzeile
        assertTrue(t, t.endsWith("Löschen bleibt gesperrt."));
        assertTrue(KwpReadout.dtcs("58 00").contains("Keine gespeicherten Fehler"));
        assertTrue(KwpReadout.dtcs("58 03 07 15 E0").contains("3 gespeichert (aufgelistet 1)"));
        assertTrue(KwpReadout.dtcs("58 01 07 15").contains("Länge passt nicht"));
    }

    @Test public void isoNotation() {
        assertEquals("P0715", KwpReadout.iso(0x07, 0x15));
        assertEquals("C0101", KwpReadout.iso(0x41, 0x01));
        assertEquals("B1234", KwpReadout.iso(0x92, 0x34));
        assertEquals("U3FFF", KwpReadout.iso(0xFF, 0xFF));
    }
}
