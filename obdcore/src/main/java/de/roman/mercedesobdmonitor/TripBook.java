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

    /**
     * Kraftstoff im Motoröl (WIS GF07.61-P-2003V): Nach kalten Kurzstrecken verdampft Kraftstoff aus dem warm
     * werdenden Öl über die Kurbelgehäuseentlüftung; die Gemischabweichung kann im Leerlauf bis 30 % betragen und
     * fließt in die Adaption. Trims sind dann nicht mit warmen Normalfahrten vergleichbar. Schwellen sind Richtwerte.
     */
    public enum TrimQuality {
        /** Kühldaten fehlen (ältere Version oder Log ohne 01 05). */
        UNKNOWN,
        /** nie richtig warm: Kühlmittel blieb unter {@link #COLD_SHORT_MAX_C} oder warm kürzer als {@link #COLD_SHORT_WARM_MS} */
        COLD_SHORT,
        /** Kraftstoff im Öl möglich: seit der letzten langen warmen Fahrt gab es kalte Kurzstrecken */
        DILUTED,
        /** vergleichbar */
        OK
    }

    public static final double COLD_SHORT_MAX_C = 70;
    public static final long COLD_SHORT_WARM_MS = 5 * 60_000L;
    /** So lange warm, dann gilt das Öl als ausgedampft und die Fahrt selbst als vergleichbar. */
    public static final long FLUSH_WARM_MS = 20 * 60_000L;

    /** Einordnung je Fahrt (gleiche Reihenfolge wie {@code trips}, also zeitlich aufsteigend). */
    public static List<TrimQuality> trimQuality(List<TripComputer.Trip> trips) {
        List<TrimQuality> out = new ArrayList<>();
        boolean diluted = false;
        for (TripComputer.Trip t : trips) {
            if (!t.coolantKnown()) {
                // ohne Kühldaten unbekannt; der Zustand „Kraftstoff im Öl“ bleibt vorsichtshalber erhalten
                out.add(TrimQuality.UNKNOWN);
                continue;
            }
            boolean cold = t.maxCoolantC < COLD_SHORT_MAX_C || t.warmMs < COLD_SHORT_WARM_MS;
            if (cold) {
                out.add(TrimQuality.COLD_SHORT);
                diluted = true;
            } else if (t.warmMs >= FLUSH_WARM_MS) {
                out.add(TrimQuality.OK);   // lange warm: das Öl dampft aus, die Fahrt zählt
                diluted = false;
            } else {
                out.add(diluted ? TrimQuality.DILUTED : TrimQuality.OK);
            }
        }
        return out;
    }

    /** Folgt ein Teilstück so dicht auf ein offenes Ende, gehört es zur selben Fahrt (Logwechsel mitten in der Fahrt). */
    public static final long JOIN_GAP_MS = TripComputer.GAP_MS;

    /**
     * Neue Teilstücke einfügen. Überschneidet sich ein neues zeitlich mit gespeicherten, ersetzt es diese (ein Log,
     * das beim letzten Einlesen noch wuchs, oder derselbe Log erneut importiert). Gespeichert bleiben Teilstücke je
     * Log – verbunden wird erst in {@link #joined}, sonst löschte ein nachwachsendes Teilstück eines Logs die schon
     * verbundene Fahrt samt dem Teil aus dem anderen Log. Ergebnis zeitlich sortiert, höchstens {@link #MAX_TRIPS}.
     */
    public static List<TripComputer.Trip> merge(List<TripComputer.Trip> stored, List<TripComputer.Trip> fresh) {
        List<TripComputer.Trip> out = new ArrayList<>(stored);
        for (TripComputer.Trip f : fresh) {
            out.removeIf(t -> t.startMs <= f.endMs && f.startMs <= t.endMs);
            out.add(f);
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        while (out.size() > MAX_TRIPS) out.remove(0);
        return out;
    }

    /**
     * Teilstücke zu Fahrten verbinden: nur an einem offenen Ende (Log endete bei laufendem Motor) und bei weniger als
     * {@link #JOIN_GAP_MS} Abstand. Eine Grenze aus „Motor aus“ oder Neustart bleibt eine Grenze, auch wenn die Pause
     * kürzer war (Tankstopp mit 60 s Motor aus = zwei Fahrten, wie im Trim-Verlauf).
     */
    public static List<TripComputer.Trip> joined(List<TripComputer.Trip> pieces) {
        List<TripComputer.Trip> out = new ArrayList<>();
        for (TripComputer.Trip t : pieces) {
            int last = out.size() - 1;
            TripComputer.Trip prev = last >= 0 ? out.get(last) : null;
            if (prev != null && prev.openEnd && t.startMs >= prev.endMs && t.startMs - prev.endMs < JOIN_GAP_MS) {
                out.set(last, TripComputer.Trip.join(prev, t));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Bericht: Summe der letzten 30 Tage, darunter die neuesten Fahrten mit je zwei Zeilen, z. B.
     * „19.11. 18:38 · 10 min · 6,1 km · Ø 12,4 l/100 km“ und „  Leerlauf 25 % · max 75 km/h · Δ Trim B1−B2 +7,7 %“.
     */
    public static String report(List<TripComputer.Trip> pieces, ZoneId zone, long nowMs, int maxRows) {
        List<TripComputer.Trip> trips = joined(pieces);
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
        List<TrimQuality> quality = trimQuality(trips);
        double diffSum = 0;
        int diffN = 0, skipped = 0, unknown = 0;
        for (int i = 0; i < trips.size(); i++) {   // gleiches 30-Tage-Fenster wie die Zeile darüber
            TripComputer.Trip t = trips.get(i);
            if (t.trimDiff == null || t.startMs < from) continue;
            switch (quality.get(i)) {
                case OK -> {
                    diffSum += t.trimDiff;
                    diffN++;
                }
                case UNKNOWN -> unknown++;
                default -> skipped++;
            }
        }
        if (diffN > 0 || skipped > 0 || unknown > 0) {
            sb.append('\n').append(diffN == 0 ? "Δ Trim B1−B2: keine vergleichbare Fahrt"
                    : String.format(Locale.GERMANY, "Δ Trim B1−B2 aus vergleichbaren Fahrten: Ø %+.1f %% (%d)", diffSum / diffN, diffN));
            if (skipped > 0) sb.append(String.format(Locale.GERMANY, " · %d nach Kaltstarts ausgelassen", skipped));
            if (unknown > 0) sb.append(String.format(Locale.GERMANY, " · %d ohne Kühldaten", unknown));
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
            TrimQuality q = quality.get(i);
            if (t.coolantKnown()) {
                sb.append(String.format(Locale.GERMANY, " · warm %d min", Math.round(t.warmMs / 60_000.0)));
            }
            if (t.trimDiff != null) {
                sb.append(String.format(Locale.GERMANY,
                        q == TrimQuality.OK ? " · Δ Trim B1−B2 %+.1f %%" : " · (Δ Trim %+.1f %%)",
                        t.trimDiff));
            }
            if (q == TrimQuality.COLD_SHORT) {
                sb.append(String.format(Locale.GERMANY, "\n  kalte Kurzstrecke (max %.0f °C) – Kraftstoff gelangt ins Öl",
                        t.maxCoolantC));
            } else if (q == TrimQuality.DILUTED) {
                sb.append("\n  nach Kaltstarts: Kraftstoff im Öl möglich – Trims nicht vergleichbar");
            }
        }
        if (trips.size() > maxRows) sb.append(String.format(Locale.GERMANY, "\n… %d ältere", trips.size() - maxRows));
        return sb.toString();
    }

    /** CSV zum Teilen (Semikolon, deutsche Dezimalzahlen wie in Tabellenkalkulationen üblich). */
    public static String csv(List<TripComputer.Trip> pieces, ZoneId zone) {
        List<TripComputer.Trip> trips = joined(pieces);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.GERMANY).withZone(zone);
        StringBuilder sb = new StringBuilder("start;ende;motorlaufzeit_min;strecke_km;kraftstoff_l;l_100km;leerlauf_prozent;"
                + "max_kmh;trim_b1_minus_b2_prozent;warm_min;max_kuehlmittel_c;trim_einordnung\n");
        List<TrimQuality> quality = trimQuality(trips);
        for (int i = 0; i < trips.size(); i++) {
            TripComputer.Trip t = trips.get(i);
            Double l100 = t.litersPer100Km();
            sb.append(fmt.format(Instant.ofEpochMilli(t.startMs))).append(';').append(fmt.format(Instant.ofEpochMilli(t.endMs)))
                    .append(String.format(Locale.GERMANY, ";%.1f;%.2f;", t.runMs / 60_000.0, t.km))
                    .append(t.fuelKnown() ? String.format(Locale.GERMANY, "%.2f", t.liters) : "").append(';')
                    .append(l100 == null ? "" : String.format(Locale.GERMANY, "%.1f", l100))
                    .append(String.format(Locale.GERMANY, ";%.0f;%.0f;", 100 * t.idleShare(), t.maxKmh))
                    .append(t.trimDiff == null ? "" : String.format(Locale.GERMANY, "%.1f", t.trimDiff)).append(';')
                    .append(t.coolantKnown() ? String.format(Locale.GERMANY, "%.0f", t.warmMs / 60_000.0) : "").append(';')
                    .append(t.coolantKnown() ? String.format(Locale.GERMANY, "%.0f", t.maxCoolantC) : "").append(';')
                    .append(switch (quality.get(i)) {
                        case OK -> "vergleichbar";
                        case COLD_SHORT -> "kalte_kurzstrecke";
                        case DILUTED -> "kraftstoff_im_oel_moeglich";
                        case UNKNOWN -> "";
                    }).append('\n');
        }
        return sb.toString();
    }
}
