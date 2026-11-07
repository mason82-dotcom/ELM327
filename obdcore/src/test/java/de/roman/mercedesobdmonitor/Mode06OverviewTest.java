package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Mode-06-Gesamtübersicht: Bewertung gegen Grenzwerte, Vorzeichen, Lesen über Bitmaps. */
public class Mode06OverviewTest {

    private static Mode06Overview.Test eval(int mid, int tid, int uasid, int value, int min, int max) {
        return Mode06Overview.evaluate(new Mode06.TestResult(mid, tid, uasid, value, min, max));
    }

    @Test public void maxOnlyWithinReserveIsOk() {
        Mode06Overview.Test t = eval(0x21, 0x91, 0x04, 450, 0, 500);
        assertEquals(Mode06Overview.Status.OK, t.status);
        assertEquals(Integer.valueOf(10), t.reservePercent);
        assertTrue(t.hasMax);
        assertFalse(t.hasMin);
    }

    @Test public void maxOnlyCloseToLimit() {
        Mode06Overview.Test t = eval(0x21, 0x91, 0x04, 460, 0, 500);
        assertEquals(Mode06Overview.Status.CLOSE, t.status);
        assertEquals(Integer.valueOf(8), t.reservePercent);
    }

    @Test public void maxOnlyAboveLimitFails() {
        assertEquals(Mode06Overview.Status.FAIL, eval(0x21, 0x91, 0x04, 510, 0, 500).status);
    }

    @Test public void limitIsInclusive() {
        assertEquals(Mode06Overview.Status.CLOSE, eval(0x21, 0x91, 0x04, 500, 0, 500).status);
    }

    @Test public void bothLimitsUseDistanceToNearerLimit() {
        assertEquals(Integer.valueOf(100), eval(0x01, 0x85, 0x0B, 200, 100, 300).reservePercent);
        Mode06Overview.Test near = eval(0x01, 0x85, 0x0B, 105, 100, 300);
        assertEquals(Integer.valueOf(5), near.reservePercent);
        assertEquals(Mode06Overview.Status.CLOSE, near.status);
        assertEquals(Mode06Overview.Status.FAIL, eval(0x01, 0x85, 0x0B, 99, 100, 300).status);
    }

    @Test public void minOnly() {
        Mode06Overview.Test t = eval(0x01, 0x08, 0x0B, 850, 750, 0xFFFF);
        assertEquals(Mode06Overview.Status.OK, t.status);
        assertEquals(Integer.valueOf(13), t.reservePercent);
        assertEquals(Mode06Overview.Status.FAIL, eval(0x01, 0x08, 0x0B, 700, 750, 0xFFFF).status);
    }

    @Test public void allZeroMeansNotRun() {
        Mode06Overview.Test t = eval(0x21, 0x91, 0x04, 0, 0, 0);
        assertEquals(Mode06Overview.Status.NOT_RUN, t.status);
        assertNull(t.reservePercent);
    }

    @Test public void valueWithoutLimitsIsInformational() {
        assertEquals(Mode06Overview.Status.NO_LIMIT, eval(0xA2, 0x0B, 0x24, 7, 0, 0).status);
        assertEquals(Mode06Overview.Status.NO_LIMIT, eval(0xA2, 0x0B, 0x24, 7, 0, 0xFFFF).status);
    }

    @Test public void signedUasidIsInterpretedWithSign() {
        // -150 bei Grenzen -100 … 100: vorzeichenlos gelesen läge 0xFF6A über 100 und
        // 0xFF9C/0x0064 ergäben unsinnige Grenzen.
        Mode06Overview.Test fail = eval(0x31, 0x90, 0x8B, 0xFF6A, 0xFF9C, 0x0064);
        assertEquals(-150, fail.value);
        assertEquals(-100, fail.min);
        assertEquals(Mode06Overview.Status.FAIL, fail.status);
        Mode06Overview.Test ok = eval(0x31, 0x90, 0x8B, 0x0000, 0xFF9C, 0x0064);
        assertEquals(Mode06Overview.Status.OK, ok.status);
        assertEquals(Integer.valueOf(100), ok.reservePercent);
    }

    @Test public void formatUsesUasidScaling() {
        assertEquals("0,450 V", Mode06Overview.format(450, 0x0B));
        assertEquals("60,0 °C", Mode06Overview.format(1000, 0x16));
        assertEquals("7 Zähler", Mode06Overview.format(7, 0x24));
        assertEquals("1234 (roh)", Mode06Overview.format(1234, 0xC7));
    }

    @Test public void names() {
        assertEquals("Katalysator Bank 2", Mode06Overview.midName(0x22));
        assertEquals("Sondenheizung Bank 1 Sonde 2", Mode06Overview.midName(0x42));
        assertEquals("Aussetzer Zylinder 6", Mode06Overview.midName(0xA7));
        assertEquals("Test 0x91 (herstellerspezifisch)", Mode06Overview.tidName(0x91));
        assertEquals("maximale Sondenspannung", Mode06Overview.tidName(0x08));
    }

    private static final class Fake implements InspectionReader.Transport {
        final Map<String, String> answers = new HashMap<>();
        final List<String> sent = new ArrayList<>();

        @Override public String send(String cmd, int timeoutMs) throws IOException {
            sent.add(cmd);
            String a = answers.getOrDefault(cmd, "NO DATA");
            if ("TIMEOUT".equals(a)) throw new SocketTimeoutException(cmd);
            return a;
        }

        @Override public boolean resync() { return true; }
    }

    private static Fake car() {
        Fake f = new Fake();
        f.answers.put("0600", "46 00 80 00 00 01");      // MID 01 + Bitmap 20
        f.answers.put("0620", "46 20 80 00 00 00");      // MID 21, keine weitere Bitmap
        f.answers.put("0601", "46 01 01 0B 03 52 00 00 03 E8"); // 850 ≤ 1000 mV-Stufen
        f.answers.put("0621", "46 21 91 04 01 CC 00 00 01 F4"); // 460 ≤ 500 → knapp
        return f;
    }

    @Test public void readFollowsBitmapsAndQueriesEachMonitor() throws IOException {
        Fake f = car();
        List<Mode06Overview.Test> tests = Mode06Overview.read(f);
        assertEquals(List.of("0600", "0620", "0601", "0621"), f.sent);
        assertEquals(2, tests.size());
        String report = Mode06Overview.report(tests);
        assertTrue(report, report.contains("2 Tests: 0 außerhalb · 1 knapp · 1 in Ordnung"));
        assertTrue(report, report.contains("⚠ Katalysator Bank 1 · Test 0x91 (herstellerspezifisch): 0,460 (≤ 0,500) · Reserve 8 %"));
        assertTrue(report, report.contains("Katalysator (Bank 1 = Zyl. 1–3"));
    }

    @Test public void singleTimeoutSkipsMonitorAndContinues() throws IOException {
        Fake f = car();
        f.answers.put("0601", "TIMEOUT");
        List<Mode06Overview.Test> tests = Mode06Overview.read(f);
        assertEquals(1, tests.size());
        assertEquals(0x21, tests.get(0).mid);
    }

    @Test public void noMode06Support() throws IOException {
        Fake f = new Fake();
        assertTrue(Mode06Overview.read(f).isEmpty());
        assertTrue(Mode06Overview.report(new ArrayList<>()).contains("keine Mode-06-Testergebnisse"));
    }
}
