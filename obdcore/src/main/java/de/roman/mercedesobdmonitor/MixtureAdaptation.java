package de.roman.mercedesobdmonitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Gemischadaption je Bank aus Mode 06 des ME9.7 (Android-frei, testbar): MID 81/82 = Kraftstoffsystem Bank 1/2,
 * TID 80 = Adaption in Prozent, TID 82 = Adaptionsfaktor. Referenz laut Mode-06-Tabelle des Werkstatthandbuchs:
 * ±4,5 % bzw. Faktor 0,70–1,30 (Beispielwerte; die echten Grenzen liefert das Steuergerät mit). Bank 1 = rechte
 * Zylinderbank (Zyl. 1–3, Sonde G3/4), Bank 2 = links.
 *
 * <p>Anders als STFT/LTFT ist das der vom Steuergerät gelernte Wert selbst – unabhängig davon, wie warm die aktuelle
 * Fahrt ist. Der Verlauf zeigt, ob eine Bank Richtung Grenze wandert.
 */
public final class MixtureAdaptation {
    public static final int MID_BANK1 = 0x81;
    public static final int MID_BANK2 = 0x82;
    public static final int TID_PERCENT = 0x80;
    public static final int TID_FACTOR = 0x82;
    /** Referenz aus dem Handbuch, falls das Steuergerät keine Grenzen mitliefert. */
    static final double REF_PERCENT = 4.5;
    static final double REF_FACTOR_MIN = 0.70;
    static final double REF_FACTOR_MAX = 1.30;
    /** Gespeicherte Lesungen (die ältesten fallen heraus). */
    public static final int MAX_READINGS = 40;
    /** Änderung der Faktor-Differenz B1−B2 (erste gegen letzte Lesungen), ab der sie als steigend gilt. */
    static final double TREND_STEP = 0.03;

    /** Lesungen innerhalb dieser Zeit ersetzen die vorige (mehrmals Lesen in der Garage verdrängt keinen Verlauf). */
    static final long SAME_SESSION_MS = 3_600_000L;

    /** Eine Lesung: NaN = von diesem Steuergerät nicht geliefert oder Test noch nicht gelaufen. */
    public static final class Reading {
        public final long timeMs;
        public final double coolantC;
        public final double percent1, factor1, percent2, factor2;
        /** Fahrgestellnummer aus 09 02; "" = unbekannt. Der Verlauf vergleicht nur Lesungen desselben Fahrzeugs. */
        public final String vin;
        /** Grenzen, die das Steuergerät mitgeliefert hat (nur für die Anzeige der aktuellen Lesung; NaN = keine). */
        double factorMin1 = Double.NaN, factorMax1 = Double.NaN, factorMin2 = Double.NaN, factorMax2 = Double.NaN;
        double percentLimit1 = Double.NaN, percentLimit2 = Double.NaN;

        public Reading(long timeMs, double coolantC, double percent1, double factor1, double percent2, double factor2) {
            this(timeMs, coolantC, percent1, factor1, percent2, factor2, "");
        }

        public Reading(long timeMs, double coolantC, double percent1, double factor1, double percent2, double factor2,
                String vin) {
            this.timeMs = timeMs;
            this.coolantC = coolantC;
            this.percent1 = percent1;
            this.factor1 = factor1;
            this.percent2 = percent2;
            this.factor2 = factor2;
            this.vin = vin == null ? "" : vin;
        }

        boolean sameValues(Reading o) {
            return same(percent1, o.percent1) && same(factor1, o.factor1) && same(percent2, o.percent2)
                    && same(factor2, o.factor2);
        }

        private static boolean same(double a, double b) {
            return Double.isNaN(a) ? Double.isNaN(b) : !Double.isNaN(b) && Math.abs(a - b) < 1e-4;
        }

        boolean sameVehicle(Reading o) {
            return vin.isEmpty() || o.vin.isEmpty() || vin.equals(o.vin);
        }

        public boolean any() {
            return !Double.isNaN(percent1) || !Double.isNaN(factor1) || !Double.isNaN(percent2) || !Double.isNaN(factor2);
        }

        /** Faktor-Differenz B1−B2 oder NaN. */
        public double factorDiff() {
            return factor1 - factor2;
        }
    }

    private final List<Reading> readings = new ArrayList<>();

    /** Ob dieser Mode-06-Lauf überhaupt Tests unter MID 81/82 enthält. */
    public static boolean present(List<Mode06Overview.Test> tests) {
        for (Mode06Overview.Test t : tests) if (t.mid == MID_BANK1 || t.mid == MID_BANK2) return true;
        return false;
    }

    /** Werte aus einem Mode-06-Lauf; Tests, die noch nicht gelaufen sind, zählen als fehlend. */
    public static Reading from(List<Mode06Overview.Test> tests, long timeMs, double coolantC) {
        return from(tests, timeMs, coolantC, "");
    }

    public static Reading from(List<Mode06Overview.Test> tests, long timeMs, double coolantC, String vin) {
        double p1 = value(tests, MID_BANK1, TID_PERCENT, 0), f1 = value(tests, MID_BANK1, TID_FACTOR, 0);
        double p2 = value(tests, MID_BANK2, TID_PERCENT, 0), f2 = value(tests, MID_BANK2, TID_FACTOR, 0);
        Reading r = new Reading(timeMs, coolantC, p1, f1, p2, f2, vin);
        r.factorMin1 = value(tests, MID_BANK1, TID_FACTOR, -1);
        r.factorMax1 = value(tests, MID_BANK1, TID_FACTOR, 1);
        r.factorMin2 = value(tests, MID_BANK2, TID_FACTOR, -1);
        r.factorMax2 = value(tests, MID_BANK2, TID_FACTOR, 1);
        r.percentLimit1 = Math.max(Math.abs(value(tests, MID_BANK1, TID_PERCENT, -1)), Math.abs(value(tests, MID_BANK1, TID_PERCENT, 1)));
        r.percentLimit2 = Math.max(Math.abs(value(tests, MID_BANK2, TID_PERCENT, -1)), Math.abs(value(tests, MID_BANK2, TID_PERCENT, 1)));
        return r;
    }

    /** {@code which}: 0 = Wert, -1 = untere Grenze, 1 = obere Grenze (NaN, wenn das Steuergerät keine liefert). */
    private static double value(List<Mode06Overview.Test> tests, int mid, int tid, int which) {
        for (Mode06Overview.Test t : tests) {
            if (t.mid != mid || t.tid != tid || t.status == Mode06Overview.Status.NOT_RUN) continue;
            if (which < 0 && !t.hasMin || which > 0 && !t.hasMax) return Double.NaN;
            Double v = Mode06Overview.scaled(which == 0 ? t.value : which < 0 ? t.min : t.max, t.uasid);
            if (v != null) return v;
        }
        return Double.NaN;
    }

    /**
     * Lesung aufnehmen (nur wenn sie etwas enthält). Gleiche Werte wie die letzte Lesung desselben Fahrzeugs werden
     * nicht doppelt gespeichert; innerhalb einer Stunde ersetzt die neue Lesung die letzte.
     */
    public void add(Reading r) {
        if (r == null || !r.any()) return;
        Reading last = readings.isEmpty() ? null : readings.get(readings.size() - 1);
        if (last != null && last.sameVehicle(r) && r.timeMs >= last.timeMs) {
            if (last.sameValues(r)) return;
            if (r.timeMs - last.timeMs < SAME_SESSION_MS) readings.remove(readings.size() - 1);
        }
        readings.add(r);
        readings.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        while (readings.size() > MAX_READINGS) readings.remove(0);
    }

    public List<Reading> readings() {
        return Collections.unmodifiableList(readings);
    }

    /** Grenze in Richtung des Faktors: die des Steuergeräts, sonst die Referenz aus dem Handbuch. */
    static double limit(double factor, double min, double max) {
        return factor >= 1 ? (Double.isNaN(max) || max <= 1 ? REF_FACTOR_MAX : max)
                : (Double.isNaN(min) || min >= 1 ? REF_FACTOR_MIN : min);
    }

    /** Anteil des Wegs von „neutral“ (1,0) bis zur Grenze in Prozent (0 = neutral, 100 = an der Grenze). */
    static double usedPercent(double factor, double min, double max) {
        if (Double.isNaN(factor)) return Double.NaN;
        return Math.abs(factor - 1) / Math.abs(limit(factor, min, max) - 1) * 100;
    }

    static double usedPercent(double factor) {
        return usedPercent(factor, Double.NaN, Double.NaN);
    }

    /** Abschnitt für den Monitortest-Bericht: aktuelle Lesung, Verlauf, Einordnung. */
    public String report(Reading now, ZoneId zone) {
        StringBuilder sb = new StringBuilder("GEMISCHADAPTION JE BANK (Steuergerät, MID 81/82)\n");
        if (now == null || !now.any()) {
            return sb.append("  nicht geliefert – Test noch nicht gelaufen (nach einer warmen Fahrt erneut lesen) oder "
                    + "anderes Steuergerät").toString();
        }
        sb.append(bank(1, "Bank 1 (rechts, Zyl. 1–3)", now.factor1, now.factorMin1, now.factorMax1, now.percent1, now.percentLimit1));
        sb.append(bank(2, "Bank 2 (links, Zyl. 4–6)", now.factor2, now.factorMin2, now.factorMax2, now.percent2, now.percentLimit2));
        double diff = now.factorDiff();
        if (!Double.isNaN(diff)) {
            sb.append(String.format(Locale.GERMANY, "  Differenz B1−B2: %+.3f (≈ %+.1f %% Kraftstoff)%n", diff, diff * 100));
        }
        sb.append("  Referenz Werkstatthandbuch: Faktor 0,70–1,30, Prozent ±4,5 %; >1 = Steuergerät gibt mehr Kraftstoff "
                + "(Bank lief mager)\n");
        List<Reading> withDiff = new ArrayList<>();
        for (Reading r : readings) if (!Double.isNaN(r.factorDiff()) && r.sameVehicle(now)) withDiff.add(r);
        if (withDiff.size() >= 2) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(zone);
            sb.append("  Verlauf (neueste zuerst):\n");
            for (int i = withDiff.size() - 1, n = 0; i >= 0 && n < 5; i--, n++) {
                Reading r = withDiff.get(i);
                sb.append(String.format(Locale.GERMANY, "  %s · B1 %.3f · B2 %.3f · Δ %+.3f%s%n",
                        fmt.format(Instant.ofEpochMilli(r.timeMs)), r.factor1, r.factor2, r.factorDiff(),
                        Double.isNaN(r.coolantC) ? "" : String.format(Locale.GERMANY, " · %.0f °C", r.coolantC)));
            }
            double first = withDiff.get(0).factorDiff(), last = withDiff.get(withDiff.size() - 1).factorDiff();
            String trend = last - first >= TREND_STEP ? "Bankunterschied wächst"
                    : first - last >= TREND_STEP ? "Bankunterschied nimmt ab" : "Bankunterschied stabil";
            sb.append(String.format(Locale.GERMANY, "  %s (%+.3f → %+.3f über %d Lesungen)%n", trend, first, last,
                    withDiff.size()));
        } else {
            sb.append("  Verlauf: erst ab zwei Lesungen – Monitortests nach jeder längeren warmen Fahrt lesen\n");
        }
        return sb.toString().trim();
    }

    private static String bank(int bank, String name, double factor, double min, double max, double percent,
            double percentLimit) {
        StringBuilder sb = new StringBuilder("  ").append(name).append(": ");
        if (Double.isNaN(factor) && Double.isNaN(percent)) return sb.append("–\n").toString();
        if (!Double.isNaN(factor)) {
            double used = usedPercent(factor, min, max);
            sb.append(String.format(Locale.GERMANY, "Faktor %.3f (%.0f %% bis zur Grenze %.2f)", factor, used,
                    limit(factor, min, max)));
            if (used >= 80) {
                // >1: Steuergerät gibt Kraftstoff dazu (Bank lief mager), <1: nimmt weg (lief fett)
                String code = factor >= 1 ? (bank == 1 ? "P0171 (mager)" : "P0174 (mager)")
                        : (bank == 1 ? "P0172 (fett)" : "P0175 (fett)");
                sb.append(" ⚠ nahe der Grenze – ").append(code).append(" droht");
            }
        }
        if (!Double.isNaN(percent)) {
            if (!Double.isNaN(factor)) sb.append(" · ");
            sb.append(String.format(Locale.GERMANY, "%+.2f %%", percent));
            double lim = Double.isNaN(percentLimit) || percentLimit <= 0 ? REF_PERCENT : percentLimit;
            if (Math.abs(percent) >= lim * 0.8) sb.append(" ⚠");
        }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ Speichern

    /** Eine Zeile je Lesung: Zeit;Kühlmittel;P1;F1;P2;F2;FIN (leer = NaN bzw. unbekannt). */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Reading r : readings) {
            sb.append(r.timeMs);
            for (double v : new double[] {r.coolantC, r.percent1, r.factor1, r.percent2, r.factor2}) {
                sb.append(';').append(Double.isNaN(v) ? "" : String.format(Locale.US, "%.5f", v));
            }
            sb.append(';').append(r.vin.replace(";", "")).append('\n');
        }
        return sb.toString();
    }

    public static MixtureAdaptation parse(String text) {
        MixtureAdaptation m = new MixtureAdaptation();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            String[] f = line.split(";", -1);
            if (f.length != 7) continue;
            try {
                double[] v = new double[5];
                for (int i = 0; i < 5; i++) v[i] = f[i + 1].isEmpty() ? Double.NaN : Double.parseDouble(f[i + 1]);
                m.readings.add(new Reading(Long.parseLong(f[0]), v[0], v[1], v[2], v[3], v[4], f[6]));
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }
}
