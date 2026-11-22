package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.junit.Test;

public class Vgs7229CodesTest {
    @Test
    public void tableIsComplete() {
        // alle 129 Kennungen der Tabelle „ETC2 (722.9) ab 2008“ (abgeglichen 22.11.2026); Doppelte bricht der
        // Static-Block ab
        assertEquals(129, Vgs7229Codes.size());
    }

    @Test
    public void fourDigitCodesMatchDirectly() {
        Vgs7229Codes.Entry e = Vgs7229Codes.lookup(0x02, 0x18);
        assertEquals("0218", e.code);
        assertEquals(EnumSet.of(Vgs7229Codes.Group.OIL), e.groups);
        assertTrue(e.text, e.text.startsWith("Getriebeöl zu heiß"));
        assertTrue(Vgs7229Codes.lookup(0x27, 0x59).text.contains("Y3/8y8"));
        assertEquals("C300", Vgs7229Codes.lookup(0xC3, 0x00).code);
        assertEquals(EnumSet.of(Vgs7229Codes.Group.OIL, Vgs7229Codes.Group.VOLTAGE),
                Vgs7229Codes.possibleECode(0x17, 0x07).groups);   // E1707: Übertemperatur und Überspannung
    }

    @Test
    public void eCodesAreOnlyAPossibilityNeverAMatch() {
        assertNull(Vgs7229Codes.lookup(0xC1, 0x00));
        assertEquals("EC100", Vgs7229Codes.possibleECode(0xC1, 0x00).code);
        String t = KwpReadout.dtcs("58 01 C1 00 E0");
        assertTrue(t, t.contains("• C100 · Status E0"));                       // gesendete Bytes, kein „E“ davor
        assertTrue(t, t.contains("möglich, nicht belegt: EC100 – Keine CAN-Botschaft vom Motorsteuergerät"));
        assertFalse(t, t.contains("Was jetzt"));                               // kein Rat auf eine Vermutung
    }

    @Test
    public void reportAdvisesOnlyForPresentFaults() {
        // 2759 aktuell (E0), 0218 aktuell (60), 0748 nur gespeichert (20), 0715 unbekannt
        String t = KwpReadout.dtcs("58 04 27 59 E0 02 18 60 07 48 20 07 15 20");
        assertTrue(t, t.contains("2759 · Status E0 (aktuell vorhanden, Warnlampe) – Magnetventil Wandlerüberbrückung (Y3/8y8)"));
        assertTrue(t, t.contains("0218 · Status 60 (aktuell vorhanden) – Getriebeöl zu heiß"));
        assertTrue(t, t.contains("0748 · Status 20 (gespeichert, derzeit nicht vorhanden) – Magnetventil Kupplung K1"));
        int oil = t.indexOf("Was jetzt – Getriebeöl"), valve = t.indexOf("Was jetzt – Schaltventil");
        assertTrue(t, oil > 0 && valve > oil);                                  // feste Reihenfolge, Öl zuerst
        assertEquals(t, valve, t.lastIndexOf("Was jetzt – Schaltventil"));      // je Gruppe einmal
        assertFalse(t, t.contains("Nur gespeichert"));                          // Ventil ist auch aktuell dabei
        assertTrue(t, t.contains("Kurztexte: eigene Fassung"));
        assertTrue(t, t.contains("Codes ohne Eintrag"));
    }

    @Test
    public void storedOnlyFaultsGetNoUrgentAdvice() {
        String t = KwpReadout.dtcs("58 01 02 18 20");
        assertFalse(t, t.contains("Was jetzt"));
        assertTrue(t, t.contains("Nur gespeichert, derzeit nicht vorhanden (Getriebeöl): kein akuter Handlungsbedarf"));
    }

    @Test
    public void spuriousCodeNeedsNoWorkshop() {
        String t = KwpReadout.dtcs("58 01 06 06 E0");
        assertTrue(t, t.contains("Was jetzt – Scheinfehler: Laut Tabelle ohne Bedeutung – kein Handlungsbedarf."));
        assertFalse(t, t.contains("Werkstatt"));
    }

    @Test
    public void emptyMemoryNeedsNoAdvice() {
        assertEquals("", Vgs7229Codes.advice(EnumSet.noneOf(Vgs7229Codes.Group.class)));
        assertFalse(KwpReadout.dtcs("58 00").contains("Was jetzt"));
        assertFalse(KwpReadout.dtcs("58 03").contains("Kurztexte"));             // gezählt, aber nichts gelistet
    }
}
