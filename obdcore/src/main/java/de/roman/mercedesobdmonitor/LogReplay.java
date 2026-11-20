package de.roman.mercedesobdmonitor;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Spielt ein CSV-Log der Monitor-App ({@link CsvLogWriter#HEADER}) durch den {@link TripComputer} (Android-frei,
 * testbar): Er teilt das Log in Fahrten (Motor läuft; Ende nach 30 s Motor aus, 2 min Lücke oder Neustart) und liefert
 * je Fahrt das Fahrtenbuch und – mit einer Trim-Karte wie live im Dashboard – den Eintrag für den
 * {@link TrimHistory Trim-Verlauf}. Eine einzige Aufteilung: beide zählen dieselben Fahrten (Review).
 */
public final class LogReplay {
    private LogReplay() { }

    /** Byte-Order-Mark, die manche Editoren beim Speichern voranstellen. */
    static final char BOM = (char) 0xFEFF;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Ergebnis: je Fahrt mit genug Werten ein Verlaufseintrag, dazu Zählwerte für die Rückmeldung. */
    public static final class Result {
        public final List<TrimHistory.Entry> entries = new ArrayList<>();
        /** Fahrten für das Fahrtenbuch ({@link TripComputer}). */
        public final List<TripComputer.Trip> drives = new ArrayList<>();
        public int rows;
        public int skippedRows;
        public int trips;
        /** Nur {@link #readFrom}: ab hier liest das nächste Mal weiter. */
        public long resumeOffset;

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
        Replayer rp = new Replayer(in.readLine(), zone);
        String line;
        while ((line = in.readLine()) != null) rp.accept(line, -1);
        return rp.finish();
    }

    /**
     * Liest eine Logdatei ab {@code fromOffset} weiter (0 = ganz). Die Kopfzeile wird immer gelesen, danach wird bis
     * {@code fromOffset} übersprungen. Nur mit Zeilenende abgeschlossene Zeilen zählen: die letzte kann der Dienst
     * gerade schreiben. {@link Result#resumeOffset} sagt, ab wo das nächste Mal ergebnisgleich weitergelesen werden kann
     * – am Anfang der noch offenen Fahrt, sonst hinter der letzten vollständigen Zeile.
     */
    public static Result readFrom(InputStream raw, ZoneId zone, long fromOffset) throws IOException {
        LineReader in = new LineReader(raw);
        String header = in.next();
        if (header == null) throw new IOException("Datei ist leer");
        Replayer rp = new Replayer(header, zone);
        long headerEnd = in.position;
        if (fromOffset > headerEnd) in.skipTo(fromOffset);
        long lastComplete = in.position;
        String line;
        while (true) {
            long lineStart = in.position;
            line = in.next();
            if (line == null) break;
            if (!in.lastTerminated) break;   // halb geschriebene Zeile: beim nächsten Mal
            rp.accept(line, lineStart);
            lastComplete = in.position;
        }
        Result r = rp.finish();
        r.resumeOffset = rp.openOffset >= 0 ? rp.openOffset : lastComplete;
        return r;
    }

    /** Verarbeitet Zeilen eines Logs; gemeinsam für Import ({@link #read}) und Fortsetzen ({@link #readFrom}). */
    private static final class Replayer {
        private final ZoneId zone;
        private final int iTs, iPid, iValue, iRaw;
        private final Map<Integer, ObdPid> pids = new HashMap<>();
        private final Result r = new Result();
        // Eine einzige Aufteilung in Fahrten (TripComputer): Fahrtenbuch und Trim-Verlauf zählen dieselben Fahrten
        private final TripComputer tripComputer = new TripComputer();
        private long previous;
        private ZoneOffset offset;   // Offset der Vorzeile: hält die doppelte Stunde der Zeitumstellung fortlaufend
        /** Byte-Position der Zeile, mit der die offene Fahrt begann; -1 = keine offen. */
        long openOffset = -1;

        Replayer(String header, ZoneId zone) throws IOException {
            if (header == null) throw new IOException("Datei ist leer");
            if (!header.isEmpty() && header.charAt(0) == BOM) header = header.substring(1);
            List<String> cols = split(header);
            iTs = cols.indexOf("timestamp");
            iPid = cols.indexOf("pid");
            iValue = cols.indexOf("value");
            iRaw = cols.indexOf("raw");
            if (iTs < 0 || iPid < 0 || (iValue < 0 && iRaw < 0)) {
                throw new IOException("kein Log der Monitor-App (Spalten timestamp;pid;value erwartet)");
            }
            this.zone = zone;
            for (ObdPid p : ObdPid.defaultPids()) pids.put(p.pid, p);
        }

        void accept(String line, long lineStart) {
            if (line.isEmpty()) return;
            List<String> f = split(line);
            long t;
            int pid;
            Double value;
            try {
                ZonedDateTime local = ZonedDateTime.ofLocal(LocalDateTime.parse(f.get(iTs), TS), zone, offset);
                // springt die Ortszeit in der doppelten Stunde zurück, ist es die zweite Hälfte (Winterzeit)
                if (previous != 0 && local.toInstant().toEpochMilli() < previous) local = local.withLaterOffsetAtOverlap();
                t = local.toInstant().toEpochMilli();
                offset = local.getOffset();
                pid = Integer.parseInt(f.get(iPid).trim(), 16);
                ObdPid def = pids.get(pid);
                if (iValue >= 0 && !f.get(iValue).isEmpty()) value = Double.parseDouble(f.get(iValue));
                else value = def == null || iRaw < 0 ? null : def.parse(f.get(iRaw));
            } catch (RuntimeException e) {   // Spalte fehlt, Zahl/Zeit kaputt
                r.skippedRows++;
                return;
            }
            if (value == null || value.isNaN() || value.isInfinite()) {
                r.skippedRows++;
                return;
            }
            previous = t;
            r.rows++;
            long before = tripComputer.openStart();
            tripComputer.onSample(t, pid, value);
            long after = tripComputer.openStart();
            if (after < 0) openOffset = -1;
            else if (after != before) openOffset = lineStart;
        }

        Result finish() {
            r.drives.addAll(tripComputer.finish());
            r.trips = r.drives.size();
            for (TripComputer.Trip d : r.drives) if (d.trimEntry != null) r.entries.add(d.trimEntry);
            return r;
        }
    }

    /** Zeilen aus Bytes (UTF-8) mit Byte-Position; erkennt, ob die letzte Zeile ein Zeilenende hatte. */
    static final class LineReader {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        long position;
        boolean lastTerminated;

        LineReader(InputStream in) {
            this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 64 * 1024);
        }

        void skipTo(long target) throws IOException {
            while (position < target) {
                long n = in.skip(target - position);
                if (n <= 0) {
                    if (in.read() < 0) return;
                    n = 1;
                }
                position += n;
            }
        }

        String next() throws IOException {
            buf.reset();
            int b;
            while ((b = in.read()) >= 0) {
                position++;
                if (b == '\n') {
                    lastTerminated = true;
                    return text();
                }
                buf.write(b);
            }
            lastTerminated = false;
            return buf.size() == 0 ? null : text();
        }

        private String text() {
            String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }
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
