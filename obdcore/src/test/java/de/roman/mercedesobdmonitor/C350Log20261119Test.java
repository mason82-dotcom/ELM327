package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Zweites C350-Log (19.11.2026, Monitor 1.2.x mit ATH1, zwei Fahrten, 39 min). Die Testressource enthält
 * Zeitstempel, PID, den von der App geloggten Wert und die Rohantwort mit CAN-Header, wie sie im CSV steht
 * (mehrere Antworten durch Leerzeichen getrennt).
 */
public class C350Log20261119Test {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("PID missing: " + id);
    }

    /** Echte Zeilen, in denen das Getriebe (7E9) zuerst antwortete und einen anderen Wert meldete. */
    @Test
    public void csvRawWithTransmissionFirstStillYieldsEngineValue() {
        assertEquals(52.0, pid(0x05).parse("7E90341055B 7E80341055C"), 1e-9);
        assertEquals(525.5, pid(0x0C).parse("7E904410C0840 7E804410C0836"), 1e-9);
        // ATS1-Form bleibt eine Zeile und wird wie bisher gelesen
        assertEquals(697.5, pid(0x0C).parse("7E8 04 41 0C 0A E6"), 1e-9);
        // ohne Header (ATH0, Log 07.11.): weiterhin die erste Antwort
        assertEquals(953.75, pid(0x0C).parse("410C0EE7 410C0F08"), 1e-9);
    }

    /**
     * Jede Rohantwort ergibt beim Wiedereinlesen genau den Wert, den die App live geloggt hat (live: 7E8 bevorzugt),
     * und die Trim-Karte zeigt wieder das lastunabhängige Bank-1-Muster des 07.11.
     */
    @Test
    public void realLogReplayMatchesLiveValuesAndBank1Pattern() throws Exception {
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        Map<Integer, Double> last = new HashMap<>();
        int parsed = 0, twoEcus = 0, transmissionFirst = 0;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("c350_log_20261119.csv")) {
            assertNotNull(in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(";");
                int id = Integer.parseInt(f[1], 16);
                double logged = Double.parseDouble(f[2]);
                String raw = f[3].replace("\"", "");
                Double v = pid(id).parse(raw);
                assertNotNull(line, v);
                assertEquals(line, logged, v, 1e-6);
                parsed++;
                if (raw.contains(" ")) {
                    twoEcus++;
                    if (raw.startsWith("7E9")) transmissionFirst++;
                }
                last.put(id, v);
                if (id == 0x08 && !EngineAnalysis.isOverrun(last.get(0x44), status(last), last.get(0x45), last.get(0x0C))
                        && !EngineAnalysis.isLambdaSaturated(last.get(0x44))) {
                    map.add(last.get(0x06), last.get(0x07), last.get(0x08), last.get(0x09), status(last),
                            last.get(0x05), last.get(0x0C), last.get(0x0D), last.get(0x04));
                }
            }
        }
        assertEquals(4768, parsed);
        assertTrue(twoEcus + " Doppelantworten", twoEcus > 1000);
        assertTrue(transmissionFirst + " davon Getriebe zuerst", transmissionFirst > 300);
        assertTrue(map.verdict(), map.verdict().startsWith("Bank 1 wird in Leerlauf und unter Last stärker angefettet"));

        TrimHistory history = new TrimHistory();
        history.upsert(TrimHistory.snapshot(1L, map));
        double diff = history.entries().get(0).bankDifference();
        assertTrue(map.table(), diff >= 5 && diff <= 10);
    }

    private static Integer status(Map<Integer, Double> last) {
        Double st = last.get(0x03);
        return st == null ? null : (int) Math.round(st);
    }
}
