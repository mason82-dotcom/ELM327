package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Erkenntnisse aus dem ersten echten C350-Log (07.11.2026, 2 Fahrten, 37 min): Rohantworten des ME9.7 als
 * Regressionstests. Die Testressource enthält nur Zeitstempel, PID und Rohantwort ausgewählter Mode-01-PIDs.
 */
public class C350LogTest {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("PID missing: " + id);
    }

    // ------------------------------------------------------------------ λ-Rohwert FFFF = Schub

    @Test
    public void commandedLambdaFfffIsOverrun() {
        Double lambda = pid(0x44).parse("4144FFFF");
        assertNotNull(lambda);
        assertTrue(EngineAnalysis.isLambdaSaturated(lambda));
        assertEquals("FFFF (Schub/Messbereichsende)", pid(0x44).format(lambda));
        // früher: λ 2 → halber Verbrauch statt 0
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(5.11, lambda, 4, 1.18, 1370.0), 0);
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(5.11, lambda, null, 1.18, 1370.0), 0);
        // FFFF bei geregeltem Status ist kein Schub (z. B. „nicht verfügbar“ bei anderen Steuergeräten)
        assertFalse(EngineAnalysis.isOverrun(lambda, 2, 1.18, 1370.0));
        assertEquals("1,000 λ", pid(0x44).format(pid(0x44).parse("41448000")));
    }

    @Test
    public void widebandFfffIsOutOfRange() {
        Double lambda = pid(0x34).parse("4134FFFF8241");
        assertTrue(EngineAnalysis.isLambdaSaturated(lambda));
        assertEquals("> 2 λ (Schub/Messbereich)", pid(0x34).format(lambda));
        assertFalse(EngineAnalysis.isLambdaSaturated(pid(0x34).parse("413479987FCD")));   // 0,950 λ
    }

    @Test
    public void closedThrottleOnC350IsAboveOnePercent() {
        Double closed = pid(0x45).parse("414504");                                       // 1,57 %, Klappe zu
        assertEquals(1.57, closed, 0.01);
        // ohne Soll-Lambda: Klappe zu + Status 4 + über Leerlauf
        assertTrue(EngineAnalysis.isOverrun(null, 4, closed, 1533.0));
        assertEquals(0.0, EngineAnalysis.fuelRateLitersPerHour(6.19, null, 4, closed, 1533.0), 0);
        // gültiges Soll-Lambda hat Vorrang: Übergang/Wiedereinsetzen, es wird eingespritzt
        assertFalse(EngineAnalysis.isOverrun(1.0, 4, closed, 1533.0));
        assertTrue(EngineAnalysis.fuelRateLitersPerHour(6.19, 1.0, 4, closed, 1533.0) > 0);
        // Volllast-Anreicherung (ebenfalls Status 4) ist kein Schub
        assertFalse(EngineAnalysis.isOverrun(null, 4, 26.7, 2393.0));
        // Leerlauf mit Status 4 ist kein Schub
        assertFalse(EngineAnalysis.isOverrun(null, 4, 1.18, 680.0));
        assertNull(EngineAnalysis.fuelRateLitersPerHour(null, 2.0, 4, 1.0, 1500.0));
    }

    // ------------------------------------------------------------------ Trim-Karte

    private static void feed(EngineAnalysis.TrimMap map, int n, double tot1, double tot2, double rpm, double speed, double load) {
        for (int i = 0; i < n; i++) map.add(0.0, tot1, 0.0, tot2, 2, 90.0, rpm, speed, load);
    }

    @Test
    public void trimMapBands() {
        assertEquals(EngineAnalysis.TrimMap.Band.IDLE, EngineAnalysis.TrimMap.band(680.0, 0.0, 26.0));
        assertEquals(EngineAnalysis.TrimMap.Band.PART, EngineAnalysis.TrimMap.band(1500.0, 50.0, 30.0));
        assertEquals(EngineAnalysis.TrimMap.Band.HIGH, EngineAnalysis.TrimMap.band(2400.0, 90.0, 70.0));
        assertNull(EngineAnalysis.TrimMap.band(null, 0.0, 20.0));
        // Stand mit erhöhter Drehzahl (Hochdrehen in P/N) ist weder Leerlauf noch Last
        assertNull(EngineAnalysis.TrimMap.band(1500.0, 0.0, 20.0));
    }

    @Test
    public void trimMapIgnoresOpenLoopAndCold() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        for (int i = 0; i < 50; i++) {
            map.add(0.0, 12.0, 0.0, 4.0, 4, 90.0, 680.0, 0.0, 25.0);   // offen
            map.add(0.0, 12.0, 0.0, 4.0, 2, 60.0, 680.0, 0.0, 25.0);   // kalt
            map.add(null, 12.0, 0.0, 4.0, 2, 90.0, 680.0, 0.0, 25.0);  // fehlender Wert
        }
        assertEquals(0, map.samples(EngineAnalysis.TrimMap.Band.IDLE));
        assertEquals("sammelt Werte (nur geregelt und warm)", map.verdict());
    }

    @Test
    public void trimMapBankSpecificPattern() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        // Mittelwerte der Wiedergabe des echten Logs (Leerlauf / Teillast / hohe Last)
        feed(map, 30, 9.3, 2.5, 680, 0, 26);
        feed(map, 30, 12.0, 3.8, 1500, 50, 30);
        feed(map, 30, 9.5, 3.8, 2100, 90, 60);
        String v = map.verdict();
        assertTrue(v, v.startsWith("Bank 1 wird in Leerlauf und unter Last stärker angefettet"));
        assertFalse(v, v.contains("Falschluft"));
        assertTrue(v, v.endsWith("(Richtwert, keine Diagnose)"));
        assertTrue(map.table(), map.table().contains("Leerlauf: B1 +9,3 % · B2 +2,5 % (n 30)"));
    }

    @Test
    public void trimMapVacuumLeakPattern() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        feed(map, 30, 16.0, 15.0, 680, 0, 25);
        feed(map, 30, 4.0, 3.0, 1800, 60, 35);
        String v = map.verdict();
        assertTrue(v, v.contains("passt zu Falschluft"));
        assertFalse(v, v.contains("bankspezifisch"));
    }

    @Test
    public void trimMapUnremarkable() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        feed(map, 30, 2.0, 1.0, 680, 0, 25);
        feed(map, 30, -1.0, 0.5, 1800, 60, 35);
        assertEquals("unauffällig (innerhalb ±10 %, Bänke gleichlaufend)", map.verdict());
        assertTrue(map.table().contains("hohe Last: sammelt (0/20)"));
    }

    @Test
    public void trimMapNegative() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        feed(map, 30, -14.0, -13.0, 680, 0, 25);
        String v = map.verdict();
        assertTrue(v, v.contains("Bank 1 deutlich negativ"));
        assertTrue(v, v.contains("Bank 2 deutlich negativ"));
    }

    @Test
    public void trimMapOnlyOneBandDoesNotClaimAllBands() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        feed(map, 30, 11.0, 4.0, 680, 0, 25);
        String v = map.verdict();
        assertTrue(v, v.startsWith("Bank 1 stärker angefettet (bis 7,0 % mehr), Lastbereich fehlt noch"));
        assertFalse(v, v.contains("bankspezifisch"));
    }

    @Test
    public void trimMapHighUnderLoadIsNotUnremarkable() {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        feed(map, 30, 5.0, 4.0, 680, 0, 25);
        feed(map, 30, 15.0, 14.0, 1800, 60, 35);
        String v = map.verdict();
        assertTrue(v, v.contains("unter Last über +10 %"));
        EngineAnalysis.TrimMap idleHigh = new EngineAnalysis.TrimMap();
        feed(idleHigh, 30, 12.0, 12.0, 680, 0, 25);
        feed(idleHigh, 30, 9.0, 9.0, 1800, 60, 35);
        assertTrue(idleHigh.verdict(), idleHigh.verdict().contains("im Leerlauf über +10 %"));
    }

    @Test
    public void saturatedLambdaIsNoControlDeviation() {
        assertNull(EngineAnalysis.lambdaDeviation(pid(0x34).parse("4134FFFF8241"), 1.0, 2));
        assertNull(EngineAnalysis.lambdaDeviation(0.99, pid(0x44).parse("4144FFFF"), 2));
        assertEquals(0.01, EngineAnalysis.lambdaDeviation(0.99, 1.0, 2), 1e-9);
    }

    // ------------------------------------------------------------------ Wiedergabe des echten Logs

    private static Integer status(Map<Integer, Double> last) {
        Double st = last.get(0x03);
        return st == null ? null : (int) Math.round(st);
    }

    /** Spielt die Rohantworten durch den Parser und die Trim-Karte wie das Dashboard (eine Probe je STFT-B2). */
    @Test
    public void realLogReplayFindsBank1Pattern() throws Exception {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        Map<Integer, Double> last = new HashMap<>();
        int overrun = 0, parsed = 0;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("c350_log_20261107.csv")) {
            assertNotNull(in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(";");
                int id = Integer.parseInt(f[1], 16);
                Double v = pid(id).parse(f[2].replace("\"", ""));
                assertNotNull(line, v);
                parsed++;
                last.put(id, v);
                if (id == 0x44 && EngineAnalysis.isLambdaSaturated(v)) overrun++;
                if (id == 0x08 && !EngineAnalysis.isOverrun(last.get(0x44), status(last), last.get(0x45), last.get(0x0C))
                        && !EngineAnalysis.isLambdaSaturated(last.get(0x44))) {
                    Double st = last.get(0x03);
                    map.add(last.get(0x06), last.get(0x07), last.get(0x08), last.get(0x09),
                            st == null ? null : (int) Math.round(st), last.get(0x05), last.get(0x0C), last.get(0x0D), last.get(0x04));
                }
            }
        }
        assertEquals(3185, parsed);
        assertEquals(15, overrun);
        Double idle1 = map.mean(1, EngineAnalysis.TrimMap.Band.IDLE), idle2 = map.mean(2, EngineAnalysis.TrimMap.Band.IDLE);
        Double part1 = map.mean(1, EngineAnalysis.TrimMap.Band.PART), part2 = map.mean(2, EngineAnalysis.TrimMap.Band.PART);
        assertNotNull(map.table(), idle1);
        assertNotNull(map.table(), part1);
        assertTrue(map.table(), idle1 - idle2 >= 5 && part1 - part2 >= 5);
        assertTrue(map.verdict(), map.verdict().startsWith("Bank 1 wird in Leerlauf und unter Last stärker angefettet"));
    }

    // ------------------------------------------------------------------ zwei Antwortende (Motor + Getriebe)

    /**
     * 0C/0D/04/05/42/49 beantworten am C350 zwei Steuergeräte (ATH0, daher ohne Absender). Der Parser nimmt
     * die erste Antwort; dokumentiert, bis die Absenderwahl (7E8) umgesetzt ist.
     */
    @Test
    public void twoRespondersFirstWins() {
        assertEquals(953.75, pid(0x0C).parse("410C0EE7 410C0F08"), 1e-9);
        assertEquals(13.625, pid(0x42).parse("41423539 41423737"), 1e-9);
    }
}
