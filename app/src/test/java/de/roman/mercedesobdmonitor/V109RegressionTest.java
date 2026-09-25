package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** Regressionstests für die sechs Review-Punkte zu v1.0.8. */
public class V109RegressionTest {

    // Readiness: MIL aus, 0 DTCs, Funken, alle unterstützten Monitore komplett.
    // A=00, B=07 (Misfire/Fuel/Comp unterstützt+komplett), C=65 (Kat, EVAP, O2, O2-Heizung), D=00
    private static final String READY_ALL_COMPLETE = "41 01 00 07 65 00";
    // wie oben, aber MIL aus und 2 bestätigte DTCs laut ECU (A = 0x02)
    private static final String READY_TWO_DTCS = "41 01 02 07 65 00";

    private static InspectionCheck.Input cleanInput() {
        InspectionCheck.Input in = new InspectionCheck.Input();
        in.readiness = Readiness.parse(READY_ALL_COMPLETE);
        in.permanentSupported = true;
        return in;
    }

    // ---------- 1. readiness.dtcCount in der HU/AU-Entscheidung ----------

    @Test public void baselineIsReady() {
        assertNotNull(cleanInput().readiness);
        assertEquals(0, cleanInput().readiness.dtcCount);
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(cleanInput()));
    }

    @Test public void dtcCountFromReadinessBlocksEvenWithoutMode03Codes() {
        InspectionCheck.Input in = cleanInput();
        in.readiness = Readiness.parse(READY_TWO_DTCS);
        assertEquals(2, in.readiness.dtcCount);
        assertFalse(in.readiness.milOn);
        assertEquals(InspectionCheck.Verdict.NOT_READY, InspectionCheck.verdict(in));
        String rep = InspectionCheck.report(in);
        assertTrue(rep, rep.contains("meldet 2 bestätigte"));
    }

    // ---------- 2. Pending-DTC → mindestens UNCERTAIN ----------

    @Test public void pendingOnlyIsUncertainNotReady() {
        InspectionCheck.Input in = cleanInput();
        in.pending = new ArrayList<>(Arrays.asList("P0171"));
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
    }

    @Test public void pendingDoesNotDowngradeNotReady() {
        InspectionCheck.Input in = cleanInput();
        in.pending = new ArrayList<>(Arrays.asList("P0171"));
        in.stored = new ArrayList<>(Arrays.asList("P0300"));
        assertEquals(InspectionCheck.Verdict.NOT_READY, InspectionCheck.verdict(in));
    }

    // ---------- 3. neutrale Reset-/Diagnosezustandsformulierung ----------

    @Test public void recentResetUsesNeutralWording() {
        InspectionCheck.Input in = cleanInput();
        in.kmSinceCleared = 12;
        in.warmupsSinceCleared = 2;
        assertTrue(InspectionCheck.recentReset(in));
        String rep = InspectionCheck.report(in);
        assertTrue(rep, rep.contains("Diagnosespeicher wurde vor kurzem zurückgesetzt (12 km, 2 Warmläufe)"));
        assertTrue(rep, rep.contains("Batterietrennung"));
        assertTrue(rep, rep.contains("Seit dem letzten Rücksetzen des Diagnosespeichers"));
        assertFalse(rep, rep.contains("kürzlich gelöscht"));
        assertFalse(rep, rep.contains("Prüfstellen erkennen"));
    }

    @Test public void noResetNoteWithHighCounters() {
        InspectionCheck.Input in = cleanInput();
        in.kmSinceCleared = 5000;
        in.warmupsSinceCleared = 200;
        assertFalse(InspectionCheck.recentReset(in));
    }

    // ---------- 4. optionale HU/AU-Abfragen einzeln fehlertolerant ----------

    /** Fake-Adapter: Antworten pro Befehl; "TIMEOUT" = SocketTimeoutException. */
    private static final class FakeTransport implements InspectionReader.Transport {
        final Map<String, String> answers = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        boolean resyncOk = true;

        @Override public String send(String cmd, int timeoutMs) throws IOException {
            sent.add(cmd);
            String a = answers.getOrDefault(cmd, "NO DATA");
            if ("TIMEOUT".equals(a)) throw new SocketTimeoutException("Timeout " + cmd);
            return a;
        }

        @Override public boolean resync() { return resyncOk; }
    }

    private static FakeTransport healthyCar() {
        FakeTransport t = new FakeTransport();
        t.answers.put("0101", READY_ALL_COMPLETE);
        t.answers.put("03", "43 00");
        t.answers.put("07", "47 00");
        t.answers.put("0A", "4A 00");
        t.answers.put("0121", "41 21 00 00");
        t.answers.put("0130", "41 30 FF");
        t.answers.put("0131", "41 31 13 88");
        t.answers.put("020200", "42 02 00 00 00");
        return t;
    }

    @Test public void healthyCarReadsReady() throws IOException {
        InspectionCheck.Input in = new InspectionReader(healthyCar(), true).read(p -> true);
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in));
        assertEquals(Integer.valueOf(5000), in.kmSinceCleared);
        assertTrue(in.unavailable.isEmpty());
    }

    @Test public void singleOptionalTimeoutIsSkippedAndCheckContinues() throws IOException {
        FakeTransport t = healthyCar();
        t.answers.put("0121", "TIMEOUT");
        InspectionReader reader = new InspectionReader(t, true);
        InspectionCheck.Input in = reader.read(p -> true);

        assertNull(in.kmWithMil);
        assertEquals(Integer.valueOf(5000), in.kmSinceCleared); // nach dem Timeout weitergelesen
        assertTrue(t.sent.contains("020200"));
        assertEquals(1, reader.timeoutCount());
        assertTrue(in.unavailable.toString(), in.unavailable.contains("Strecke mit MIL (01 21)"));
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in));
        assertTrue(InspectionCheck.report(in).contains("Nicht verfügbar: Strecke mit MIL (01 21)"));
    }

    @Test public void twoSeparateOptionalTimeoutsAreTolerated() throws IOException {
        FakeTransport t = healthyCar();
        t.answers.put("0121", "TIMEOUT");
        t.answers.put("0130", "TIMEOUT"); // 2 in Folge < 3
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> true);
        assertEquals(Integer.valueOf(5000), in.kmSinceCleared);
        assertEquals(2, in.unavailable.size());
    }

    @Test public void failedMandatoryDtcReadMakesVerdictUncertain() throws IOException {
        FakeTransport t = healthyCar();
        t.answers.put("07", "TIMEOUT");
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> true);
        assertFalse(in.pendingKnown);
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
        assertTrue(InspectionCheck.report(in).contains("Pending: nicht lesbar"));
    }

    @Test public void readinessTimeoutStillEvaluatesStoredCodes() throws IOException {
        FakeTransport t = healthyCar();
        t.answers.put("0101", "TIMEOUT");
        t.answers.put("03", "43 01 03 00");  // P0300
        InspectionCheck.Input in = new InspectionReader(t, true).read(p -> true);
        assertNull(in.readiness);
        assertEquals(Arrays.asList("P0300"), in.stored);
        assertEquals(InspectionCheck.Verdict.NOT_READY, InspectionCheck.verdict(in));
    }

    @Test(expected = SocketTimeoutException.class)
    public void lostSyncAbortsWholeCheck() throws IOException {
        FakeTransport t = healthyCar();
        t.answers.put("0121", "TIMEOUT");
        t.resyncOk = false;
        new InspectionReader(t, true).read(p -> true);
    }

    @Test public void threeConsecutiveTimeoutsAbort() {
        FakeTransport t = healthyCar();
        t.answers.put("0121", "TIMEOUT");
        t.answers.put("0130", "TIMEOUT");
        t.answers.put("0131", "TIMEOUT");
        try {
            new InspectionReader(t, true).read(p -> true);
            fail("erwartet Abbruch");
        } catch (SocketTimeoutException expected) {
            assertFalse(t.sent.contains("020200"));
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    // ---------- 5. Mode-06-Misfire nur bei UASID 0x24 ----------

    @Test public void misfireAcceptsUasid24() {
        Mode06.Misfire m = Mode06.misfire("46 A2 0B 24 00 05 00 00 FF FF", 1);
        assertNotNull(m);
        assertEquals(Integer.valueOf(5), m.ewma);
    }

    @Test public void misfireRejectsOtherUasid() {
        // Gleiche MID/TID, aber UASID 0x01 (Rohwert) – darf nicht als Aussetzerzahl gelten.
        assertNull(Mode06.misfire("46 A2 0B 01 00 05 00 00 FF FF", 1));
    }

    @Test public void misfireMixedUasidKeepsOnlyCounts() {
        // EWMA mit UASID 0x01 (verworfen), aktueller Zyklus mit 0x24 (übernommen), ISO-TP
        String raw = "013\r0:46A20B010005\r1:0000FFFFA20C24\r2:00120000FFFF";
        Mode06.Misfire m = Mode06.misfire(raw, 1);
        assertNotNull(m);
        assertNull(m.ewma);
        assertEquals(Integer.valueOf(18), m.currentCycle);
    }

    // ---------- 6. 4A00 = keine permanenten DTCs ----------

    @Test public void permanentEmptyCanResponse() {
        assertTrue(ObdParser.isEmptyDtcResponse("4A 00", 0x4A, true));
        assertTrue(ObdParser.dtcs("4A 00", 0x4A, true).isEmpty());
    }

    @Test public void permanentEmptyAutoDetectAndMultiEcu() {
        assertTrue(ObdParser.isEmptyDtcResponse("4A00", 0x4A, null));
        assertTrue(ObdParser.isEmptyDtcResponse("4A 00\r4A 00", 0x4A, true));
        assertTrue(ObdParser.isEmptyDtcResponse("4A 00 00 00 00 00 00", 0x4A, false));
    }

    @Test public void permanentWithCodeIsNotEmpty() {
        assertFalse(ObdParser.isEmptyDtcResponse("4A 01 01 71", 0x4A, true));
    }

    @Test public void noDataIsNotAnEmptyValidResponse() {
        assertFalse(ObdParser.isEmptyDtcResponse("NO DATA", 0x4A, true));
        assertFalse(ObdParser.hasDtcResponse("NO DATA", 0x4A, true));
    }

    @Test public void inspectionTreats4A00AsSupportedAndEmpty() throws IOException {
        InspectionCheck.Input in = new InspectionReader(healthyCar(), true).read(p -> true);
        assertTrue(in.permanentSupported);
        assertTrue(in.permanent.isEmpty());
        assertTrue(InspectionCheck.report(in).contains("Permanent: keine"));
    }
}
