package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** Warmlauf-/Thermostat-Check mit simulierten Fahrten (1 Tick pro Sekunde). */
public class WarmupCheckTest {
    private final WarmupCheck check = new WarmupCheck();
    private final Map<Integer, Double> values = new HashMap<>();
    private long now = 1_000_000;

    /** Fährt {@code seconds} lang; die Temperatur ändert sich linear von from nach to. */
    private void drive(int seconds, double speed, double from, double to) {
        for (int i = 0; i < seconds; i++) {
            values.put(0x0D, speed);
            values.put(0x05, from + (to - from) * i / Math.max(1, seconds - 1));
            now += 1000;
            check.tick(values, now);
        }
    }

    private void startCold(double coolant) {
        values.put(0x05, coolant);
        values.put(0x0F, 12.0);
        values.put(0x0D, 0.0);
        check.start(now);
    }

    @Test public void normalWarmupAndHeldTemperatureIsOk() {
        startCold(20);
        drive(12 * 60, 50, 20, 82);   // 80 °C nach knapp 12 min
        drive(3 * 60, 50, 82, 95);
        drive(6 * 60, 90, 95, 97);    // zügige Fahrt, Temperatur hält
        assertEquals(WarmupCheck.Verdict.OK, check.verdict());
        String report = check.finish(now);
        assertTrue(report, report.startsWith("✅ Warmlauf unauffällig"));
        assertTrue(report, report.contains("Kein Abfall bei zügiger Fahrt"));
        assertTrue(report, report.contains("(Kaltstart)"));
        assertFalse(check.isRunning());
    }

    @Test public void dropWhileCruisingIsSuspicious() {
        startCold(20);
        drive(10 * 60, 50, 20, 88);   // betriebswarm
        drive(60, 100, 88, 79);       // Autobahn: fällt unter 80 °C …
        drive(3 * 60, 100, 79, 74);   // … und bleibt 3 min darunter
        assertEquals(WarmupCheck.Verdict.SUSPICIOUS, check.verdict());
        String report = check.finish(now);
        assertTrue(report, report.contains("offen hängendes Thermostat"));
        assertTrue(report, report.contains("bis 74 °C"));
    }

    @Test public void shortDipBelow80IsTolerated() {
        startCold(20);
        drive(10 * 60, 50, 20, 88);
        drive(60, 100, 88, 79);
        drive(60, 100, 79, 79);       // nur ~1 min unter 80 °C
        drive(6 * 60, 100, 85, 90);
        assertEquals(WarmupCheck.Verdict.OK, check.verdict());
    }

    @Test public void neverReaching80AfterLongDriveIsSuspicious() {
        startCold(15);
        drive(25 * 60, 50, 15, 72);
        assertEquals(WarmupCheck.Verdict.SUSPICIOUS, check.verdict());
        assertTrue(check.finish(now).contains("keine 80 °C erreicht"));
    }

    @Test public void slowButEventualWarmupIsSuspicious() {
        startCold(10);
        drive(24 * 60, 50, 10, 79);
        drive(60, 50, 79, 81);        // 80 °C erst nach ~25 min Fahrt
        assertEquals(WarmupCheck.Verdict.SUSPICIOUS, check.verdict());
    }

    @Test public void shortTripIsInconclusive() {
        startCold(20);
        drive(5 * 60, 40, 20, 55);
        assertEquals(WarmupCheck.Verdict.INCONCLUSIVE, check.verdict());
        assertTrue(check.finish(now).contains("Fahrt zu kurz"));
    }

    @Test public void warmStartNeedsCruiseForAStatement() {
        startCold(90);
        drive(2 * 60, 100, 90, 92);
        assertEquals(WarmupCheck.Verdict.INCONCLUSIVE, check.verdict());
        drive(4 * 60, 100, 92, 93);
        assertEquals(WarmupCheck.Verdict.OK, check.verdict());
        assertTrue(check.finish(now).contains("(kein Kaltstart)"));
    }

    @Test public void gapsAreNotCountedAsDrivingTime() {
        startCold(20);
        values.put(0x0D, 50.0);
        now += 1000;
        check.tick(values, now);
        now += 60_000;                // Reconnect-Lücke
        check.tick(values, now);
        now += 1000;
        check.tick(values, now);
        assertTrue(check.finish(now).contains("davon Fahrt 0:02 min"));
    }

    @Test public void statusLineAndIdleBehaviour() {
        assertNull(check.tick(values, now));
        assertNull(check.finish(now));
        startCold(20);
        drive(90, 30, 20, 30);
        String status = check.tick(values, now);
        assertTrue(status, status.startsWith("Warmlauf: 30 °C · 1:30 min seit Start"));
        check.cancel();
        assertFalse(check.isRunning());
    }
}
