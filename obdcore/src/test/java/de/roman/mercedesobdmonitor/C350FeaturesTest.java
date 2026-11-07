package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.TreeSet;
import org.junit.Test;

/** C350/M272 features: new PIDs, analyses, vehicle profile, ATF temperature and the ADR-001 safety gate. */
public class C350FeaturesTest {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("PID missing: " + id);
    }

    // ------------------------------------------------------------------ new standard PIDs (SAE J1979)

    @Test
    public void newPidFormulas() {
        assertEquals(11.5, pid(0x0E).parse("41 0E 97"), 1e-9);                 // 0x97/2 − 64
        assertEquals(0.75, pid(0x15).parse("41 15 96 FF"), 1e-9);              // 150/200 V
        assertEquals(1.0, pid(0x24).parse("41 24 80 00 80 00"), 1e-6);        // 0x8000·2/65536
        assertEquals(560.0, pid(0x3C).parse("41 3C 17 70"), 1e-9);             // 6000/10 − 40
        assertEquals(100.0, pid(0x43).parse("41 43 00 FF"), 1e-9);
        assertEquals(754.0, pid(0x1F).parse("41 1F 02 F2"), 1e-9);
        assertEquals(2.0, pid(0x03).parse("41 03 02 00"), 1e-9);
        assertEquals("geregelt (Lambda)", pid(0x03).format(2));
        assertEquals("offen (Last/Schub)", ObdPid.fuelSystemText(4));
    }

    @Test
    public void pidNumbersAreUnique() {
        TreeSet<Integer> seen = new TreeSet<>();
        for (ObdPid p : ObdPid.defaultPids()) assertTrue("duplicate PID " + p.pid, seen.add(p.pid));
    }

    // ------------------------------------------------------------------ analyses

    @Test
    public void fuelConsumptionFromMaf() {
        // 10 g/s air at λ=1 → 10/14.7 g/s fuel → ·3600/745 = 3.287 l/h
        assertEquals(3.287, EngineAnalysis.fuelRateLitersPerHour(10.0, 1.0, 2, 10.0, 800.0), 0.001);
        // enrichment λ 0.85 burns more fuel
        assertTrue(EngineAnalysis.fuelRateLitersPerHour(10.0, 0.85, 4, 60.0, 4000.0) > 3.287);
        // overrun fuel cut: status 4, throttle closed, rpm above idle → 0
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(12.0, 1.0, 4, 0.0, 2500.0), 0);
        assertNull(EngineAnalysis.fuelRateLitersPerHour(null, 1.0, 2, 5.0, 800.0));
        assertEquals(6.574, EngineAnalysis.fuelPer100Km(3.287, 50.0), 0.001);
        assertNull("standing: no l/100 km", EngineAnalysis.fuelPer100Km(1.0, 0.0));
    }

    @Test
    public void volumetricEfficiencyAndVerdict() {
        // idle-like: 100 kPa, 20 °C, 3000 rpm, theoretical ≈ 1.189 kg/m³·0.003498·25·1000 = 103.9 g/s
        Double eta = EngineAnalysis.volumetricEfficiency(88.0, 100.0, 20.0, 3000.0);
        assertEquals(0.847, eta, 0.01);
        assertEquals("unauffällig", EngineAnalysis.volumetricVerdict(eta));
        assertTrue(EngineAnalysis.volumetricVerdict(0.4).startsWith("auffällig niedrig"));
        assertNull(EngineAnalysis.volumetricEfficiency(10.0, 100.0, 20.0, 0.0));
    }

    @Test
    public void lambdaQualityOnlyInClosedLoop() {
        assertNull(EngineAnalysis.lambdaDeviation(1.05, 1.0, 4));
        assertEquals(0.03, EngineAnalysis.lambdaDeviation(1.03, 1.0, 2), 1e-9);
        assertTrue(EngineAnalysis.lambdaVerdict(0.03, 0.0).contains("Bank 1 weicht ab"));
        assertTrue(EngineAnalysis.lambdaVerdict(0.01, 0.005).startsWith("unauffällig"));
    }

    @Test
    public void catalystTendencyNeedsWarmClosedLoopSamples() {
        EngineAnalysis.CatTendency good = new EngineAnalysis.CatTendency();
        EngineAnalysis.CatTendency weak = new EngineAnalysis.CatTendency();
        for (int i = 0; i < 60; i++) {
            good.add(0.70 + (i % 2) * 0.02, 2, 90.0);              // calm, rich post-cat signal
            weak.add(i % 2 == 0 ? 0.1 : 0.85, 2, 90.0);             // switching like the pre-cat sensor
            good.add(0.1, 4, 90.0);                                 // open loop: ignored
            good.add(0.1, 2, 40.0);                                 // cold: ignored
        }
        assertEquals(60, good.samples());
        assertTrue(good.verdict(), good.verdict().endsWith("Kat glättet gut"));
        assertTrue(weak.verdict(), weak.verdict().contains("glättet kaum"));
        EngineAnalysis.CatTendency few = new EngineAnalysis.CatTendency();
        few.add(0.7, 2, 90.0);
        assertTrue(few.verdict().startsWith("sammelt Werte"));
    }

    @Test
    public void electricalAndEgas() {
        assertTrue(EngineAnalysis.voltageVerdict(12.6, 800.0).startsWith("niedrig"));
        assertEquals("Ladespannung unauffällig", EngineAnalysis.voltageVerdict(14.1, 800.0));
        assertEquals("Batterie schwach geladen", EngineAnalysis.voltageVerdict(12.0, 0.0));
        assertEquals(2.0, EngineAnalysis.pedalRatio(40.0, 20.0), 1e-9);
        assertNull("pedal at rest: no ratio", EngineAnalysis.pedalRatio(2.0, 1.0));
        assertEquals(-2.0, EngineAnalysis.throttleTracking(20.0, 18.0), 1e-9);
    }

    // ------------------------------------------------------------------ vehicle profile

    @Test
    public void profileSeparatesEngineAndTransmission() {
        String raw = "7E8064100BE3FA813\r7E9064100980000010\r";
        Map<String, TreeSet<Integer>> m = VehicleScan.supportedByEcu(raw, 0x41, 0x00);
        assertTrue(m.get("7E8").contains(0x0C));
        assertTrue(m.get("7E8").contains(0x20));       // next block announced
        assertTrue(m.get("7E9").contains(0x01));
        assertFalse(m.get("7E9").contains(0x0C));
        assertTrue(VehicleScan.anyHasNext(m, 0x00));
        // adapter without headers still works (one anonymous ECU)
        assertTrue(VehicleScan.supportedByEcu("41 00 BE 3F A8 13", 0x41, 0x00).get("?").contains(0x0C));
    }

    @Test
    public void profileReportNamesSensorsAndKeepsRawAnswers() {
        VehicleScan.Result r = new VehicleScan.Result();
        r.merge(r.mode01, VehicleScan.supportedByEcu("7E8064120B005B011", 0x41, 0x20));
        r.merge(r.mode06, VehicleScan.supportedByEcu("7E8064600C0000001", 0x46, 0x00));
        r.raw.put("0120", "7E8064120B005B011");
        String report = VehicleScan.report(r);
        assertTrue(report, report.contains("Motor (7E8)"));
        assertTrue(report, report.contains("24 Lambda B1S1 (Breitband)"));
        assertTrue(report, report.contains("Lambdasonden vorn: Breitband"));
        assertTrue(report, report.contains("0120 → 7E8064120B005B011"));
        assertTrue(VehicleScan.report(new VehicleScan.Result()).startsWith("Kein Steuergerät"));
    }

    // ------------------------------------------------------------------ ATF temperature (ADR-001)

    @Test
    public void atfTemperatureFromMultiFrameAnswer() {
        // byte[11] = 0x82 = 130 → 80 °C
        String raw = "01A\r0: 61 30 00 00 00 00\r1: 00 00 00 00 00 82 00\r2: 00 00 00 00 00 00 00\r";
        AtfTemperature.Reading r = AtfTemperature.parse(raw);
        assertEquals(80.0, r.celsius, 1e-9);
        assertTrue(r.text().contains("80 °C"));
        assertTrue(r.text().contains("Rohantwort: 61 30"));
    }

    @Test
    public void atfInvalidAnswersAreNeverAValue() {
        assertNull(AtfTemperature.parse("NO DATA").celsius);
        assertNull(AtfTemperature.parse("7F 21 11").celsius);                          // service not supported
        assertTrue(AtfTemperature.parse("7F 21 11").problem.contains("lehnt"));
        assertNull(AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 01").celsius); // reported −49/−51 case
        assertNull(AtfTemperature.parse("61 30 00 00").celsius);                       // too short
        assertNull(AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 FF").celsius); // 205 °C: implausible
    }

    @Test
    public void adr001AllowsOnlyTheExactSequenceInTheRightHeaderState() {
        String obd = CommandSafety.DEFAULT_HEADER;
        assertFalse("2130 never with the functional header", CommandSafety.isAllowed("2130", obd));
        assertTrue(CommandSafety.isAllowed("ATSH7E1", obd));
        String tcu = CommandSafety.headerAfter("ATSH7E1", obd);
        assertEquals("7E1", tcu);
        assertTrue(CommandSafety.isAllowed("21 30", tcu));
        assertFalse("no live polling to the transmission", CommandSafety.isAllowed("010C", tcu));
        assertFalse(CommandSafety.isAllowed("03", tcu));
        assertTrue(CommandSafety.isAllowed("ATSH7DF", tcu));
        assertEquals(obd, CommandSafety.headerAfter("ATSH7DF", tcu));
        assertEquals(obd, CommandSafety.headerAfter("ATZ", tcu));
        // everything else stays blocked
        for (String cmd : new String[]{"ATSH7E0", "ATSH7E2", "ATSH18DB33F1", "2131", "2101", "22F190", "1003", "2701",
                "3101", "2E0001", "14FF00", "1101", "ATCRA7E9", "ATCRA", "2130;0101"}) {
            assertFalse(cmd, CommandSafety.isAllowed(cmd));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, "7E1"));
        }
    }

    @Test
    public void ecuNameFromMode09() {
        // "ECM" 0x00 "-EngineControl" padded to 20 bytes
        String raw = "49 0A 01 45 43 4D 00 2D 45 6E 67 69 6E 65 43 6F 6E 74 72 6F 6C 00 00";
        assertEquals(java.util.List.of("ECM-EngineControl"), VehicleInfo.ecuNames(raw, true));
        assertTrue(VehicleInfo.ecuNames("NO DATA", true).isEmpty());
        assertNotNull(VehicleInfo.ecuNames(null, true));
    }
}
