package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReadinessTest {
    private static Readiness.Monitor find(Readiness r, String name) {
        for (Readiness.Monitor m : r.monitors) if (m.name.equals(name)) return m;
        return null;
    }

    @Test public void allCompleteMilOff() {
        // A=00, B=07 (3 kontinuierliche verfügbar, alle fertig), C=65 (Kat, EVAP, Lambda, Heizung), D=00
        Readiness r = Readiness.parse("41 01 00 07 65 00");
        assertNotNull(r);
        assertFalse(r.milOn);
        assertEquals(0, r.dtcCount);
        assertEquals(0, r.incompleteCount());
        assertEquals(7, r.monitors.size());
        assertTrue(find(r, "Katalysator").complete);
        assertTrue(find(r, "Lambdasondenheizung").complete);
    }

    @Test public void milOnWithIncompleteCatAndEvap() {
        // A=82 (MIL, 2 DTCs), D=05 → Kat + EVAP offen
        Readiness r = Readiness.parse("41 01 82 07 65 05");
        assertTrue(r.milOn);
        assertEquals(2, r.dtcCount);
        assertEquals(2, r.incompleteCount());
        assertFalse(find(r, "Katalysator").complete);
        assertFalse(find(r, "Tankentlüftung (EVAP)").complete);
        assertTrue(find(r, "Lambdasonden").complete);
    }

    @Test public void incompleteBitForUnsupportedMonitorIsIgnored() {
        // D=08 (Sekundärluft offen), aber C ohne Bit 3 → nicht unterstützt, nicht gelistet
        Readiness r = Readiness.parse("41 01 00 07 65 08");
        assertEquals(0, r.incompleteCount());
        assertNull(find(r, "Sekundärluft"));
    }

    @Test public void mergesMultipleEcus() {
        // Motor: MIL aus, alles fertig. Getriebe: 1 DTC, Komponenten offen (B Bit 6).
        Readiness r = Readiness.parse("41 01 00 07 65 00\r41 01 01 47 00 00");
        assertEquals(2, r.ecuCount);
        assertEquals(1, r.dtcCount);
        assertFalse(find(r, "Umfassende Komponenten").complete);
    }

    @Test public void noDataGivesNull() {
        assertNull(Readiness.parse("NO DATA"));
        assertNull(Readiness.parse(null));
    }
}
