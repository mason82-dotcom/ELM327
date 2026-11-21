package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class TestPlanTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final double NA = Double.NaN;

    private static TestPlan.Record rec(ObdSession.Op op, double coolant, double rpm, String text) {
        return new TestPlan.Record(op, 1_795_000_000_000L, coolant, rpm, text);
    }

    /** ATF-Bericht wie AtfTemperature ihn schreibt, mit Wählhebel-Rohwert [9]. */
    private static String atf(int selector) {
        return "ATF-Temperatur: 70 °C\nRohantwort: 61 30 …\n• [9] Wählhebelstellung: " + selector + "\n";
    }

    @Test public void emptyPlanListsAllSteps() {
        String c = new TestPlan().checklist();
        assertTrue(c, c.startsWith("0 von 9 Schritten erledigt · 0 Ergebnisse gespeichert"));
        assertTrue(c, c.contains("○ " + TestPlan.SHIFT_STEP + " (0/4)"));
        assertTrue(c, c.contains("Kühlmittel unter 40\u00A0°C"));
    }

    @Test public void stepsUseTemperatureAndEngineState() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 85, 700, atf(0)));               // warm zählt nicht als kalt
        p.record(rec(ObdSession.Op.ATF, NA, NA, atf(1)));                // Temperatur unbekannt: weder noch
        p.record(rec(ObdSession.Op.MONITOR_TESTS, 50, 700, "kalt"));       // Mode 06 braucht warm
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 0, "Motor aus"));      // Bus mitlesen braucht Motor an
        List<TestPlan.Step> s = p.steps();
        assertEquals(0, s.get(0).done);   // kalt
        assertEquals(1, s.get(1).done);   // warm 1/4
        assertFalse(s.get(4).complete());
        assertFalse(s.get(5).complete());
        p.record(rec(ObdSession.Op.ATF, 20, 0, atf(0)));
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 650, "läuft"));
        for (int i = 1; i <= 3; i++) p.record(rec(ObdSession.Op.ATF, 90, 700, atf(i)));
        s = p.steps();
        assertTrue(s.get(0).complete());
        assertTrue(s.get(1).complete());
        assertEquals(4, s.get(1).done);
        assertTrue(s.get(4).complete());
        assertTrue(p.checklist(), p.checklist().contains("✓ " + TestPlan.SHIFT_STEP + " (4/4)"));
    }

    @Test public void allDoneSaysShare() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 10, 0, atf(0)));
        for (int i = 0; i < 4; i++) p.record(rec(ObdSession.Op.ATF, 90, 700, atf(i)));
        for (ObdSession.Op op : new ObdSession.Op[] {ObdSession.Op.KWP, ObdSession.Op.STARTER, ObdSession.Op.CAN_MONITOR,
                ObdSession.Op.MONITOR_TESTS, ObdSession.Op.MISFIRE, ObdSession.Op.DTC}) {
            p.record(rec(op, 90, 700, ""));
        }
        assertFalse(p.checklist(), p.checklist().startsWith("Alle"));
        p.record(rec(ObdSession.Op.LID_SCAN, 90, 700, ""));                // nur warm: 1/2
        assertTrue(p.checklist(), p.checklist().contains("○ Kennungssuche Motor kalt und warm (1/2)"));
        p.record(rec(ObdSession.Op.LID_SCAN, 15, 0, ""));
        assertTrue(p.checklist(), p.checklist().startsWith("Alle 9 Schritte erledigt"));
    }

    @Test public void reportCarriesEveryResult() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 85.4, 712, "ATF-Temperatur: 71 °C\nRohantwort: 61 30 …"));
        String r = p.report(BERLIN, "Monitor 1.10.0");
        assertTrue(r, r.startsWith("Prüfplan C350 (S204/M272) · Monitor 1.10.0"));
        assertTrue(r, r.contains("· 1 Ergebnis gespeichert"));
        assertTrue(r, r.contains("==== ATF-Temperatur · "));
        assertTrue(r, r.contains("· Kühlmittel 85 °C · 712 U/min ===="));
        assertTrue(r, r.contains("Rohantwort: 61 30 …"));
    }

    @Test public void serializeRoundTripKeepsAwkwardLines() {
        TestPlan p = new TestPlan();
        String tricky = "#### ENDE\n\\Backslash\n# Kopf\nnormal\n\n";
        p.record(rec(ObdSession.Op.KWP, 80, NA, tricky));
        p.record(rec(ObdSession.Op.DTC, NA, 650, "kein Fehler"));
        TestPlan back = TestPlan.parse(p.serialize() + "#### KAPUTT\nmüll\n#### ENDE\n");
        assertEquals(2, back.records().size());
        assertEquals(tricky, back.records().get(0).report);
        assertTrue(Double.isNaN(back.records().get(0).rpm));
        assertTrue(Double.isNaN(back.records().get(1).coolantC));
        assertEquals(p.serialize(), back.serialize());
        assertTrue(TestPlan.parse(null).isEmpty());
    }

    @Test public void keepsOnlyTheNewest() {
        TestPlan p = new TestPlan();
        for (int i = 0; i < TestPlan.MAX_RECORDS + 5; i++) p.record(rec(ObdSession.Op.DTC, 80, 700, "n" + i));
        assertEquals(TestPlan.MAX_RECORDS, p.records().size());
        assertEquals("n5", p.records().get(0).report);
    }

    /** Geprüft wird das Layout: laufender Motor, Feld [9] vorhanden, je Stellung einmal – Temperatur egal. */
    @Test public void shiftCheckCountsDistinctSelectorPositionsWithRunningEngine() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 90, 0, atf(1)));                  // Motor aus
        p.record(rec(ObdSession.Op.ATF, 90, NA, atf(2)));                 // Drehzahl unbekannt
        p.record(rec(ObdSession.Op.ATF, 90, 399, atf(3)));                // unter 400 U/min (wie Warnungen)
        p.record(rec(ObdSession.Op.ATF, 90, 720,
                "ATF-Temperatur: kein gültiger Wert (lehnt ab)\nRohantwort: 7F 21 12"));   // Absage, kein Feld [9]
        p.record(rec(ObdSession.Op.ATF, 90, 720, "ATF-Temperatur: 71 °C\nRohantwort: 61 30 00"));   // zu kurz, kein [9]
        assertEquals(0, p.steps().get(1).done);
        p.record(rec(ObdSession.Op.ATF, 90, 400, atf(0)));
        p.record(rec(ObdSession.Op.ATF, 90, 720, atf(0)));                // gleiche Stellung nochmal
        assertEquals(1, p.steps().get(1).done);
        // unplausible Temperatur, aber Feld [9] da: zählt (es geht um das Layout)
        p.record(rec(ObdSession.Op.ATF, 30, 720,
                "ATF-Temperatur: kein gültiger Wert (205 °C)\n• [9] Wählhebelstellung: 4\n"));
        assertEquals(2, p.steps().get(1).done);
        assertTrue(p.checklist(), p.checklist().contains("Wählhebel [9] und Gang [11] der Stellung folgen"));
        // Bus mitlesen: gleiche Grenze
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 399, "zu langsam"));
        assertFalse(p.steps().get(4).complete());
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 400, "läuft"));
        assertTrue(p.steps().get(4).complete());
    }

    @Test public void freshValueIgnoresOldSamples() {
        java.util.Map<Integer, Double> v = new java.util.HashMap<>();
        java.util.Map<Integer, Long> at = new java.util.HashMap<>();
        v.put(0x0C, 720.0);
        at.put(0x0C, 1_000L);
        assertEquals(720.0, LiveValues.fresh(v, at, 0x0C, 1_000L + LiveValues.MAX_AGE_MS), 0);
        assertEquals(null, LiveValues.fresh(v, at, 0x0C, 1_001L + LiveValues.MAX_AGE_MS));
        assertEquals(null, LiveValues.fresh(v, at, 0x05, 2_000L));
    }
}
