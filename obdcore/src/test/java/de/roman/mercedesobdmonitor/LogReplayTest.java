package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class LogReplayTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private LogReplay.Result resource(String name) throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(name, in);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return LogReplay.read(r, BERLIN);
        }
    }

    /** Log 19.11.: zwei Fahrten (Pause 15 min), beide warm genug; Bank 1 fetter. */
    @Test
    public void log20261119TwoTripsBank1Richer() throws IOException {
        LogReplay.Result r = resource("c350_log_20261119.csv");
        assertEquals(4768, r.rows);
        assertEquals(0, r.skippedRows);
        assertEquals(2, r.trips);
        assertEquals(r.summary(), 2, r.entries.size());
        for (TrimHistory.Entry e : r.entries) assertTrue(String.valueOf(e.bankDifference()), e.bankDifference() >= 5);
        // erster Wert 18:09:07 Ortszeit
        assertEquals(1795108147305L, r.entries.get(0).startedAtMs);
    }

    /** Log 07.11. (nur Rohantworten, ohne Wertspalte): Werte kommen aus dem Parser. */
    @Test
    public void log20261107RawOnly() throws IOException {
        LogReplay.Result r = resource("c350_log_20261107.csv");
        assertEquals(3185, r.rows);
        assertEquals(2, r.trips);
        assertTrue(r.summary(), r.entries.size() >= 1);
        TrimHistory h = new TrimHistory();
        assertEquals("2 Fahrten übernommen", r.applyTo(h));
        LogReplay.Result r19 = resource("c350_log_20261119.csv");
        assertEquals("2 Fahrten übernommen", r19.applyTo(h));
        assertTrue(h.verdict(), h.verdict().startsWith("Bank 1 in allen 4 Verbindungen stärker angefettet"));
        // erneuter Import ändert nichts an der Anzahl
        assertEquals("2 erneut importiert (aktualisiert)", resource("c350_log_20261119.csv").applyTo(h));
        assertEquals(4, h.entries().size());
        // Fahrtende = letzter Wert der Fahrt
        assertTrue(r19.entries.get(0).endedAtMs > r19.entries.get(0).startedAtMs + 10 * 60_000L);
    }

    /** Zeitumstellung: die doppelte Stunde (25.10.2026 02:00–03:00) bleibt eine fortlaufende Fahrt. */
    @Test
    public void dstFallBackStaysOneTrip() throws IOException {
        StringBuilder csv = new StringBuilder("timestamp;pid;value\n");
        // 02:58 MESZ, 02:59 MESZ, dann nach der Umstellung 02:00 MEZ … – lokal springt die Uhr zurück
        String[] times = {"2026-10-25 02:58:00.000", "2026-10-25 02:59:59.000", "2026-10-25 02:00:01.000", "2026-10-25 02:01:00.000"};
        for (String t : times) csv.append('"').append(t).append("\";0C;700.0\n");
        LogReplay.Result r = LogReplay.read(new BufferedReader(new StringReader(csv.toString())), BERLIN);
        assertEquals(4, r.rows);
        // ohne Offset-Fortschreibung: „02:00:01“ gälte als MESZ = 1 h früher → Rücksprung → zweite Fahrt
        assertEquals(1, r.trips);
    }

    /** Format der App (CsvLogWriter.HEADER) mit Anführungszeichen, kaputten Zeilen und BOM. */
    @Test
    public void appFormatToleratesBrokenRows() throws IOException {
        String csv = LogReplay.BOM + CsvLogWriter.HEADER + "\n"
                + "\"2026-11-19 18:09:07.305\";0C;\"Motordrehzahl\";697.500000;\"U/min\";120;\"7E804410C0AE6 7E904410C0AF4\"\n"
                + "kaputt\n"
                + "\"2026-11-19 18:09:07.400\";0D;\"Geschwindigkeit\";;\"km/h\";90;\"7E803410D00\"\n"
                + "\"2026-11-19 18:09:07.500\";ZZ;\"?\";1;\"\";90;\"\"\n"
                + "\"2026-11-19 18:30:00.000\";05;\"Kühlmittel\";90.000000;\"°C\";90;\"7E80341057E\"\n";
        LogReplay.Result r = LogReplay.read(new BufferedReader(new StringReader(csv)), BERLIN);
        assertEquals(3, r.rows);          // 0D ohne Wert → aus der Rohantwort
        assertEquals(2, r.skippedRows);
        assertEquals(2, r.trips);         // Pause > 5 min
        assertEquals(0, r.entries.size());
        assertTrue(r.summary(), r.summary().contains("2 Zeilen übersprungen"));
    }

    @Test
    public void rejectsForeignFiles() {
        for (String text : new String[] {"", "Datum,Wert\n1,2\n"}) {
            try {
                LogReplay.read(new BufferedReader(new StringReader(text)), BERLIN);
                fail("erwartet IOException: " + text);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void splitHandlesQuotes() {
        assertEquals(List.of("a", "b;c", "d\"e", ""), LogReplay.split("a;\"b;c\";\"d\"\"e\";"));
    }
}
