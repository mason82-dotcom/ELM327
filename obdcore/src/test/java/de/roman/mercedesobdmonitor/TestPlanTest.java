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

    @Test public void emptyPlanListsAllSteps() {
        String c = new TestPlan().checklist();
        assertTrue(c, c.startsWith("0 von 9 Schritten erledigt · 0 Ergebnisse gespeichert"));
        assertTrue(c, c.contains("○ ATF-Temperatur warm in P, R, N, D (0/4)"));
        assertTrue(c, c.contains("Kühlmittel unter 40\u00A0°C"));
    }

    @Test public void stepsUseTemperatureAndEngineState() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 85, 700, "ATF 70 °C"));          // warm zählt nicht als kalt
        p.record(rec(ObdSession.Op.ATF, NA, NA, "ohne Kühlmittel"));       // Temperatur unbekannt: weder noch
        p.record(rec(ObdSession.Op.MONITOR_TESTS, 50, 700, "kalt"));       // Mode 06 braucht warm
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 0, "Motor aus"));      // Bus mitlesen braucht Motor an
        List<TestPlan.Step> s = p.steps();
        assertEquals(0, s.get(0).done);   // kalt
        assertEquals(1, s.get(1).done);   // warm 1/4
        assertFalse(s.get(4).complete());
        assertFalse(s.get(5).complete());
        p.record(rec(ObdSession.Op.ATF, 20, 0, "kalt"));
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 650, "läuft"));
        for (int i = 0; i < 3; i++) p.record(rec(ObdSession.Op.ATF, 90, 700, "warm " + i));
        s = p.steps();
        assertTrue(s.get(0).complete());
        assertTrue(s.get(1).complete());
        assertEquals(4, s.get(1).done);
        assertTrue(s.get(4).complete());
        assertTrue(p.checklist(), p.checklist().contains("✓ ATF-Temperatur warm in P, R, N, D (4/4)"));
    }

    @Test public void allDoneSaysShare() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 10, 0, ""));
        for (int i = 0; i < 4; i++) p.record(rec(ObdSession.Op.ATF, 90, 700, ""));
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

    /** Schalten geht nur bei laufendem Motor: warme ATF-Lesungen mit stehendem Motor zählen nicht für P/R/N/D. */
    @Test public void warmShiftReadingsNeedRunningEngine() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.ATF, 90, 0, "Motor aus"));
        p.record(rec(ObdSession.Op.ATF, 90, NA, "Drehzahl unbekannt"));   // zählt (alte Einträge)
        p.record(rec(ObdSession.Op.ATF, 90, 720, "Leerlauf"));
        assertEquals(2, p.steps().get(1).done);
        assertTrue(p.checklist(), p.checklist().contains("Motor läuft im Leerlauf"));
    }
}
