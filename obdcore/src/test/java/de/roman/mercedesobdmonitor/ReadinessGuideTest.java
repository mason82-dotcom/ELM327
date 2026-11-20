package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ReadinessGuideTest {
    /** 41 01: MIL aus, 0 DTC, Funken-Motor; Kat (Bit 0) und Sekundärluft (Bit 3) unterstützt und offen. */
    @Test
    public void openMonitorsGetTipsAndLampLogic() {
        Readiness r = Readiness.parse("41 01 00 07 09 09");
        String s = ReadinessGuide.section(r, false);
        assertTrue(s, s.contains("FAHRZYKLUS-HILFE (ME9.7)"));
        assertTrue(s, s.contains("• Katalysator: betriebswarm"));
        assertTrue(s, s.contains("• Sekundärluft: läuft nur direkt nach einem Kaltstart"));
        assertTrue(s, s.contains("aus nach drei fehlerfreien Fahrzyklen"));
        assertTrue(s, s.contains("nach 40, katschädigende nach 80"));
    }

    @Test
    public void nothingOpenAndLampOffGivesNoSection() {
        Readiness r = Readiness.parse("41 01 00 07 09 00");
        assertNull(ReadinessGuide.section(r, false));
        String withPending = ReadinessGuide.section(r, true);
        assertTrue(withPending, withPending.contains("Motorkontrollleuchte"));
        assertFalse(withPending, withPending.contains("Offen:"));
    }
}
