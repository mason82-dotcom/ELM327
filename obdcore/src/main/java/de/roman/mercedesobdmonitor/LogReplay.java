package de.roman.mercedesobdmonitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spielt ein CSV-Log der Monitor-App ({@link CsvLogWriter#HEADER}) durch die Trim-Karte, damit ältere Fahrten im
 * {@link TrimHistory Trim-Verlauf} erscheinen (Android-frei, testbar). Gewertet wird – wie live im Dashboard – je
 * STFT-Bank-2-Wert eine Probe aus dem letzten Wert jeder PID ({@link EngineAnalysis.TrimMap#offer}). Eine Pause
 * ab {@link #TRIP_GAP_MS} beginnt eine neue Fahrt, denn der Wertestand davor gilt nicht mehr.
 */
public final class LogReplay {
    private LogReplay() { }

    /** Pause, ab der eine neue Fahrt beginnt (Motor aus, Verbindung neu). */
    public static final long TRIP_GAP_MS = 5 * 60_000L;
    /** Byte-Order-Mark, die manche Editoren beim Speichern voranstellen. */
    static final char BOM = (char) 0xFEFF;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Ergebnis: je Fahrt mit genug Werten ein Verlaufseintrag, dazu Zählwerte für die Rückmeldung. */
    public static final class Result {
        public final List<TrimHistory.Entry> entries = new ArrayList<>();
        public int rows;
        public int skippedRows;
        public int trips;

        /**
         * Trägt die Fahrten in den Verlauf ein und beschreibt das Ergebnis für die Rückmeldung, z. B.
         * „2 Fahrten übernommen, 1 schon gespeichert“.
         */
        public String applyTo(TrimHistory history) {
            int added = 0, replaced = 0, present = 0, old = 0;
            for (TrimHistory.Entry e : entries) {
                switch (history.addImported(e)) {
                    case ADDED -> added++;
                    case REPLACED -> replaced++;
                    case ALREADY_PRESENT -> present++;
                    case TOO_OLD -> old++;
                    default -> { }
                }
            }
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add(added + (added == 1 ? " Fahrt" : " Fahrten") + " übernommen");
            if (replaced > 0) parts.add(replaced + " erneut importiert (aktualisiert)");
            if (present > 0) parts.add(present + " schon gespeichert (live oder früherer Import)");
            if (old > 0) parts.add(old + " älter als die " + TrimHistory.MAX_ENTRIES + " gespeicherten, nicht aufgenommen");
            if (parts.isEmpty()) parts.add("keine Fahrt mit genug Trim-Werten (warm, geregelt)");
            return String.join(", ", parts);
        }

        public String summary() {
            return String.format(Locale.GERMANY, "%d Werte, %d Fahrten, %d mit genug Trim-Werten%s", rows, trips, entries.size(),
                    skippedRows > 0 ? String.format(Locale.GERMANY, " (%d Zeilen übersprungen)", skippedRows) : "");
        }
    }

    /**
     * Liest das Log. Zeitstempel sind Ortszeit des Telefons ({@code zone}). Wirft {@link IOException} nur bei
     * Lesefehlern oder wenn die Kopfzeile nicht zum Monitor-Log passt; einzelne kaputte Zeilen werden übersprungen.
     */
    public static Result read(BufferedReader in, ZoneId zone) throws IOException {
        String header = in.readLine();
        if (header == null) throw new IOException("Datei ist leer");
        if (!header.isEmpty() && header.charAt(0) == BOM) header = header.substring(1);
        List<String> cols = split(header);
        int iTs = cols.indexOf("timestamp"), iPid = cols.indexOf("pid"), iValue = cols.indexOf("value"), iRaw = cols.indexOf("raw");
        if (iTs < 0 || iPid < 0 || (iValue < 0 && iRaw < 0)) {
            throw new IOException("kein Log der Monitor-App (Spalten timestamp;pid;value erwartet)");
        }
        Map<Integer, ObdPid> pids = new HashMap<>();
        for (ObdPid p : ObdPid.defaultPids()) pids.put(p.pid, p);

        Result r = new Result();
        Map<Integer, Double> last = new HashMap<>();
        EngineAnalysis.TrimMap map = new EngineAnalysis.TrimMap();
        long tripStart = 0, previous = 0;
        ZoneOffset offset = null;   // Offset der Vorzeile: hält die doppelte Stunde der Zeitumstellung fortlaufend
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) continue;
            List<String> f = split(line);
            long t;
            int pid;
            Double value;
            try {
                ZonedDateTime local = ZonedDateTime.ofLocal(LocalDateTime.parse(f.get(iTs), TS), zone, offset);
                // springt die Ortszeit in der doppelten Stunde zurück, ist es die zweite Hälfte (Winterzeit)
                if (r.trips > 0 && local.toInstant().toEpochMilli() < previous) local = local.withLaterOffsetAtOverlap();
                t = local.toInstant().toEpochMilli();
                offset = local.getOffset();
                pid = Integer.parseInt(f.get(iPid).trim(), 16);
                ObdPid def = pids.get(pid);
                if (iValue >= 0 && !f.get(iValue).isEmpty()) value = Double.parseDouble(f.get(iValue));
                else value = def == null || iRaw < 0 ? null : def.parse(f.get(iRaw));
            } catch (RuntimeException e) {   // Spalte fehlt, Zahl/Zeit kaputt
                r.skippedRows++;
                continue;
            }
            if (value == null || value.isNaN() || value.isInfinite()) {
                r.skippedRows++;
                continue;
            }
            if (r.trips == 0 || t - previous >= TRIP_GAP_MS || t < previous) {
                if (r.trips > 0) addTrip(r, tripStart, previous, map);
                r.trips++;
                tripStart = t;
                last.clear();
                map = new EngineAnalysis.TrimMap();
            }
            previous = t;
            r.rows++;
            last.put(pid, value);
            if (pid == 0x08) map.offer(last::get);
        }
        if (r.trips > 0) addTrip(r, tripStart, previous, map);
        return r;
    }

    private static void addTrip(Result r, long start, long end, EngineAnalysis.TrimMap map) {
        TrimHistory.Entry e = TrimHistory.snapshot(start, end, map);
        if (e != null) r.entries.add(e);
    }

    /** Semikolon-getrennt, Felder optional in "…" mit "" als Anführungszeichen (wie {@link CsvLogWriter}). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ';') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }
}
