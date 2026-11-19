package de.roman.mercedesobdmonitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Trim-Karte über mehrere Verbindungen (Android-frei, testbar). Je Verbindung wird ein Abbild der
 * {@link EngineAnalysis.TrimMap} gespeichert; daraus folgt, ob ein Bankunterschied beständig ist und ob er
 * wächst. Anlass: Am C350 lag Bank 1 am 07.11. und 19.11.2026 lastunabhängig 6–8 % über Bank 2 – ein
 * einzelnes Log zeigt das, erst der Verlauf zeigt, ob es schlimmer wird. Alle Schwellen sind Richtwerte.
 */
public final class TrimHistory {
    /** Gespeicherte Verbindungen (die ältesten fallen heraus). */
    public static final int MAX_ENTRIES = 12;
    /** Bankdifferenz, ab der eine Fahrt als „Bank stärker angefettet“ zählt (wie {@link EngineAnalysis.TrimMap}). */
    public static final double BANK_DIFF = 5.0;
    /** Änderung der Bankdifferenz (erste gegen letzte Fahrten), ab der sie als steigend/fallend gilt. */
    public static final double TREND_STEP = 3.0;
    /** Gesamtkorrektur, ab der typischerweise P0171/P0174 (System zu mager) gesetzt wird – Richtwert. */
    public static final double LEAN_CODE_LIMIT = 25.0;
    private static final int BANDS = EngineAnalysis.TrimMap.Band.values().length;

    /** Abbild einer Verbindung: Gesamtkorrektur je Bank und Band (NaN = zu wenige Proben). */
    public static final class Entry {
        public final long startedAtMs;
        final double[] bank1 = new double[BANDS];
        final double[] bank2 = new double[BANDS];
        final int[] samples = new int[BANDS];

        Entry(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }

        public Double mean(int bank, EngineAnalysis.TrimMap.Band b) {
            double v = (bank == 1 ? bank1 : bank2)[b.ordinal()];
            return Double.isNaN(v) ? null : v;
        }

        public int samples(EngineAnalysis.TrimMap.Band b) {
            return samples[b.ordinal()];
        }

        /** Bankdifferenz B1−B2, nach Proben gewichtet über alle Bänder mit Mittelwert. */
        public Double bankDifference() {
            double s = 0;
            int n = 0;
            for (int i = 0; i < BANDS; i++) {
                if (Double.isNaN(bank1[i])) continue;
                s += (bank1[i] - bank2[i]) * samples[i];
                n += samples[i];
            }
            return n == 0 ? null : s / n;
        }

        /** Höchste Gesamtkorrektur (Betrag) beider Bänke über alle Bänder. */
        double maxCorrection() {
            double m = 0;
            for (int i = 0; i < BANDS; i++) {
                if (Double.isNaN(bank1[i])) continue;
                m = Math.max(m, Math.max(Math.abs(bank1[i]), Math.abs(bank2[i])));
            }
            return m;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Abbild der Trim-Karte; null, solange kein Band genug Proben hat. */
    public static Entry snapshot(long startedAtMs, EngineAnalysis.TrimMap map) {
        Entry e = new Entry(startedAtMs);
        boolean any = false;
        for (EngineAnalysis.TrimMap.Band b : EngineAnalysis.TrimMap.Band.values()) {
            Double m1 = map.mean(1, b), m2 = map.mean(2, b);
            int i = b.ordinal();
            e.samples[i] = map.samples(b);
            e.bank1[i] = m1 == null ? Double.NaN : m1;
            e.bank2[i] = m2 == null ? Double.NaN : m2;
            any |= m1 != null;
        }
        return any ? e : null;
    }

    /** Verbindung eintragen oder (gleicher Startzeitpunkt) aktualisieren; null wird ignoriert. */
    public void upsert(Entry e) {
        if (e == null) return;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).startedAtMs == e.startedAtMs) {
                entries.set(i, e);
                return;
            }
        }
        entries.add(e);
        entries.sort((a, b) -> Long.compare(a.startedAtMs, b.startedAtMs));
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public void clear() {
        entries.clear();
    }

    // ------------------------------------------------------------------ Speichern (SharedPreferences)

    /** Eine Zeile je Verbindung: Start;B1 L/T/H;B2 L/T/H;n L/T/H (Punkt als Dezimalzeichen, leer = NaN). */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.startedAtMs);
            for (double[] bank : new double[][] {e.bank1, e.bank2}) {
                for (double v : bank) sb.append(';').append(Double.isNaN(v) ? "" : String.format(Locale.US, "%.3f", v));
            }
            for (int n : e.samples) sb.append(';').append(n);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Liest {@link #serialize()}; beschädigte Zeilen werden übersprungen, nie eine Ausnahme. */
    public static TrimHistory parse(String text) {
        TrimHistory h = new TrimHistory();
        if (text == null) return h;
        for (String line : text.split("\n")) {
            String[] f = line.split(";", -1);
            if (f.length != 1 + 3 * BANDS) continue;
            try {
                Entry e = new Entry(Long.parseLong(f[0]));
                for (int i = 0; i < BANDS; i++) {
                    e.bank1[i] = f[1 + i].isEmpty() ? Double.NaN : Double.parseDouble(f[1 + i]);
                    e.bank2[i] = f[1 + BANDS + i].isEmpty() ? Double.NaN : Double.parseDouble(f[1 + BANDS + i]);
                    e.samples[i] = Integer.parseInt(f[1 + 2 * BANDS + i]);
                    if (Double.isNaN(e.bank1[i]) != Double.isNaN(e.bank2[i])) throw new NumberFormatException();
                }
                if (e.bankDifference() != null) h.upsert(e);
            } catch (NumberFormatException ignored) { }
        }
        return h;
    }

    // ------------------------------------------------------------------ Anzeige

    /** Letzte Verbindungen, neueste zuerst, z. B. „19.11. 18:09  B1 +11,7/+10,2/– · B2 +4,3/+3,1/– · Δ +7,0“. */
    public String table(ZoneId zone, int maxRows) {
        if (entries.isEmpty()) return "noch keine Verbindung mit genug Werten gespeichert";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(zone);
        StringBuilder sb = new StringBuilder();
        int rows = 0;
        for (int i = entries.size() - 1; i >= 0 && rows < maxRows; i--, rows++) {
            Entry e = entries.get(i);
            if (rows > 0) sb.append('\n');
            sb.append(fmt.format(Instant.ofEpochMilli(e.startedAtMs)))
                    .append("  B1 ").append(bands(e.bank1)).append(" · B2 ").append(bands(e.bank2))
                    .append(String.format(Locale.GERMANY, " · Δ %+.1f", e.bankDifference()));
        }
        return sb.toString();
    }

    private static String bands(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(Double.isNaN(v[i]) ? "–" : String.format(Locale.GERMANY, "%+.1f", v[i]));
        }
        return sb.toString();
    }

    /** Einordnung über alle gespeicherten Verbindungen. */
    public String verdict() {
        int n = entries.size();
        if (n == 0) return "sammelt Verbindungen (je Verbindung ein Eintrag, sobald die Trim-Karte Werte hat)";
        Entry latest = entries.get(n - 1);
        String margin = String.format(Locale.GERMANY, "höchste Korrektur zuletzt %.1f %% (Fehlercode „zu mager“ typ. ab ~%.0f %%)",
                latest.maxCorrection(), LEAN_CODE_LIMIT);
        if (n == 1) return "erst eine Verbindung – Verlauf ab der zweiten; " + margin + " (Richtwert)";
        int richer1 = 0, richer2 = 0;
        for (Entry e : entries) {
            double d = e.bankDifference();
            if (d >= BANK_DIFF) richer1++;
            else if (d <= -BANK_DIFF) richer2++;
        }
        StringBuilder sb = new StringBuilder();
        if (richer1 == n || richer2 == n) {
            sb.append(String.format(Locale.GERMANY, "Bank %d in allen %d Verbindungen stärker angefettet – beständig, kein Zufall; ",
                    richer1 == n ? 1 : 2, n));
        } else if (richer1 + richer2 > 0) {
            sb.append(String.format(Locale.GERMANY, "Bankunterschied in %d von %d Verbindungen; ", richer1 + richer2, n));
        } else {
            sb.append("Bänke in allen Verbindungen gleichlaufend; ");
        }
        if (n >= 3) {
            // Betrag der Differenz: erste gegen letzte Verbindungen (je ein Drittel, mindestens eine)
            int k = Math.max(1, n / 3);
            double first = 0, last = 0;
            for (int i = 0; i < k; i++) {
                first += Math.abs(entries.get(i).bankDifference());
                last += Math.abs(entries.get(n - 1 - i).bankDifference());
            }
            double change = (last - first) / k;
            if (Math.abs(change) >= TREND_STEP) {
                sb.append(String.format(Locale.GERMANY, "Unterschied %s (%+.1f %%-Punkte); ", change > 0 ? "wächst" : "schrumpft", change));
            } else {
                sb.append("Unterschied stabil; ");
            }
        }
        sb.append(margin);
        return sb + " (Richtwert, keine Diagnose)";
    }
}
