package de.roman.mercedesobdmonitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Gespeicherte Fahrten zusammenführen, als Bericht und als CSV ausgeben (Android-frei, testbar). */
public final class TripBook {
    private TripBook() { }

    /** So viele Fahrten bleiben gespeichert (die ältesten fallen heraus). */
    public static final int MAX_TRIPS = 300;

    /** Folgt ein Teilstück so dicht auf das vorige, gehört es zur selben Fahrt (Logwechsel mitten in der Fahrt). */
    public static final long JOIN_GAP_MS = TripComputer.GAP_MS;

    /**
     * Neue Fahrten einfügen. Überschneidet sich eine neue Fahrt zeitlich mit gespeicherten, ersetzt sie diese (ein Log,
     * das beim letzten Einlesen noch wuchs, oder derselbe Log erneut importiert). Danach werden Teilstücke, deren
     * Abstand unter {@link #JOIN_GAP_MS} liegt, verbunden (Logwechsel mitten in der Fahrt). Ergebnis zeitlich
     * sortiert, höchstens {@link #MAX_TRIPS}.
     */
    public static List<TripComputer.Trip> merge(List<TripComputer.Trip> stored, List<TripComputer.Trip> fresh) {
        List<TripComputer.Trip> out = new ArrayList<>(stored);
        for (TripComputer.Trip f : fresh) {
            out.removeIf(t -> t.startMs <= f.endMs && f.startMs <= t.endMs);
            out.add(f);
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        List<TripComputer.Trip> joined = new ArrayList<>();
        for (TripComputer.Trip t : out) {
            int last = joined.size() - 1;
            if (last >= 0 && t.startMs - joined.get(last).endMs < JOIN_GAP_MS) {
                joined.set(last, TripComputer.Trip.join(joined.get(last), t));
            } else {
                joined.add(t);
            }
        }
        while (joined.size() > MAX_TRIPS) joined.remove(0);
        return joined;
    }

    /**
     * Bericht: Summe der letzten 30 Tage, darunter die neuesten Fahrten mit je zwei Zeilen, z. B.
     * „19.11. 18:38 · 10 min · 6,1 km · Ø 12,4 l/100 km“ und „  Leerlauf 25 % · max 75 km/h · Δ Trim B1−B2 +7,7 %“.
     */
    public static String report(List<TripComputer.Trip> trips, ZoneId zone, long nowMs, int maxRows) {
        if (trips.isEmpty()) {
            return "noch keine Fahrt – entsteht aus den CSV-Logs dieser App (Motor lief mindestens 1 min) oder aus importierten Logs";
        }
        StringBuilder sb = new StringBuilder();
        long from = nowMs - 30L * 86_400_000L;
        int n = 0;
        double km = 0, liters = 0, fuelKm = 0;
        long run = 0, idle = 0;
        for (TripComputer.Trip t : trips) {
            if (t.startMs < from) continue;
            n++;
            km += t.km;
            run += t.runMs;
            idle += t.idleMs;
            if (t.fuelKnown()) {   // Durchschnitt nur über Fahrten mit bekanntem Verbrauch (kein Verdünnen mit 0)
                liters += t.liters;
                fuelKm += t.km;
            }
        }
        if (n == 0) {
            sb.append("letzte 30 Tage: keine Fahrt");
        } else {
            sb.append(String.format(Locale.GERMANY, "letzte 30 Tage: %d %s · %.0f km", n, n == 1 ? "Fahrt" : "Fahrten", km));
            if (fuelKm >= 1) sb.append(String.format(Locale.GERMANY, " · Ø %.1f l/100 km", liters / fuelKm * 100));
            if (run > 0) sb.append(String.format(Locale.GERMANY, " · Leerlauf %.0f %%", 100.0 * idle / run));
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(zone);
        int rows = 0;
        for (int i = trips.size() - 1; i >= 0 && rows < maxRows; i--, rows++) {
            TripComputer.Trip t = trips.get(i);
            sb.append('\n').append(fmt.format(Instant.ofEpochMilli(t.startMs)))
                    .append(String.format(Locale.GERMANY, " · %d min · %.1f km", Math.round(t.runMs / 60_000.0), t.km));
            Double l100 = t.litersPer100Km();
            if (l100 != null) sb.append(String.format(Locale.GERMANY, " · Ø %.1f l/100 km", l100));
            else if (t.fuelKnown()) sb.append(String.format(Locale.GERMANY, " · %.2f l", t.liters));
            else sb.append(" · Verbrauch – (ohne Luftmasse)");
            sb.append(String.format(Locale.GERMANY, "\n  Leerlauf %.0f %% · max %.0f km/h", 100 * t.idleShare(), t.maxKmh));
            if (t.trimDiff != null) sb.append(String.format(Locale.GERMANY, " · Δ Trim B1−B2 %+.1f %%", t.trimDiff));
        }
        if (trips.size() > maxRows) sb.append(String.format(Locale.GERMANY, "\n… %d ältere", trips.size() - maxRows));
        return sb.toString();
    }

    /** CSV zum Teilen (Semikolon, deutsche Dezimalzahlen wie in Tabellenkalkulationen üblich). */
    public static String csv(List<TripComputer.Trip> trips, ZoneId zone) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.GERMANY).withZone(zone);
        StringBuilder sb = new StringBuilder("start;ende;motorlaufzeit_min;strecke_km;kraftstoff_l;l_100km;leerlauf_prozent;max_kmh;trim_b1_minus_b2_prozent\n");
        for (TripComputer.Trip t : trips) {
            Double l100 = t.litersPer100Km();
            sb.append(fmt.format(Instant.ofEpochMilli(t.startMs))).append(';').append(fmt.format(Instant.ofEpochMilli(t.endMs)))
                    .append(String.format(Locale.GERMANY, ";%.1f;%.2f;", t.runMs / 60_000.0, t.km))
                    .append(t.fuelKnown() ? String.format(Locale.GERMANY, "%.2f", t.liters) : "").append(';')
                    .append(l100 == null ? "" : String.format(Locale.GERMANY, "%.1f", l100))
                    .append(String.format(Locale.GERMANY, ";%.0f;%.0f;", 100 * t.idleShare(), t.maxKmh))
                    .append(t.trimDiff == null ? "" : String.format(Locale.GERMANY, "%.1f", t.trimDiff)).append('\n');
        }
        return sb.toString();
    }
}
