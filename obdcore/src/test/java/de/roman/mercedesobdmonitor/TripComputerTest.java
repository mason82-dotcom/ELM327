package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

/**
 * Fahrtenbuch. Die Sollwerte der echten Logs stammen aus einer unabhängigen Nachrechnung (Python, Linkssumme/Trapez,
 * Laufzeit aus 01 1F) vom 19.11.2026; Linkssumme und Trapez weichen dort um unter 1 % ab.
 */
public class TripComputerTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static List<TripComputer.Trip> replay(String resource) throws Exception {
        try (InputStream in = TripComputerTest.class.getClassLoader().getResourceAsStream(resource)) {
            return LogReplay.read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), BERLIN).drives;
        }
    }

    @Test
    public void realLog20261119MatchesIndependentCheck() throws Exception {
        List<TripComputer.Trip> trips = replay("c350_log_20261119.csv");
        assertEquals(2, trips.size());
        TripComputer.Trip a = trips.get(0), b = trips.get(1);
        assertEquals(1795108147305L, a.startMs);                 // 18:09:07,305
        assertEquals(4.72, a.km, 0.05);
        assertEquals(0.709, a.liters, 0.02);
        assertEquals(14.0, a.runMs / 60_000.0, 0.5);
        assertEquals(75, a.maxKmh, 0);
        assertEquals(15.0, a.litersPer100Km(), 0.5);
        assertEquals(1795109931177L, b.startMs);                 // 18:38:51 – nicht erst nach dem Laufzeit-Rücksprung
        assertEquals(4.56, b.km, 0.05);
        assertEquals(0.55, b.liters, 0.02);
        assertTrue(a.trimDiff != null && a.trimDiff > 5);        // Bank-1-Muster je Fahrt
        assertTrue(a.idleShare() > 0.35 && a.idleShare() < 0.5); // Kaltstart mit langem Stand
    }

    @Test
    public void logWithoutMafHasUnknownFuelNotZero() throws Exception {
        List<TripComputer.Trip> trips = replay("c350_log_20261107.csv");   // Ressource ohne PID 10
        assertEquals(2, trips.size());
        for (TripComputer.Trip t : trips) {
            assertFalse(t.fuelKnown());
            assertNull(t.litersPer100Km());
            assertTrue(t.km > 4);
        }
        String report = TripBook.report(trips, BERLIN, trips.get(1).endMs, 5);
        assertTrue(report, report.contains("Verbrauch – (ohne Luftmasse)"));
        assertFalse(report, report.contains("Ø 0,0"));
    }

    @Test
    public void engineOffGapAndShortStarts() {
        TripComputer c = new TripComputer();
        long t = 1_000_000;
        // 2 min Fahrt mit 36 km/h, 1 s Takt
        for (int i = 0; i <= 120; i++, t += 1000) {
            c.onSample(t, 0x0C, 1500);
            c.onSample(t, 0x0D, 36);
        }
        // Motor aus (Drehzahl 0) über 40 s → Fahrt endet nach 30 s
        for (int i = 0; i < 40; i++, t += 1000) c.onSample(t, 0x0C, 0);
        // kurzes Anlassen 20 s → verworfen
        for (int i = 0; i < 20; i++, t += 1000) c.onSample(t, 0x0C, 700);
        List<TripComputer.Trip> trips = c.finish();
        assertEquals(1, trips.size());
        assertEquals(1.2, trips.get(0).km, 0.02);               // 36 km/h × 120 s
        assertEquals(2.0, trips.get(0).runMs / 60_000.0, 0.05);
        assertFalse(trips.get(0).fuelKnown());                  // keine Luftmasse
    }

    @Test
    public void runtimeJumpSplitsOnlyAfterEngineStop() {
        TripComputer c = new TripComputer();
        long t = 0;
        double[] noisyRuntime = {117, 38, 26, 95, 161, 150};   // wie der Simulator: springt ohne Motorstopp
        for (int i = 0; i < 150; i++, t += 1000) {
            c.onSample(t, 0x0C, 1500);
            if (i % 25 == 0) c.onSample(t, 0x1F, noisyRuntime[i / 25]);
        }
        // echter Neustart: kurz Motor aus (unter 30 s), dann Laufzeit klein
        for (int i = 0; i < 10; i++, t += 1000) c.onSample(t, 0x0C, 0);
        c.onSample(t, 0x0C, 800);
        c.onSample(t, 0x1F, 2);
        for (int i = 0; i < 90; i++, t += 1000) c.onSample(t, 0x0C, 800);
        List<TripComputer.Trip> trips = c.finish();
        assertEquals(2, trips.size());
        assertEquals(150_000, trips.get(0).runMs);   // inkl. Intervall bis zum ersten Wert „Motor aus“
    }

    @Test
    public void mergeReplacesOverlapsAndJoinsLogRotation() {
        // Log A endet bei laufendem Motor (offenes Ende)
        TripComputer.Trip a = new TripComputer.Trip(0, 600_000, 5, 0.6, 600_000, 60_000, 80, 6.0, true);
        // dasselbe Log später länger gelesen: überlappt → ersetzt
        TripComputer.Trip a2 = new TripComputer.Trip(0, 900_000, 7, 0.9, 900_000, 90_000, 90, 7.0, true);
        List<TripComputer.Trip> m = TripBook.merge(List.of(a), List.of(a2));
        assertEquals(1, m.size());
        assertEquals(7, m.get(0).km, 0);
        // Logwechsel mitten in der Fahrt: Stück aus Log B 30 s später → beim Anzeigen verbunden
        TripComputer.Trip b = new TripComputer.Trip(930_000, 1_230_000, 3, Double.NaN, 300_000, 0, 120, 3.0, true);
        m = TripBook.merge(m, List.of(b));
        assertEquals(2, m.size());                     // gespeichert bleiben die Teilstücke
        List<TripComputer.Trip> j = TripBook.joined(m);
        assertEquals(1, j.size());
        assertEquals(10, j.get(0).km, 1e-9);
        assertEquals(1_200_000, j.get(0).runMs);
        assertEquals(120, j.get(0).maxKmh, 0);
        assertFalse(j.get(0).fuelKnown());             // ein Stück ohne Luftmasse → Summe unbekannt
        assertEquals((7.0 * 900 + 3.0 * 300) / 1200, j.get(0).trimDiff, 1e-9);
        // Log B wächst weiter, nur B wird neu gelesen: das Stück aus Log A bleibt erhalten
        TripComputer.Trip b2 = new TripComputer.Trip(930_000, 1_530_000, 5, Double.NaN, 600_000, 0, 120, 3.0, false);
        m = TripBook.merge(m, List.of(b2));
        j = TripBook.joined(m);
        assertEquals(1, j.size());
        assertEquals(12, j.get(0).km, 1e-9);
        assertEquals(1_500_000, j.get(0).runMs);
        // nächste Fahrt nach 10 min Pause bleibt eigene Fahrt
        TripComputer.Trip c = new TripComputer.Trip(2_130_000, 2_730_000, 4, 0.5, 600_000, 0, 60, null);
        assertEquals(2, TripBook.joined(TripBook.merge(m, List.of(c))).size());
    }

    /** Tankstopp: 60 s Motor aus ist eine echte Fahrtgrenze, auch unter {@link TripBook#JOIN_GAP_MS}. */
    @Test
    public void shortEngineOffStaysTwoTrips() {
        TripComputer c = new TripComputer();
        long t = 0;
        for (; t <= 300_000; t += 2_000) c.onSample(t, 0x0C, 800);
        for (t = 302_000; t <= 362_000; t += 2_000) c.onSample(t, 0x0C, 0);
        for (t = 364_000; t <= 664_000; t += 2_000) c.onSample(t, 0x0C, 800);
        List<TripComputer.Trip> trips = c.finish();
        assertEquals(2, trips.size());
        assertFalse(trips.get(0).openEnd);
        assertTrue(trips.get(1).openEnd);              // Log endete bei laufendem Motor
        assertEquals(2, TripBook.joined(TripBook.merge(List.of(), trips)).size());
        // „offen“ übersteht das Speichern
        List<TripComputer.Trip> back = TripComputer.parse(TripComputer.serialize(trips));
        assertFalse(back.get(0).openEnd);
        assertTrue(back.get(1).openEnd);
    }

    @Test
    public void runtimeValueDuringStopDoesNotHideRestart() {
        TripComputer c = new TripComputer();
        long t = 0;
        for (int i = 0; i < 90; i++, t += 1000) {
            c.onSample(t, 0x0C, 1500);
            if (i == 80) c.onSample(t, 0x1F, 600);
        }
        for (int i = 0; i < 10; i++, t += 1000) {          // 10 s Motor aus, dabei ein 1F-Wert mit alter Laufzeit
            c.onSample(t, 0x0C, 0);
            if (i == 6) c.onSample(t, 0x1F, 610);
        }
        c.onSample(t, 0x0C, 800);
        c.onSample(t, 0x1F, 1);                             // Neustart
        for (int i = 0; i < 90; i++, t += 1000) c.onSample(t, 0x0C, 800);
        assertEquals(2, c.finish().size());
    }

    @Test
    public void gapsBeyondLimitAreNotBridged() {
        TripComputer c = new TripComputer();
        c.onSample(0, 0x0C, 1500);
        c.onSample(0, 0x0D, 50);
        c.onSample(60_000, 0x0C, 1500);   // 60 s ohne Werte: weder Strecke noch Laufzeit
        c.onSample(60_000, 0x0D, 50);
        for (long t = 61_000; t <= 200_000; t += 1000) c.onSample(t, 0x0C, 1500);
        TripComputer.Trip trip = c.finish().get(0);
        assertEquals(0.0, trip.km, 1e-9);
        assertEquals(140_000, trip.runMs);
    }

    @Test
    public void serializeMergeAndCsv() throws Exception {
        List<TripComputer.Trip> trips = replay("c350_log_20261119.csv");
        List<TripComputer.Trip> old = replay("c350_log_20261107.csv");
        String text = TripComputer.serialize(TripBook.merge(old, trips));
        List<TripComputer.Trip> back = TripComputer.parse(text + "kaputt\n1;2;3\n");
        assertEquals(4, back.size());
        assertFalse(back.get(0).fuelKnown());
        assertEquals(text, TripComputer.serialize(back));
        // derselbe Log erneut: ersetzt, verdoppelt nicht
        assertEquals(4, TripBook.merge(back, trips).size());
        String csv = TripBook.csv(back, BERLIN);
        assertTrue(csv, csv.startsWith("start;ende;motorlaufzeit_min;strecke_km;kraftstoff_l;l_100km;"));
        assertTrue(csv, csv.contains("2026-11-19 18:09;2026-11-19 18:23;14,0;4,72;0,71;15,0;"));
        String report = TripBook.report(back, BERLIN, back.get(3).endMs, 3);
        assertTrue(report, report.startsWith("letzte 30 Tage: 4 Fahrten · 20 km · Ø 13,5 l/100 km"));
        assertTrue(report, report.endsWith("… 1 ältere"));
        assertTrue(TripBook.report(List.of(), BERLIN, 0, 3).startsWith("noch keine Fahrt"));
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = TripComputerTest.class.getClassLoader().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    private static LogReplay.Result readFrom(byte[] data, int length, long from) throws Exception {
        return LogReplay.readFrom(new java.io.ByteArrayInputStream(data, 0, length), BERLIN, from);
    }

    private static String describe(List<TripComputer.Trip> trips) {
        StringBuilder sb = new StringBuilder();
        for (TripComputer.Trip t : TripBook.joined(trips)) {
            sb.append(String.format(java.util.Locale.US, "%d-%d %.3f km %.3f l %d/%d ms %s%n", t.startMs, t.endMs, t.km,
                    t.liters, t.runMs, t.idleMs, t.trimDiff == null ? "-" : String.format(java.util.Locale.US, "%.2f", t.trimDiff)));
        }
        return sb.toString();
    }

    /**
     * Das aktive Log wächst: stückweise gelesen (Fortsetzen ab resumeOffset, Schnitt auch mitten in einer Zeile)
     * ergibt dasselbe Fahrtenbuch wie einmal ganz gelesen.
     */
    @Test
    public void growingLogReadInPiecesMatchesFullRead() throws Exception {
        byte[] data = resource("c350_log_20261119.csv");
        String expected = describe(readFrom(data, data.length, 0).drives);
        assertEquals(2, TripBook.joined(readFrom(data, data.length, 0).drives).size());
        int[][] cutSets = {{data.length / 2}, {data.length / 4, data.length / 2 + 7, data.length * 3 / 4 + 3},
                {data.length / 3 + 11}};
        for (int[] cuts : cutSets) {
            List<TripComputer.Trip> stored = new java.util.ArrayList<>();
            long from = 0;
            for (int cut : cuts) {
                LogReplay.Result r = readFrom(data, cut, from);
                assertTrue(r.resumeOffset >= from && r.resumeOffset <= cut);
                stored = TripBook.merge(stored, r.drives);
                from = r.resumeOffset;
            }
            LogReplay.Result last = readFrom(data, data.length, from);
            stored = TripBook.merge(stored, last.drives);
            assertEquals(java.util.Arrays.toString(cuts), expected, describe(stored));
            // weitergelesen wird nicht ab 0 (außer der Motor lief seit Logbeginn) und nie über das Ende hinaus
            assertTrue(last.resumeOffset <= data.length);
        }
        // nichts Neues: ab dem Ende gelesen liefert keine Fahrt und dieselbe Position
        LogReplay.Result full = readFrom(data, data.length, 0);
        LogReplay.Result again = readFrom(data, data.length, full.resumeOffset);
        assertEquals(full.resumeOffset, again.resumeOffset);
        // Log endet bei laufendem Motor: weiter ab Beginn der zweiten Fahrt, nicht ab 0 (Ersparnis ~64 %)
        assertTrue(full.resumeOffset > data.length / 2);
    }

    @Test
    public void stampsKeepSizeAndPosition() {
        java.util.Map<String, long[]> m = TripStore.parseStamps("a.csv=100;40\nalt.csv=77\nkaputt\nb.csv=10;99\n");
        assertEquals(40, m.get("a.csv")[1]);
        assertEquals(0, m.get("alt.csv")[1]);        // ältere Zeile ohne Position → von vorn
        assertEquals(10, m.get("b.csv")[1]);         // nie hinter die Dateigröße
        assertEquals(3, m.size());
        assertEquals(100, TripStore.parseStamps(TripStore.formatStamps(m)).get("a.csv")[0]);
    }
}
