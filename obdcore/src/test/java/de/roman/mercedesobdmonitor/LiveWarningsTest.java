package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiveWarningsTest {
    private final LiveWarnings w = new LiveWarnings();

    /** Speist alle Sekunde Drehzahl/Kühlmittel/Spannung ein und wertet aus; liefert die letzte Meldung. */
    private LiveWarnings.Warning run(long fromMs, long toMs, double rpm, double coolant, double volt) {
        LiveWarnings.Warning last = null;
        for (long t = fromMs; t <= toMs; t += 1000) {
            if (!Double.isNaN(rpm)) w.onSample(0x0C, rpm, t);
            if (!Double.isNaN(coolant)) w.onSample(0x05, coolant, t);
            if (!Double.isNaN(volt)) w.onSample(0x42, volt, t);
            last = w.evaluate(t);
        }
        return last;
    }

    @Test
    public void normalDriveGivesNothing() {
        // Werte wie im Log 19./20.11.: warm, 14,1–14,3 V
        assertNull(run(0, 120_000, 750, 92, 14.2));
    }

    @Test
    public void coolantNeedsHoldTimeAndHysteresis() {
        assertNull(run(0, 3_000, 750, 109, 14.2));          // noch nicht 5 s
        LiveWarnings.Warning x = run(4_000, 6_000, 750, 109, 14.2);
        assertEquals(LiveWarnings.Level.WARN, x.level);
        assertEquals("coolant", x.key);
        // 106 °C liegt unter der Warnschwelle, aber über der Rückschaltschwelle: bleibt
        assertEquals(LiveWarnings.Level.WARN, run(7_000, 30_000, 750, 106, 14.2).level);
        assertNull(run(31_000, 40_000, 750, 103, 14.2));
    }

    @Test
    public void coolantAlarmWinsOverWarning() {
        LiveWarnings.Warning x = run(0, 10_000, 750, 117, 14.2);
        assertEquals(LiveWarnings.Level.ALARM, x.level);
        assertTrue(x.text, x.text.contains("117 °C"));
    }

    @Test
    public void shortSpikeIsIgnored() {
        run(0, 2_000, 750, 116, 14.2);
        assertNull(run(3_000, 20_000, 750, 95, 14.2));
    }

    @Test
    public void weakBatteryAtKeyOnLikeLog20261120() {
        // Zündung an, Motor aus (01 0C = 0), 11,5 V wie im Log vom 20.11.2026
        LiveWarnings.Warning x = run(0, 12_000, 0, 15, 11.5);
        assertEquals("battery_low", x.key);
        assertEquals(LiveWarnings.Level.WARN, x.level);
    }

    @Test
    public void crankingDipAndStartPhaseDoNotWarn() {
        run(0, 5_000, 0, 15, 12.4);
        // Anlassen: 9,5 V bei 200 U/min – weder „Motor aus“ noch „läuft“
        assertNull(run(6_000, 8_000, 200, 15, 9.5));
        // Nach dem Start 12,8 V in den ersten Sekunden (Regler läuft an): keine Ladewarnung
        assertNull(run(9_000, 25_000, 900, 16, 12.8));
    }

    @Test
    public void chargingTooLowWhileRunning() {
        run(0, 25_000, 750, 80, 14.2);                     // läuft > 20 s
        assertNull(run(26_000, 35_000, 750, 80, 12.4));     // 10 s < 15 s Haltezeit
        LiveWarnings.Warning x = run(36_000, 45_000, 750, 80, 12.4);
        assertEquals("charge_low", x.key);
        assertEquals(LiveWarnings.Level.WARN, x.level);
        assertEquals(LiveWarnings.Level.ALARM, run(46_000, 55_000, 750, 80, 11.9).level);
    }

    /** WIS: Bei voller Batterie senkt das ME die Ladespannung im Leerlauf ab – das ist keine Störung. */
    @Test
    public void loweredChargingVoltageAtIdleIsNoWarning() {
        run(0, 25_000, 750, 80, 14.2);
        assertNull(run(26_000, 120_000, 750, 80, 12.8));
    }

    @Test
    public void overVoltageIsAlarm() {
        run(0, 25_000, 750, 80, 14.2);
        LiveWarnings.Warning x = run(26_000, 32_000, 750, 80, 15.4);
        assertEquals("charge_high", x.key);
        assertEquals(LiveWarnings.Level.ALARM, x.level);
    }

    @Test
    public void invalidOrStaleDataClearsWarning() {
        assertEquals(LiveWarnings.Level.ALARM, run(0, 10_000, 750, 117, 14.2).level);
        w.onInvalid(0x05);
        assertNull(w.evaluate(10_500));
        run(11_000, 20_000, 750, 117, 14.2);
        // keine neuen Werte mehr: nach 15 s (LiveValues.MAX_AGE_MS) gilt der alte nicht mehr
        assertEquals(LiveWarnings.Level.ALARM, w.evaluate(34_000).level);
        assertNull(w.evaluate(36_000));
        run(52_000, 60_000, 750, 117, 14.2);
        w.reset();
        assertNull(w.evaluate(60_100));
    }

    /** Eine einzelne Messung reicht nie, auch wenn die Uhr weiterläuft (Abfrage nur alle paar Sekunden). */
    @Test
    public void singleSampleNeverTriggersEvenWhenClockRuns() {
        w.onSample(0x0C, 750, 0);
        w.onSample(0x05, 120, 0);
        for (long t = 0; t <= 10_000; t += 1000) {
            w.onSample(0x0C, 750, t);
            assertNull("t=" + t, w.evaluate(t));
        }
        w.onSample(0x05, 120, 10_500);
        assertEquals(LiveWarnings.Level.ALARM, w.evaluate(10_500).level);
    }

    /** Review: Der Alarm fällt beim Abkühlen auf die Warnung zurück, statt bis 104 °C rot zu bleiben. */
    @Test
    public void coolantAlarmFallsBackToWarning() {
        assertEquals(LiveWarnings.Level.ALARM, run(0, 10_000, 750, 117, 14.2).level);
        assertEquals(LiveWarnings.Level.ALARM, run(11_000, 20_000, 750, 112, 14.2).level);
        LiveWarnings.Warning x = run(21_000, 30_000, 750, 109, 14.2);
        assertEquals(LiveWarnings.Level.WARN, x.level);
        assertTrue(x.text, x.text.contains("109 °C"));
    }
}
