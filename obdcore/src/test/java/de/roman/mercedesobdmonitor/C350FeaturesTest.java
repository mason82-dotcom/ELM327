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
        // Schub: ohne Soll-Lambda über Status 4 + Klappe zu, mit Soll-Lambda nur bei FFFF (C350-Log 07.11.2026)
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(12.0, null, 4, 0.0, 2500.0), 0);
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(12.0, 1.99997, 4, 0.0, 2500.0), 0);
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
        // ADR-003: 20 Bytes wie im EGS52-Layout; Temperatur = Index 13 ab 0x61 = 0x82 = 130 → 80 °C
        String raw = "014\r0: 61 30 00 0A 03 E8\r1: 00 64 06 08 01 01 12\r2: 82 00 C8 00 FA 00 00\r";
        AtfTemperature.Reading r = AtfTemperature.parse(raw);
        assertEquals(80.0, r.celsius, 1e-9);
        String t = r.text();
        assertTrue(t, t.contains("80 °C"));
        assertTrue(t, t.contains("Rohantwort: 61 30"));
        assertTrue(t, t.contains("am C350 bestätigt"));
        assertTrue(t, t.contains("[2] Wandler-Schlupfdrehzahl: 10"));
        assertTrue(t, t.contains("[4] Wandler-Drehzahl: 1000"));            // 16 Bit big-endian
        assertTrue(t, t.contains("[8] Wandlerzustand: 6 (EGS52: geschlossen)"));
        assertTrue(t, t.contains("[11] erkannter Gang: 1 (Gang 1)"));
        assertTrue(t, t.contains("Ist-/Zielgang: 12 → Ist 1, Ziel 2"));
        assertTrue(t, t.contains("Wählhebel N · Gang 1 → 2"));
        assertTrue(t, t.contains("[14] Motormoment: 200"));
        assertTrue(t, t.contains("[18] Abtriebsdrehzahl: 0"));
        assertFalse(t, t.contains("Schaltbits"));                            // Antwort endet vor Index 20
    }

    /** Die bis ADR-003 gelesene Stelle (Index 11) ist der Gang: eine 12-Byte-Antwort ergibt keine Temperatur mehr. */
    @Test
    public void atfOldIndexIsTheGearNotTheTemperature() {
        AtfTemperature.Reading r = AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 82");
        assertNull(r.celsius);
        assertTrue(r.problem, r.problem.contains("zu kurz"));
        assertTrue(r.text(), r.text().contains("[11] erkannter Gang: 130"));
        assertEquals("", AtfTemperature.layoutFields(new byte[0]));
        assertEquals("Gang 3", AtfTemperature.gear(3));
        assertNull(AtfTemperature.gear(42));
    }

    @Test
    public void atfInvalidAnswersAreNeverAValue() {
        assertNull(AtfTemperature.parse("NO DATA").celsius);
        assertNull(AtfTemperature.parse("7F 21 11").celsius);                          // service not supported
        assertTrue(AtfTemperature.parse("7F 21 11").problem.contains("lehnt"));
        assertNull(AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 00 00 01").celsius); // reported −49/−51 case
        assertNull(AtfTemperature.parse("61 30 00 00").celsius);                             // too short
        assertNull(AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 00 00 FF").celsius); // 205 °C: implausible
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
        for (String cmd : new String[]{"ATSH7E2", "ATSH7E3", "ATSH18DB33F1", "2131", "2101", "22F190", "1003", "2701",
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

    @Test
    public void profileAcceptsEmulatorAnswers() {
        // exact answers of the brix emulator (ATH1, ATS0)
        assertTrue(VehicleScan.supportedByEcu("SEARCHING...\r7E8064100BE3FA813\r", 0x41, 0x00).get("7E8").contains(0x0C));
        // Mode 06 without the base echo, PCI 05 proves the length
        TreeSet<Integer> m6 = VehicleScan.supportedByEcu("7E80546C0000001", 0x46, 0x00).get("7E8");
        assertTrue(m6.contains(0x01));
        assertTrue(m6.contains(0x02));
        assertTrue(m6.contains(0x20));
        TreeSet<Integer> m6b = VehicleScan.supportedByEcu("7E8054680008001", 0x46, 0x20).get("7E8");
        assertTrue(m6b.contains(0x21));
        assertTrue(m6b.contains(0x31));
        assertTrue(m6b.contains(0x40));
        // standard form with echo still works
        assertTrue(VehicleScan.supportedByEcu("7E8064600C0000001", 0x46, 0x00).get("7E8").contains(0x02));
    }

    @Test
    public void ecuNameFromIsoTpMultiFrame() {
        // ELM327 form with ATH0/ATS0: length line, then segment counters
        String raw = "017\r0: 490A0145434D\r1: 002D456E67696E\r2: 65436F6E74726F\r3: 6C000000000000\r";
        assertEquals(java.util.List.of("ECM-EngineControl"), VehicleInfo.ecuNames(raw, true));
    }

    @Test
    public void unknownHeaderAfterUnconfirmedCommandOnlyAllowsTheWayBack() {
        String unknown = CommandSafety.UNKNOWN_HEADER;
        assertFalse(CommandSafety.isAllowed("010C", unknown));
        assertFalse(CommandSafety.isAllowed("2130", unknown));
        assertTrue(CommandSafety.isAllowed("ATSH7DF", unknown));
        assertTrue(CommandSafety.isAllowed("ATZ", unknown));
        assertTrue(CommandSafety.changesHeader("atsh7e1"));
        assertFalse(CommandSafety.changesHeader("010C"));
    }

    @Test
    public void terminalCannotRunTheAdr001Sequence() {
        for (String cmd : new String[]{"ATSH7E1", "2130", "21 30", "ATSH7DF"}) assertFalse(cmd, CommandSafety.isAllowedInTerminal(cmd));
        assertTrue(CommandSafety.isAllowedInTerminal("010C"));
        assertTrue(CommandSafety.isAllowedInTerminal("ATZ"));
    }

    @Test
    public void reconnectResetsTheHeaderOnlyOn11BitCan() {
        assertTrue(LinkPolicy.reconnectInit("A6").contains("ATSH7DF"));
        assertTrue(LinkPolicy.reconnectInit("8").contains("ATSH7DF"));
        assertFalse(LinkPolicy.reconnectInit("7").contains("ATSH7DF"));   // 29-bit CAN
        assertFalse(LinkPolicy.reconnectInit("3").contains("ATSH7DF"));   // ISO 9141
        assertFalse(LinkPolicy.isCan11Bit(null));
    }

    @Test
    public void analysisPidsAreNotCoreAndThrottleIsPolledOften() {
        assertTrue(ObdPid.isCore(0x0C));
        assertFalse(ObdPid.isCore(0x15));
        // relative throttle feeds the fuel-cut detection: at least every second cycle
        assertTrue(PollSchedule.shouldPoll(0x45, 1, false) || PollSchedule.shouldPoll(0x45, 2, false));
        assertTrue(PollSchedule.shouldPoll(0x45, 3, false) || PollSchedule.shouldPoll(0x45, 4, false));
    }

    @Test
    public void atfPicksTheTransmissionAnswerAmongSeveral() {
        String raw = "7F 21 11\r61 30 00 00 00 00 00 00 00 00 00 00 00 78\r";
        // a rejection from another responder does not hide the valid answer
        assertEquals(70.0, AtfTemperature.parse(raw).celsius, 1e-9);
        assertEquals(70.0, AtfTemperature.parse("61 30 00 00 00 00 00 00 00 00 00 00 00 78").celsius, 1e-9);
    }

    /** ADR-003 am C350 bestätigt (21.11.2026): echte Antworten in P, R, N, D und in der Fahrt. */
    @Test
    public void atfLayoutConfirmedOnC350() {
        String[][] cases = {
            {"61 30 00 52 00 00 00 00 00 0A 01 00 DD 3A 00 09 00 08 00 00 04 01 00 06", "Wählhebel P · Gang P", "8 °C"},
            {"61 30 02 AA 00 00 00 00 00 09 01 00 BB 3B 00 1F 00 3B 00 00 00 01 00 00", "Wählhebel R · Gang R", "9 °C"},
            {"61 30 00 74 00 00 00 00 00 08 01 00 00 3B 00 0E 00 0D 00 00 04 01 00 06", "Wählhebel N · Gang N", "9 °C"},
            {"61 30 02 9E 02 DF 00 00 00 07 01 00 11 3B 00 1F 00 3B 00 00 00 01 00 00", "Wählhebel D · Gang 1", "9 °C"},
            {"61 30 01 56 00 2D 03 C8 01 07 01 02 32 70 00 A3 00 A2 02 7B 03 81 00 90", "Wählhebel D · Gang 3 → 2", "62 °C"},
        };
        for (String[] c : cases) {
            String t = AtfTemperature.parse(c[0]).text();
            assertTrue(t, t.contains(c[1]));
            assertTrue(t, t.startsWith("ATF-Temperatur: " + c[2]));
        }
        // Motormoment vorzeichenbehaftet: FF B3 = −77 bei stehendem Motor
        String off = AtfTemperature.parse("61 30 00 00 00 00 00 00 00 0A 01 00 DD 3A FF B3 FF 6D 00 00 04 01 00 06").text();
        assertTrue(off, off.contains("[14] Motormoment: -77"));
        assertTrue(off, off.contains("[9] Wählhebelstellung: 10 (P)"));
    }

    /** Review: zu einer ungültigen Antwort keine Klartextzeile Wählhebel/Gang. */
    @Test
    public void invalidAtfHasNoShiftSummary() {
        String t = AtfTemperature.parse("61 30 00 00 00 00 00 00 00 0A 01 00 DD 01 00 00 00 00 00 00").text();
        assertTrue(t, t.startsWith("ATF-Temperatur: kein gültiger Wert"));
        assertFalse(t, t.contains("Wählhebel P · Gang"));
    }
}
