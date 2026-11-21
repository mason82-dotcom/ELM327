package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StarterTestTest {
    private static final long STEP = 80;

    /** Spielt eine Spannungskurve ab: Paare {Dauer ms, Spannung}, Abtastung alle {@link #STEP} ms. */
    private static long play(StarterTest t, long start, double[][] segments) {
        long now = start;
        for (double[] seg : segments) {
            long end = now + (long) seg[0];
            while (now < end && !t.finished()) {
                t.offer(now, seg[1]);
                now += STEP;
            }
        }
        return now;
    }

    private static StarterTest healthyStart(double ambient, double crankV, long crankMs, double chargeV) {
        StarterTest t = new StarterTest(ambient);
        play(t, 1000, new double[][] {
                {3500, 12.5},      // Ruhe
                {2000, 12.4},      // Zündung an, Kraftstoffpumpe
                {crankMs, crankV}, // Anlassen
                {400, 12.3},       // Erholung
                {21_000, chargeV}, // Ladung
        });
        return t;
    }

    @Test
    public void healthyBatteryAndCharging() {
        StarterTest t = healthyStart(8, 10.4, 900, 14.1);
        assertEquals(StarterTest.Phase.DONE, t.phase());
        assertEquals(12.5, t.rest(), 0.001);
        assertEquals(10.4, t.crankMin(), 0.001);
        assertEquals(14.1, t.charge(), 0.001);
        assertTrue("Anlassdauer " + t.crankMs(), Math.abs(t.crankMs() - 960) <= STEP);
        String r = t.report(720);
        assertTrue(r, r.contains("Ruhespannung (Zündung an, Motor aus): 12,5 V – gut geladen"));
        assertTrue(r, r.contains("tiefster Wert 10,4 V (Einbruch 2,0 V) – gut"));
        assertTrue(r, r.contains("Richtwert ≥ 9,3 V bei 8 °C Außentemperatur"));
        assertTrue(r, r.contains("Lichtmaschine lädt"));
        assertTrue(r, r.contains("720 U/min"));
        assertTrue(r, r.contains("Gesamt: Batterie, Anlassen und Ladung unauffällig."));
    }

    @Test
    public void deepDropWithGoodRestPointsToCapacityOrCables() {
        StarterTest t = healthyStart(21, 9.2, 1200, 14.2);
        String r = t.report(700);
        assertTrue(r, r.contains("zu tief"));
        assertTrue(r, r.contains("Übergangswiderstand"));
    }

    @Test
    public void lowRestAndDeepDropMeansDischarged() {
        StarterTest t = new StarterTest(Double.NaN);
        play(t, 0, new double[][] {{3500, 11.9}, {2000, 11.9}, {1500, 8.9}, {400, 11.8}, {21_000, 14.0}});
        assertEquals(StarterTest.Phase.DONE, t.phase());
        String r = t.report(Double.NaN);
        assertTrue(r, r.contains("niedrig (entladen)"));
        assertTrue(r, r.contains("21 °C angenommen"));
        assertTrue(r, r.contains("erst vollständig laden"));
        assertTrue(r, r.contains("Drehzahl nicht gelesen"));
    }

    @Test
    public void noChargingAfterStart() {
        StarterTest t = healthyStart(15, 10.5, 800, 12.6);
        String r = t.report(750);
        assertTrue(r, r.contains("lädt nicht ausreichend"));
        assertTrue(r, r.contains("Riemen, Regler"));
    }

    @Test
    public void overchargeIsFlagged() {
        String r = healthyStart(15, 10.5, 800, 15.6).report(750);
        assertTrue(r, r.contains("Regler der Lichtmaschine"));
    }

    @Test
    public void temperatureShiftsCrankLimit() {
        assertEquals(9.6, StarterTest.crankLimit(25), 0);
        assertEquals(9.6, StarterTest.crankLimit(Double.NaN), 0);
        assertEquals(9.4, StarterTest.crankLimit(10), 0);
        assertEquals(9.1, StarterTest.crankLimit(0), 0);
        assertEquals(8.5, StarterTest.crankLimit(-20), 0);
        // 9,3 V ist bei 25 °C zu tief, bei −5 °C noch gut
        assertEquals(StarterTest.Rating.WEAK, StarterTest.rateCrank(9.3, 25));
        assertEquals(StarterTest.Rating.GOOD, StarterTest.rateCrank(9.3, -5));
    }

    @Test
    public void runningEngineIsRejected() {
        StarterTest t = new StarterTest(10);
        play(t, 0, new double[][] {{4000, 14.1}});
        assertEquals(StarterTest.Phase.FAILED, t.phase());
        assertTrue(t.failure(), t.failure().contains("läuft der Motor schon"));
    }

    @Test
    public void crankingDuringRestMeasurementIsRejected() {
        StarterTest t = new StarterTest(10);
        play(t, 0, new double[][] {{1500, 12.5}, {600, 10.0}, {2000, 12.5}});
        assertEquals(StarterTest.Phase.FAILED, t.phase());
        assertTrue(t.failure(), t.failure().contains("zu früh gestartet"));
    }

    @Test
    public void noCrankTimesOut() {
        StarterTest t = new StarterTest(10);
        play(t, 0, new double[][] {{3500 + StarterTest.WAIT_MS + 500, 12.5}});
        assertEquals(StarterTest.Phase.FAILED, t.phase());
        assertTrue(t.failure().contains("Kein Startvorgang"));
        assertTrue(t.report(Double.NaN), t.report(Double.NaN).contains("Ruhespannung (Zündung an, Motor aus): 12,5 V"));
    }

    @Test
    public void tickTimesOutWithoutSamples() {
        StarterTest t = new StarterTest(10);
        long now = play(t, 0, new double[][] {{3500, 12.5}});
        assertEquals(StarterTest.Phase.WAIT_CRANK, t.phase());
        t.tick(now + StarterTest.MAX_SILENCE_MS);
        assertEquals(StarterTest.Phase.FAILED, t.phase());
        assertTrue(t.failure(), t.failure().contains("Keine lesbare Spannung"));
    }

    @Test
    public void unreadableAnswersEndTheTest() {
        StarterTest t = new StarterTest(10);
        long now = play(t, 0, new double[][] {{3500, 12.5}});
        for (int i = 0; i < 50 && !t.finished(); i++) t.offer(now + i * STEP, Double.NaN);
        assertEquals(StarterTest.Phase.FAILED, t.phase());
    }

    @Test
    public void slowRecoveryWithinCrankIsOneCrank() {
        // Kompressionstakte: Spannung schwankt beim Anlassen, ein einzelner Wert nahe am Bezug beendet es nicht
        StarterTest t = new StarterTest(15);
        play(t, 0, new double[][] {{3500, 12.5}, {2000, 12.5}, {400, 10.2}, {80, 12.3}, {600, 10.6}, {400, 12.4},
                {21_000, 14.0}});
        assertEquals(StarterTest.Phase.DONE, t.phase());
        assertTrue("Anlassdauer " + t.crankMs(), t.crankMs() >= 1000);
    }

    @Test
    public void missedDipStillMeasuresCharging() {
        StarterTest t = new StarterTest(15);
        play(t, 0, new double[][] {{3500, 12.5}, {2000, 12.5}, {21_000, 14.2}});
        assertEquals(StarterTest.Phase.DONE, t.phase());
        String r = t.report(700);
        assertTrue(r, r.contains("Einbruch nicht erfasst"));
        assertTrue(r, r.contains("Lichtmaschine lädt"));
        assertFalse(r, r.contains("zu tief"));
    }

    @Test
    public void abortWhileCrankingIsAFinding() {
        StarterTest t = new StarterTest(5);
        play(t, 0, new double[][] {{3500, 12.4}, {2000, 12.4}, {160, 9.0}});
        assertEquals(StarterTest.Phase.CRANK, t.phase());
        t.abort("Adapter antwortet nicht mehr.", true);
        assertTrue(t.abortedWhileCranking());
        String r = t.report(Double.NaN);
        assertTrue(r, r.contains("setzen unter etwa 8–9 V zurück"));
        assertTrue(r, r.contains("tiefster Wert 9,0 V"));
    }

    @Test
    public void parsesAdapterAnswers() {
        assertEquals(12.4, StarterTest.parseVolts("12.4V"), 1e-9);
        assertEquals(12.4, StarterTest.parseVolts("ATRV\r12.4V\r\r>"), 1e-9);
        assertEquals(9.85, StarterTest.parseVolts("9.85 V"), 1e-9);
        assertEquals(14.7, StarterTest.parseVolts("14,7V"), 1e-9);
        assertTrue(Double.isNaN(StarterTest.parseVolts("?")));
        assertTrue(Double.isNaN(StarterTest.parseVolts(null)));
        assertTrue(Double.isNaN(StarterTest.parseVolts("STOPPED")));
    }

    @Test
    public void statusTellsWhenToStart() {
        StarterTest t = new StarterTest(10);
        assertTrue(t.status(0).contains("Motor noch nicht starten"));
        long now = play(t, 0, new double[][] {{3500, 12.5}});
        assertTrue(t.status(now), t.status(now).contains("JETZT MOTOR STARTEN"));
    }

    /** Das Dashboard zeigt Fortschrittsmeldungen am Präfix „Starttest: “ im Ergebnisfeld an. */
    @Test public void statusTextsCarryPrefix() {
        StarterTest t = new StarterTest(Double.NaN);
        long now = 1000;
        assertTrue(t.status(now).startsWith("Starttest: "));
        for (int i = 0; i < 8; i++) t.offer(now + i * 500L, 12.5);
        assertEquals(StarterTest.Phase.WAIT_CRANK, t.phase());
        assertTrue(t.status(now + 4000).startsWith("Starttest: JETZT MOTOR STARTEN"));
        t.abort("Test", false);
        assertTrue(t.status(now + 5000).startsWith("Starttest: "));
    }

    @Test
    public void userCancelWhileCrankingIsNoFinding() {
        StarterTest t = new StarterTest(5);
        play(t, 0, new double[][] {{3500, 12.4}, {2000, 12.4}, {160, 9.0}});
        t.abort("vom Benutzer abgebrochen.", false);
        assertFalse(t.abortedWhileCranking());
        assertFalse(t.report(Double.NaN).contains("setzen unter etwa 8–9 V zurück"));
    }

    /** Ein Einbruch unter 5 V ist der wichtigste Befund und darf nicht als ungültige Probe verschwinden. */
    @Test
    public void veryDeepDipIsMeasured() {
        StarterTest t = healthyStart(20, 4.5, 1200, 14.2);
        assertEquals(StarterTest.Phase.DONE, t.phase());
        assertEquals(4.5, t.crankMin(), 1e-9);
        String r = t.report(700);
        assertTrue(r, r.contains("tiefster Wert 4,5 V"));
        assertTrue(r, r.contains("zu tief"));
        // in Ruhe bleibt ein solcher Wert unplausibel
        StarterTest idle = new StarterTest(20);
        idle.offer(0, 4.5);
        assertEquals(StarterTest.Phase.REST, idle.phase());
    }

    /** Motor läuft, Lichtmaschine lädt nicht: Anlassen endet normal, Befund ist die Ladung, nicht „sehr lang“. */
    @Test
    public void nonChargingAlternatorEndsCrank() {
        StarterTest t = new StarterTest(15);
        play(t, 0, new double[][] {{3500, 12.5}, {2000, 12.5}, {1000, 9.8}, {22_000, 12.0}});
        assertEquals(StarterTest.Phase.DONE, t.phase());
        assertTrue("Anlassdauer " + t.crankMs(), t.crankMs() >= 900 && t.crankMs() <= 1200);
        String r = t.report(700);
        assertFalse(r, r.contains("sehr lang"));
        assertTrue(r, r.contains("lädt nicht ausreichend"));
    }

    /** E: Herstellerwerte – Batterietest erst ab 12,4 V, Startgrenze ~7 V, Ladung bis 15 V normal. */
    @Test
    public void manufacturerValuesInReport() {
        StarterTest t = new StarterTest(9);
        play(t, 1000, new double[][] {{3500, 11.6}, {2000, 11.5}, {900, 7.6}, {400, 11.8}, {21_000, 14.9}});
        String r = t.report(700);
        assertTrue(r, r.contains("Nahe der Startgrenze: unter etwa 7,0 V"));
        assertTrue(r, r.contains("auch der Werkstatt-Batterietest braucht über 12,4 V Ruhespannung"));
        assertTrue(r, r.contains("Lichtmaschine lädt"));          // 14,9 V: bis 15 V normal (WIS)
        assertEquals(StarterTest.Rating.GOOD, StarterTest.rateCharge(15.0));
        assertEquals(StarterTest.Rating.FAIR, StarterTest.rateCharge(15.1));
    }

    /** Review: Ruhespannung knapp (12,3 V) bleibt „knapp“, kein eigener Handlungspunkt. */
    @Test
    public void fairRestStaysBorderline() {
        StarterTest t = new StarterTest(15);
        play(t, 1000, new double[][] {{3500, 12.3}, {2000, 12.2}, {900, 10.6}, {400, 12.0}, {21_000, 14.2}});
        String r = t.report(700);
        assertTrue(r, r.contains("Gesamt: unauffällig, einzelne Werte knapp"));
    }

    /** Fahrzeugtest 21.11.2026: Ruhe 10,6 V, Anlassen 6,9 V, Ladung zuerst 11 V, kurz 13,5 V. */
    @Test
    public void deeplyDischargedBatteryDoesNotBlameAlternator() {
        StarterTest t = new StarterTest(5);
        play(t, 1000, new double[][] {{3500, 10.6}, {2000, 10.6}, {1200, 6.9}, {400, 10.8},
                {3000, 13.5}, {18_000, 11.1}});
        String r = t.report(1140);
        assertEquals(11.1, t.charge(), 0.001);
        assertTrue(r, r.contains("bei tief entladener Batterie üblich"));
        assertFalse(r, r.contains("Lichtmaschine lädt nicht ausreichend"));
    }

    /** Ladespannung zählt erst am Ende des Fensters (Lichtmaschine fährt verzögert hoch). */
    @Test
    public void chargeUsesLastSecondsOfWindow() {
        StarterTest t = new StarterTest(15);
        play(t, 1000, new double[][] {{3500, 12.5}, {2000, 12.4}, {900, 10.6}, {400, 12.3},
                {12_000, 12.6}, {9_000, 14.2}});
        assertEquals(14.2, t.charge(), 0.001);
    }

    /** Review: eine einzelne Spitze direkt nach dem Anlassen entlastet die Lichtmaschine nicht. */
    @Test
    public void singleSpikeDoesNotHideNonChargingAlternator() {
        StarterTest t = new StarterTest(10);
        play(t, 1000, new double[][] {{3500, 11.9}, {2000, 11.8}, {900, 9.5}, {400, 11.5},
                {160, 13.4}, {21_000, 11.6}});
        String r = t.report(720);
        assertTrue(r, r.contains("Lichtmaschine lädt nicht ausreichend"));
    }

    /** Abgleich 21.11.2026: Adapter 10,6/11,1 V, Steuergerät 11,5/14,4 V → nach dem Steuergerät bewerten. */
    @Test
    public void inaccurateAdapterIsCorrectedByEcuVoltage() {
        StarterTest t = new StarterTest(5);
        play(t, 1000, new double[][] {{3500, 10.6}, {2000, 10.6}, {1200, 6.9}, {400, 10.8}, {21_000, 11.1}});
        t.setEcuRest(11.497);
        t.setEcuCharge(14.41);
        assertTrue(t.adapterInaccurate());
        String r = t.report(1140);
        assertTrue(r, r.contains("Ruhespannung (Zündung an, Motor aus): 11,7 V (aus Motorsteuergerät 01 42 + 0,2 V) – niedrig"));
        assertTrue(r, r.contains("Ladespannung nach dem Start: 14,6 V (aus Motorsteuergerät 01 42 + 0,2 V) – Lichtmaschine lädt"));
        assertTrue(r, r.contains("Ruhe 10,6 / 11,5 V (-0,9 V) · Ladung 11,1 / 14,4 V (-3,3 V)"));
        assertTrue(r, r.contains("Adapter misst ungenau"));
        assertTrue(r, r.contains("Adapter tiefster Wert 6,9 V, geschätzt 8,0 V an der Batterie"));
        assertFalse(r, r.contains("Lichtmaschine lädt nicht"));
        assertTrue(r, r.contains("Batterie entladen oder verschlissen"));   // Ruhe und geschätzter Einbruch schwach
    }

    /** Review: Ladung misst nicht gleichzeitig – 0,9 V Unterschied beim Hochfahren ist noch kein ungenauer Adapter. */
    @Test
    public void rampingAlternatorIsNotAnInaccurateAdapter() {
        StarterTest t = healthyStart(8, 10.4, 900, 13.6);
        t.setEcuRest(12.3);
        t.setEcuCharge(14.3);
        assertFalse(t.adapterInaccurate());
    }

    /** Review: ohne Ruhe-Abgleich ist der Einbruch nicht schätzbar – das Gesamturteil sagt das. */
    @Test
    public void crankNotRatedWithoutRestReferenceIsReported() {
        StarterTest t = healthyStart(8, 10.4, 900, 11.0);
        t.setEcuCharge(14.3);
        assertTrue(t.adapterInaccurate());
        String r = t.report(720);
        assertTrue(r, r.contains("nicht bewertbar"));
        assertTrue(r, r.contains("Einbruch beim Anlassen nicht bewertbar"));
        assertFalse(r, r.contains("unauffällig."));
    }

    @Test
    public void matchingAdapterKeepsAdapterValues() {
        StarterTest t = healthyStart(8, 10.4, 900, 14.1);
        t.setEcuRest(12.3);
        t.setEcuCharge(14.0);
        assertFalse(t.adapterInaccurate());
        String r = t.report(720);
        assertTrue(r, r.contains("Ruhespannung (Zündung an, Motor aus): 12,5 V – gut geladen"));
        assertTrue(r, r.contains("stimmt überein"));
    }

    @Test
    public void withoutEcuVoltageNoCheckLine() {
        String r = healthyStart(8, 10.4, 900, 14.1).report(720);
        assertFalse(r, r.contains("Abgleich"));
    }
}
