package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mode-06-Gesamtübersicht: alle On-Board-Monitortests des Steuergeräts mit den
 * mitgelieferten Grenzwerten (ISO 15031-5 / SAE J1979, CAN).
 *
 * Bewertet wird auf Rohwerten (vorzeichenrichtig): Wert und Grenzen haben dieselbe
 * Skalierung, das Urteil hängt also nicht von der UASID-Tabelle ab – sie dient nur
 * der Anzeige. Android-unabhängig, unit-testbar.
 */
public final class Mode06Overview {
    public enum Status { FAIL, CLOSE, OK, NO_LIMIT, NOT_RUN }

    /** Unter dieser Reserve zur Grenze (Prozent) gilt ein Test als knapp. */
    public static final int CLOSE_PERCENT = 10;

    public static final class Test {
        public final int mid;
        public final int tid;
        public final int uasid;
        /** Vorzeichenrichtig interpretierte Rohwerte. */
        public final int value;
        public final int min;
        public final int max;
        public final boolean hasMin;
        public final boolean hasMax;
        public final Status status;
        /** Abstand zur nächsten Grenze in Prozent (0–100); null, wenn nicht sinnvoll berechenbar. */
        public final Integer reservePercent;

        Test(int mid, int tid, int uasid, int value, int min, int max, boolean hasMin, boolean hasMax,
             Status status, Integer reservePercent) {
            this.mid = mid;
            this.tid = tid;
            this.uasid = uasid;
            this.value = value;
            this.min = min;
            this.max = max;
            this.hasMin = hasMin;
            this.hasMax = hasMax;
            this.status = status;
            this.reservePercent = reservePercent;
        }
    }

    private Mode06Overview() { }

    // ---------------------------------------------------------------- Lesen

    /**
     * Liest alle unterstützten OBDMIDs: Bitmaps 00, 20, … (Bit 32 meldet die nächste),
     * danach jeden Monitor einzeln. Einzelne Timeouts werden übersprungen.
     */
    public static List<Test> read(InspectionReader.Transport transport) throws IOException {
        return read(transport, new ArrayList<>());
    }

    /** @param problems nimmt auf, was nicht gelesen werden konnte (für {@link #report(List, List)}) */
    public static List<Test> read(InspectionReader.Transport transport, List<String> problems) throws IOException {
        TolerantQuery q = new TolerantQuery(transport);
        Set<Integer> mids = new TreeSet<>();
        for (int base = 0x00; base <= 0xE0; base += 0x20) {
            String raw = q.query(Mode06.command(base), 3000);
            // NO DATA auf 06 00 heißt „kein Mode 06“; jede weitere Bitmap hat das Steuergerät selbst angekündigt
            if (base == 0 && raw != null && ObdParser.isNoData(raw) && !ObdParser.isLinkError(raw)) break;
            Set<Integer> supported = raw == null || ObdParser.isLinkError(raw) ? Set.of() : Mode06.supportedMids(raw, base);
            if (supported.isEmpty() && raw != null && !ObdParser.isLinkError(raw) && Mode06.hasBitmap(raw, base)) break;
            if (supported.isEmpty()) {
                problems.add(String.format(Locale.US, "Testliste ab %s (%s)", Mode06.command(base), reason(raw)));
                break;
            }
            mids.addAll(supported);
            if (!supported.contains(base + 0x20)) break;
        }
        List<Test> tests = new ArrayList<>();
        for (int mid : mids) {
            if (mid % 0x20 == 0) continue; // Bitmap-MID, kein Test
            String raw = q.query(Mode06.command(mid), 3000);
            boolean any = false;
            if (raw != null && !ObdParser.isNoData(raw) && !ObdParser.isLinkError(raw)) {
                for (Mode06.TestResult r : Mode06.results(raw)) {
                    if (r.mid != mid) continue;
                    tests.add(evaluate(r));
                    any = true;
                }
            }
            if (!any || ObdParser.hasIncompleteMessage(raw)) problems.add(midName(mid) + " (" + reason(raw) + ")");
        }
        return tests;
    }

    private static String reason(String raw) {
        if (raw == null) return "Zeitüberschreitung";
        if (ObdParser.isLinkError(raw)) return "Busfehler";
        if (ObdParser.isNoData(raw)) return "keine Daten";
        return ObdParser.hasIncompleteMessage(raw) ? "Antwort unvollständig" : "keine gültige Antwort";
    }

    // ---------------------------------------------------------------- Bewertung

    public static Test evaluate(Mode06.TestResult r) {
        boolean signed = r.uasid >= 0x81 && r.uasid <= 0xFE;
        int v = signed ? (short) r.value : r.value;
        int min = signed ? (short) r.min : r.min;
        int max = signed ? (short) r.max : r.max;

        // J1979: Test im aktuellen Zyklus noch nicht gelaufen → Wert und Grenzen 0.
        if (r.value == 0 && r.min == 0 && r.max == 0) {
            return new Test(r.mid, r.tid, r.uasid, v, min, max, false, false, Status.NOT_RUN, null);
        }
        int lowest = signed ? Short.MIN_VALUE : 0;
        int highest = signed ? Short.MAX_VALUE : 0xFFFF;
        boolean hasMin = min > lowest;
        boolean hasMax = max < highest;
        // Grenzen 0/0 bei vorhandenem Wert: reiner Informationswert ohne Grenze.
        if (r.min == 0 && r.max == 0) hasMin = hasMax = false;

        if (!hasMin && !hasMax) {
            return new Test(r.mid, r.tid, r.uasid, v, min, max, false, false, Status.NO_LIMIT, null);
        }
        // Kennwerte statt Prüfungen: untere = obere Grenze (z. B. Umschaltschwelle der Nachkat-Sonde, J1979 TID 01/02).
        // Grenzen bleiben sichtbar, aber ohne Reserve und ohne „knapp“; eine Abweichung um eine Stelle ist Rundung
        // (Fahrzeugtest 21.11.2026: 0,7427 V … 0,7427 V gab „knapp“).
        if (hasMin && hasMax && min == max && Math.abs(v - min) <= 1) {
            return new Test(r.mid, r.tid, r.uasid, v, min, max, true, true, Status.OK, null);
        }
        boolean fail = (hasMin && v < min) || (hasMax && v > max);
        Integer reserve;
        if (fail) {
            reserve = 0;
        } else if (hasMin && hasMax) {
            double half = (max - min) / 2.0;
            reserve = half <= 0 ? 0 : percent(Math.min(v - min, max - v) / half);
        } else if (hasMax) {
            reserve = max <= 0 ? null : percent((max - v) / (double) max);
        } else {
            reserve = min <= 0 ? null : percent((v - min) / (double) min);
        }
        // Schwellenwert-Tests (J1979 TID 01/02, ME9.7-Nachkat-Sonde TID 81/82) liegen bauartbedingt dicht an der
        // Schwelle – auch das Beispiel im Werkstatthandbuch (591,8 mV bei Grenze 601,6 mV). Dort gilt nur bestanden
        // oder nicht, „knapp“ wäre ein Fehlalarm.
        Status status = fail ? Status.FAIL
                : reserve != null && reserve < CLOSE_PERCENT && !thresholdTest(r.mid, r.tid) ? Status.CLOSE : Status.OK;
        return new Test(r.mid, r.tid, r.uasid, v, min, max, hasMin, hasMax, status, reserve);
    }

    /** Tests, bei denen nur bestanden/nicht bestanden zählt (Sondenschwellen). */
    static boolean thresholdTest(int mid, int tid) {
        boolean o2 = mid >= 0x01 && mid <= 0x10;
        boolean rearMe97 = mid == 0x02 || mid == 0x06;
        return o2 && (tid == 0x01 || tid == 0x02) || rearMe97 && (tid == 0x81 || tid == 0x82);
    }

    private static int percent(double fraction) {
        return (int) Math.max(0, Math.min(100, Math.floor(fraction * 100)));
    }

    // ---------------------------------------------------------------- Bezeichnungen

    private static final Map<Integer, String> MIDS = new HashMap<>();

    static {
        String[] o2 = {"Lambdasonde Bank 1 Sonde 1", "Lambdasonde Bank 1 Sonde 2", "Lambdasonde Bank 1 Sonde 3",
                "Lambdasonde Bank 1 Sonde 4", "Lambdasonde Bank 2 Sonde 1", "Lambdasonde Bank 2 Sonde 2",
                "Lambdasonde Bank 2 Sonde 3", "Lambdasonde Bank 2 Sonde 4"};
        for (int i = 0; i < o2.length; i++) {
            MIDS.put(0x01 + i, o2[i]);
            MIDS.put(0x41 + i, o2[i].replace("Lambdasonde", "Sondenheizung"));
        }
        for (int b = 1; b <= 4; b++) {
            MIDS.put(0x20 + b, "Katalysator Bank " + b);
            MIDS.put(0x30 + b, "AGR Bank " + b);
            MIDS.put(0x34 + b, "Nockenwellenverstellung (VVT) Bank " + b);
            MIDS.put(0x60 + b, "Beheizter Katalysator Bank " + b);
            MIDS.put(0x70 + b, "Sekundärluft " + b);
            MIDS.put(0x80 + b, "Kraftstoffsystem Bank " + b);
        }
        MIDS.put(0x39, "Tankentlüftung (Deckel offen / 0,150\")");
        MIDS.put(0x3A, "Tankentlüftung (0,090\")");
        MIDS.put(0x3B, "Tankentlüftung (0,040\")");
        MIDS.put(0x3C, "Tankentlüftung (0,020\")");
        MIDS.put(0x3D, "Regenerierventil (Spülstrom)");
        MIDS.put(0x85, "Ladedruckregelung Bank 1");
        MIDS.put(0x86, "Ladedruckregelung Bank 2");
        MIDS.put(0x90, "NOx-Speicherkat Bank 1");
        MIDS.put(0x91, "NOx-Speicherkat Bank 2");
        MIDS.put(0x98, "NOx-Katalysator Bank 1");
        MIDS.put(0x99, "NOx-Katalysator Bank 2");
        MIDS.put(0xA1, "Aussetzer allgemein");
        for (int cyl = 1; cyl <= 12; cyl++) MIDS.put(0xA1 + cyl, "Aussetzer Zylinder " + cyl);
        MIDS.put(0xB0, "Partikelfilter Bank 1");
        MIDS.put(0xB1, "Partikelfilter Bank 2");
    }

    /** Standardisierte Test-IDs (Lambdasonden, Aussetzer); ab 0x80 herstellerspezifisch. */
    private static final Map<Integer, String> TIDS = new HashMap<>();

    static {
        TIDS.put(0x01, "Schwelle fett→mager");
        TIDS.put(0x02, "Schwelle mager→fett");
        TIDS.put(0x03, "untere Spannung Schaltzeit");
        TIDS.put(0x04, "obere Spannung Schaltzeit");
        TIDS.put(0x05, "Schaltzeit fett→mager");
        TIDS.put(0x06, "Schaltzeit mager→fett");
        TIDS.put(0x07, "minimale Sondenspannung");
        TIDS.put(0x08, "maximale Sondenspannung");
        TIDS.put(0x09, "Zeit zwischen Wechseln");
        TIDS.put(0x0A, "Sondenperiode");
        TIDS.put(0x0B, "Aussetzer EWMA (10 Zyklen)");
        TIDS.put(0x0C, "Aussetzer aktueller Zyklus");
    }

    public static String midName(int mid) {
        String n = MIDS.get(mid);
        return n != null ? n : String.format(Locale.US, "Monitor 0x%02X", mid);
    }

    /**
     * Herstellerspezifische Tests des ME9.7 (M272/M273) nach der Mode-06-Referenztabelle im Werkstatthandbuch
     * (Werte dort nur Beispiele; Grenzen liefert das Steuergerät selbst).
     */
    private static final Map<Integer, String> ME97 = new HashMap<>();

    static {
        for (int[] m : new int[][] {{0x01, 1}, {0x05, 2}}) {
            ME97.put(m[0] << 8 | 0x83, "Breitbandsonde Rohwert (Ref. 0,30–2,00)");
            ME97.put(m[0] << 8 | 0x84, "Breitbandsonde Abweichung (Ref. ±0,03)");
        }
        for (int mid : new int[] {0x02, 0x06}) {
            ME97.put(mid << 8 | 0x81, "Sprungsonde untere Spannung");
            ME97.put(mid << 8 | 0x82, "Sprungsonde obere Spannung");
            ME97.put(mid << 8 | 0x83, "Sprungsonde Restspannung");
        }
        for (int mid : new int[] {0x21, 0x22}) ME97.put(mid << 8 | 0x84, "Sauerstoffspeicher (Ref. 1–16 mg O₂)");
        for (int mid : new int[] {0x41, 0x45}) ME97.put(mid << 8 | 0x85, "Heizung Sondentemperatur (Ref. 700–1263 °C)");
        for (int mid : new int[] {0x42, 0x46}) ME97.put(mid << 8 | 0x81, "Heizung Widerstand (Ref. 0–1344 Ω)");
        for (int mid : new int[] {0x71, 0x72}) {
            ME97.put(mid << 8 | 0x80, "Sekundärluft Wirkung (Ref. 0,37–2,00)");
            ME97.put(mid << 8 | 0x81, "Sekundärluft Restwert (Ref. 0–0,20)");
        }
        for (int mid : new int[] {0x81, 0x82}) {
            ME97.put(mid << 8 | 0x80, "Gemischadaption % (Ref. ±4,5 %)");
            ME97.put(mid << 8 | 0x82, "Gemischadaption Faktor (Ref. 0,70–1,30)");
        }
    }

    /** Name eines Tests: ME9.7-Bezeichnung für herstellerspezifische Tests, sonst SAE-Bezeichnung. */
    public static String testName(int mid, int tid) {
        String n = ME97.get(mid << 8 | tid);
        return n != null ? n : tidName(tid);
    }

    public static String tidName(int tid) {
        String n = TIDS.get(tid);
        if (n != null) return n;
        return String.format(Locale.US, tid >= 0x80 ? "Test 0x%02X (herstellerspezifisch)" : "Test 0x%02X", tid);
    }

    /** Skalierung nach UASID (Faktor, Offset, Einheit) – nur für die Anzeige. */
    private static final class Scale {
        final double factor;
        final double offset;
        final String unit;

        Scale(double factor, double offset, String unit) {
            this.factor = factor;
            this.offset = offset;
            this.unit = unit;
        }
    }

    private static final Map<Integer, Scale> SCALES = new HashMap<>();

    private static void scale(int uasid, double factor, String unit) {
        SCALES.put(uasid, new Scale(factor, 0, unit));
    }

    static {
        scale(0x01, 1, ""); scale(0x02, 0.1, ""); scale(0x03, 0.01, ""); scale(0x04, 0.001, "");
        scale(0x05, 0.0000305, ""); scale(0x06, 0.000305, "");
        scale(0x07, 0.25, "U/min"); scale(0x08, 0.01, "km/h"); scale(0x09, 1, "km/h");
        scale(0x0A, 0.000122, "V"); scale(0x0B, 0.001, "V"); scale(0x0C, 0.01, "V");
        scale(0x0D, 0.00390625, "mA"); scale(0x0E, 0.001, "A"); scale(0x0F, 0.01, "A");
        scale(0x10, 1, "ms"); scale(0x11, 100, "ms"); scale(0x12, 1, "s");
        scale(0x13, 1, "mΩ"); scale(0x14, 1, "Ω"); scale(0x15, 1, "kΩ");
        SCALES.put(0x16, new Scale(0.1, -40, "°C"));
        scale(0x17, 0.01, "kPa"); scale(0x18, 0.0117, "kPa"); scale(0x19, 0.079, "kPa");
        scale(0x1A, 1, "kPa"); scale(0x1B, 10, "kPa");
        scale(0x1C, 0.01, "°"); scale(0x1D, 0.5, "°");
        scale(0x1E, 0.0000305, "λ"); scale(0x1F, 0.05, "AFR"); scale(0x20, 0.0039062, "");
        scale(0x21, 1, "mHz"); scale(0x22, 1, "Hz"); scale(0x23, 1, "kHz");
        scale(0x24, 1, "Zähler"); scale(0x25, 1, "km");
        scale(0x26, 0.1, "mV/ms"); scale(0x27, 0.01, "g/s"); scale(0x28, 1, "g/s");
        scale(0x29, 0.25, "Pa/s"); scale(0x2A, 0.001, "kg/h"); scale(0x2B, 1, "Wechsel");
        scale(0x2C, 0.01, "g/Zyl."); scale(0x2D, 0.01, "mg/Hub");
        scale(0x2F, 0.01, "%"); scale(0x30, 0.001526, "%"); scale(0x31, 0.001, "l");
        scale(0x81, 1, ""); scale(0x82, 0.1, ""); scale(0x83, 0.01, ""); scale(0x84, 0.001, "");
        scale(0x85, 0.0000305, ""); scale(0x86, 0.000305, "");
        scale(0x8A, 0.000122, "V"); scale(0x8B, 0.001, "V"); scale(0x8C, 0.01, "V");
        scale(0x8D, 0.00390625, "mA"); scale(0x8E, 0.001, "A"); scale(0x90, 1, "ms");
        scale(0x96, 0.1, "°C"); scale(0x9C, 0.01, "°"); scale(0x9D, 0.5, "°");
        scale(0xA8, 1, "g/s"); scale(0xA9, 0.25, "Pa/s"); scale(0xAF, 0.01, "%");
        scale(0xB0, 0.003052, "%"); scale(0xB1, 2, "mV/s");
        scale(0xFC, 0.01, "kPa"); scale(0xFD, 0.001, "kPa"); scale(0xFE, 0.25, "Pa");
    }

    /** Rohwert skaliert (ohne Einheit); null bei unbekannter UASID. */
    public static Double scaled(int raw, int uasid) {
        Scale s = SCALES.get(uasid);
        return s == null ? null : raw * s.factor + s.offset;
    }

    /** Rohwert → Anzeige mit Einheit; unbekannte UASID als Rohwert. */
    public static String format(int raw, int uasid) {
        Scale s = SCALES.get(uasid);
        if (s == null) return String.format(Locale.US, "%d (roh)", raw);
        double v = raw * s.factor + s.offset;
        int decimals = s.factor >= 1 ? 0 : Math.min(4, (int) Math.ceil(-Math.log10(s.factor)));
        String num = String.format(Locale.GERMANY, "%." + decimals + "f", v);
        return s.unit.isEmpty() ? num : num + " " + s.unit;
    }

    // ---------------------------------------------------------------- Bericht

    public static String report(List<Test> tests) {
        return report(tests, List.of());
    }

    public static String report(List<Test> tests, List<String> problems) {
        StringBuilder sb = new StringBuilder("Monitortests (Mode 06)\n");
        if (!problems.isEmpty()) {
            sb.append("⚠ Unvollständig – nicht gelesen: ").append(String.join(", ", problems))
                    .append(". Bitte erneut lesen.\n");
        }
        if (tests.isEmpty()) {
            sb.append(problems.isEmpty() ? "\nDas Motorsteuergerät liefert keine Mode-06-Testergebnisse."
                    : "\nKeine Testergebnisse gelesen.");
            return sb.toString();
        }
        int[] count = new int[Status.values().length];
        for (Test t : tests) count[t.status.ordinal()]++;
        sb.append(tests.size()).append(" Tests: ")
                .append(count[Status.FAIL.ordinal()]).append(" außerhalb · ")
                .append(count[Status.CLOSE.ordinal()]).append(" knapp · ")
                .append(count[Status.OK.ordinal()]).append(" in Ordnung · ")
                .append(count[Status.NOT_RUN.ordinal()]).append(" nicht gelaufen");
        if (count[Status.NO_LIMIT.ordinal()] > 0) {
            sb.append(" · ").append(count[Status.NO_LIMIT.ordinal()]).append(" ohne Grenzwert");
        }
        sb.append('\n');

        List<Test> notable = new ArrayList<>();
        for (Test t : tests) if (t.status == Status.FAIL || t.status == Status.CLOSE) notable.add(t);
        notable.sort(Comparator.comparingInt((Test t) -> t.status.ordinal())
                .thenComparingInt(t -> t.reservePercent == null ? 100 : t.reservePercent));
        if (!notable.isEmpty()) {
            sb.append("\nAuffällig:\n");
            for (Test t : notable) sb.append(line(t, true)).append('\n');
        }

        List<String> banks = bankComparison(tests);
        if (!banks.isEmpty()) {
            sb.append("\nBankvergleich (M272: Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6; Reserve zur Grenze):\n");
            for (String l : banks) sb.append(l).append('\n');
        }

        sb.append("\nAlle Tests:\n");
        int lastMid = -1;
        for (Test t : tests) {
            if (t.mid != lastMid) {
                sb.append(midName(t.mid)).append('\n');
                lastMid = t.mid;
            }
            sb.append(line(t, false)).append('\n');
        }

        List<String> hints = m272Hints(notable);
        if (!hints.isEmpty()) {
            sb.append("\nM272-Hinweise (typische Ursachen, keine Diagnose):\n");
            for (String h : hints) sb.append("• ").append(h).append('\n');
        }
        sb.append("\nGrenzwerte liefert das Steuergerät selbst. „Knapp“ = weniger als ")
                .append(CLOSE_PERCENT).append(" % Reserve zur Grenze. „Nicht gelaufen“ = Test im ")
                .append("aktuellen Fahrzyklus noch ohne Ergebnis. Tests ab 0x80 sind herstellerspezifisch; ")
                .append("benannt sind die des ME9.7 laut Mode-06-Tabelle des Werkstatthandbuchs (Ref. = Beispielbereich ")
                .append("dort), die übrigen sind unbekannt – die Bewertung gegen die Grenzwerte gilt trotzdem.");
        return sb.toString();
    }

    private static String line(Test t, boolean withMonitor) {
        String mark = switch (t.status) {
            case FAIL -> "✗";
            case CLOSE -> "⚠";
            case OK -> "✓";
            case NO_LIMIT -> "·";
            case NOT_RUN -> "–";
        };
        StringBuilder sb = new StringBuilder(withMonitor ? "" : "  ").append(mark).append(' ');
        if (withMonitor) sb.append(midName(t.mid)).append(" · ");
        sb.append(testName(t.mid, t.tid)).append(": ");
        if (t.status == Status.NOT_RUN) return sb.append("noch nicht gelaufen").toString();
        sb.append(format(t.value, t.uasid));
        if (t.hasMin && t.hasMax) {
            sb.append(" (").append(format(t.min, t.uasid)).append(" … ").append(format(t.max, t.uasid)).append(')');
        } else if (t.hasMax) {
            sb.append(" (≤ ").append(format(t.max, t.uasid)).append(')');
        } else if (t.hasMin) {
            sb.append(" (≥ ").append(format(t.min, t.uasid)).append(')');
        }
        if (t.reservePercent != null && t.status != Status.FAIL) {
            sb.append(" · Reserve ").append(t.reservePercent).append(" %");
        }
        return sb.toString();
    }

    /** Reserve-Unterschied (Prozentpunkte), ab dem eine Bank im Vergleich als deutlich schwächer gilt. */
    public static final int BANK_GAP_PERCENT = 25;

    /** Monitorpaare Bank 1 → Bank 2: Lambdasonden 1–4, Sondenheizungen 1–4, Katalysator. */
    private static final int[][] BANK_PAIRS = {
            {0x01, 0x05}, {0x02, 0x06}, {0x03, 0x07}, {0x04, 0x08},
            {0x41, 0x45}, {0x42, 0x46}, {0x43, 0x47}, {0x44, 0x48},
            {0x21, 0x22}};

    /**
     * Gleiche Tests beider Bänke nebeneinander (Anlass: Bank 1 am C350 lastunabhängig ~7 % fetter korrigiert,
     * 07./19.11.2026). Verglichen wird die Reserve zur Grenze, weil sie von der Skalierung unabhängig ist; ab
     * {@link #BANK_GAP_PERCENT} Punkten Unterschied wird die schwächere Bank markiert. Ohne Wertepaar: leere Liste.
     */
    public static List<String> bankComparison(List<Test> tests) {
        Map<Integer, Test> byKey = new HashMap<>();
        for (Test t : tests) byKey.put((t.mid << 8) | t.tid, t);
        List<String> lines = new ArrayList<>();
        int weaker1 = 0, weaker2 = 0, compared = 0, sensor1Weaker1 = 0;
        for (int[] pair : BANK_PAIRS) {
            for (Test a : tests) {
                if (a.mid != pair[0]) continue;
                Test b = byKey.get((pair[1] << 8) | a.tid);
                if (b == null || b.uasid != a.uasid || a.reservePercent == null || b.reservePercent == null) continue;
                // Schwellenprüfungen haben keine aussagekräftige Reserve (siehe evaluate)
                if (thresholdTest(a.mid, a.tid) || thresholdTest(b.mid, b.tid)) continue;
                compared++;
                int gap = a.reservePercent - b.reservePercent;
                String mark = Math.abs(gap) >= BANK_GAP_PERCENT ? "⚠" : "·";
                StringBuilder l = new StringBuilder("  ").append(mark).append(' ')
                        .append(midName(a.mid).replace(" Bank 1", "")).append(" · ").append(testName(a.mid, a.tid)).append(": B1 ")
                        .append(format(a.value, a.uasid)).append(" (").append(a.reservePercent).append(" %) · B2 ")
                        .append(format(b.value, b.uasid)).append(" (").append(b.reservePercent).append(" %)");
                if (gap <= -BANK_GAP_PERCENT) {
                    l.append(" → Bank 1 näher an der Grenze");
                    weaker1++;
                    if (a.mid == 0x01) sensor1Weaker1++;
                } else if (gap >= BANK_GAP_PERCENT) {
                    l.append(" → Bank 2 näher an der Grenze");
                    weaker2++;
                }
                lines.add(l.toString());
            }
        }
        if (compared == 0) return lines;
        String summary;
        if (weaker1 == 0 && weaker2 == 0) {
            summary = "  Bänke in allen " + compared + " vergleichbaren Tests ähnlich (Unterschied < " + BANK_GAP_PERCENT + " Punkte)";
        } else {
            summary = "  Deutlich schwächer: Bank 1 in " + weaker1 + ", Bank 2 in " + weaker2 + " von " + compared + " Tests";
        }
        lines.add(0, summary);
        if (sensor1Weaker1 > 0) {
            lines.add("  Hinweis: Die Vorkat-Sonde von Bank 1 liegt näher an ihren Grenzen als die von Bank 2. Das passt "
                    + "zu einer lastunabhängig höheren Korrektur von Bank 1 (Trim-Karte): Sonde B1S1, Abgasleck vor der "
                    + "Sonde (Krümmer/Flansch Zyl. 1–3) zuerst prüfen (Richtwert, keine Diagnose). Der Kraftstoffdruck "
                    + "ist für beide Bänke derselbe (rücklauffreie Anlage, 3,8 bar laut M272-Schulungsunterlage), "
                    + "scheidet als Ursache einer einseitigen Abweichung also weitgehend aus.");
        }
        return lines;
    }

    private static List<String> m272Hints(List<Test> notable) {
        Set<String> out = new LinkedHashSet<>();
        Set<Integer> mids = new TreeSet<>();
        for (Test t : notable) mids.add(t.mid);
        if (mids.contains(0x21) || mids.contains(0x22)) {
            out.add("Katalysator (Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6): vor einem Kat-Tausch Aussetzer "
                    + "und die hintere Lambdasonde der Bank ausschließen; ein knapper Wert kündigt "
                    + "P0420/P0430 oft an.");
        }
        for (int mid : mids) {
            if (mid >= 0x01 && mid <= 0x08) {
                out.add(midName(mid) + ": träge oder gealterte Sonde, Abgasleck vor der Sonde oder "
                        + "Falschluft der Bank; zusammen mit dem Fuel-Trim-Test bewerten.");
            } else if (mid >= 0x41 && mid <= 0x48) {
                out.add(midName(mid) + ": Heizwiderstand der Sonde, Stecker und Sicherung prüfen.");
            } else if (mid >= 0x39 && mid <= 0x3D) {
                out.add("Tankentlüftung: Tankdeckel/Dichtung, Regenerierventil, Schläuche zum Aktivkohlefilter.");
            } else if (mid >= 0x71 && mid <= 0x74) {
                out.add("Sekundärluft: Pumpe, Umschalt-/Rückschlagventil, Schläuche.");
            } else if (mid >= 0xA2 && mid <= 0xA7) {
                int cyl = mid - 0xA1;
                out.add("Aussetzer Zylinder " + cyl + " (Bank " + Mode06.m272Bank(cyl)
                        + "): Zündspule/Zündkerze zuerst, Tauschtest mit Nachbarzylinder.");
            }
        }
        return new ArrayList<>(out);
    }
}
