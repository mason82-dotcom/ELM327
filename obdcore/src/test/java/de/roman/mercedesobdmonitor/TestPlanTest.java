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
        assertTrue(c, c.startsWith("0 von 6 Schritten erledigt · 0 Ergebnisse gespeichert"));
        assertFalse("ATF-Schritte seit ADR-003 erledigt", c.contains("ATF-Temperatur"));
        assertFalse(c, c.contains("P, R, N, D"));
    }

    @Test public void stepsUseTemperatureAndEngineState() {
        TestPlan p = new TestPlan();
        p.record(rec(ObdSession.Op.MONITOR_TESTS, 50, 700, "kalt"));       // Mode 06 braucht warm
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 0, "Motor aus"));      // Bus mitlesen braucht Motor an
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, NA, "unbekannt"));     // Drehzahl unbekannt zählt nicht
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 399, "zu langsam"));   // Grenze wie Warnungen: 400
        List<TestPlan.Step> s = p.steps();
        assertFalse(s.get(2).complete());
        assertFalse(s.get(3).complete());
        p.record(rec(ObdSession.Op.CAN_MONITOR, 80, 400, "läuft"));
        p.record(rec(ObdSession.Op.MONITOR_TESTS, 90, 700, "warm"));
        s = p.steps();
        assertTrue(s.get(2).complete());
        assertTrue(s.get(3).complete());
    }

    @Test public void allDoneSaysShare() {
        TestPlan p = new TestPlan();
        for (ObdSession.Op op : new ObdSession.Op[] {ObdSession.Op.KWP, ObdSession.Op.STARTER, ObdSession.Op.CAN_MONITOR,
                ObdSession.Op.MONITOR_TESTS, ObdSession.Op.MISFIRE, ObdSession.Op.DTC}) {
            p.record(rec(op, 90, 700, ""));
        }
        assertTrue(p.checklist(), p.checklist().startsWith("Alle 6 Schritte erledigt"));
        // alte Einträge (ATF, verworfene Kennungssuche) bleiben im Bericht lesbar, zählen aber für keinen Schritt
        p.record(rec(ObdSession.Op.LID_SCAN, 15, 0, ""));
        p.record(rec(ObdSession.Op.ATF, 90, 700, "ATF-Temperatur: 62 °C"));
        assertEquals(p.serialize(), TestPlan.parse(p.serialize()).serialize());
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

    @Test public void freshValueIgnoresOldSamples() {
        java.util.Map<Integer, Double> v = new java.util.HashMap<>();
        java.util.Map<Integer, Long> at = new java.util.HashMap<>();
        v.put(0x0C, 720.0);
        at.put(0x0C, 1_000L);
        assertEquals(720.0, LiveValues.fresh(v, at, 0x0C, 1_000L + LiveValues.MAX_AGE_MS, k -> PollSchedule.maxAgeMs(k, false)), 0);
        assertEquals(null, LiveValues.fresh(v, at, 0x0C, 1_001L + LiveValues.MAX_AGE_MS, k -> PollSchedule.maxAgeMs(k, false)));
        assertEquals(null, LiveValues.fresh(v, at, 0x05, 2_000L, k -> PollSchedule.maxAgeMs(k, false)));
    }
}
