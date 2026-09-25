package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class FuelTrimTestTest {
    private static Map<Integer, Double> values(double rpm, double speed) {
        Map<Integer, Double> v = new HashMap<>();
        v.put(0x0C, rpm);
        v.put(0x0D, speed);
        v.put(0x06, 1.0);
        v.put(0x07, 12.0);
        v.put(0x08, 0.5);
        v.put(0x09, 6.0);
        v.put(0x10, 4.0);
        v.put(0x0B, 33.0);
        return v;
    }

    private static Map<Integer, Long> seq(long n) {
        Map<Integer, Long> s = new HashMap<>();
        s.put(0x06, n);
        s.put(0x07, n);
        s.put(0x08, n);
        s.put(0x09, n);
        return s;
    }

    @Test public void duplicateTelemetryDoesNotCompleteIdlePhase() {
        FuelTrimTest test = new FuelTrimTest();
        Map<Integer, Double> v = values(700, 0);
        Map<Integer, Long> s = seq(1);

        test.start();
        test.tick(v, s, 1_000);
        test.tick(v, s, 4_000); // enters COLLECT_IDLE; baseline = sequence 1

        FuelTrimTest.Update u = test.tick(v, s, 25_000);
        assertFalse(u.prompt2500);
        assertTrue(test.getStage() == FuelTrimTest.Stage.COLLECT_IDLE);
    }

    @Test public void movingVehicleInvalidatesMeasurement() {
        FuelTrimTest test = new FuelTrimTest();
        Map<Integer, Double> v = values(700, 0);

        test.start();
        test.tick(v, seq(1), 1_000);
        test.tick(v, seq(1), 4_000);

        v.put(0x0D, 8.0);
        FuelTrimTest.Update u = test.tick(v, seq(2), 5_000);
        assertTrue(u.status.contains("Fahrzeugstand"));
        assertTrue(test.getStage() == FuelTrimTest.Stage.WAIT_IDLE);
    }

    @Test public void completesWithFreshTrimSamplesInBothPhases() {
        FuelTrimTest test = new FuelTrimTest();
        Map<Integer, Double> v = values(700, 0);

        test.start();
        test.tick(v, seq(1), 1_000);
        test.tick(v, seq(1), 4_000); // collect idle baseline
        test.tick(v, seq(2), 5_000);
        test.tick(v, seq(3), 6_000);
        test.tick(v, seq(4), 7_000);
        FuelTrimTest.Update idleDone = test.tick(v, seq(5), 24_100);
        assertTrue(idleDone.prompt2500);

        v.put(0x0C, 2_500.0);
        test.tick(v, seq(5), 25_000);
        test.tick(v, seq(5), 28_000); // collect high baseline
        test.tick(v, seq(6), 29_000);
        test.tick(v, seq(7), 30_000);
        test.tick(v, seq(8), 31_000);
        FuelTrimTest.Update done = test.tick(v, seq(9), 43_100);

        assertTrue(done.completed);
        assertTrue(done.report.contains("Passiver Fuel-Trim-Test"));
        assertTrue(done.report.contains("Gesamt B1"));
    }
}
