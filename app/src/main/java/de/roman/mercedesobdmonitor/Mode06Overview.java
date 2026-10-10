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
        TolerantQuery q = new TolerantQuery(transport);
        Set<Integer> mids = new TreeSet<>();
        for (int base = 0x00; base <= 0xE0; base += 0x20) {
            String raw = q.query(Mode06.command(base), 3000);
            if (raw == null || ObdParser.isNoData(raw) || ObdParser.isLinkError(raw)) break;
            Set<Integer> supported = Mode06.supportedMids(raw, base);
            if (supported.isEmpty()) break;
            mids.addAll(supported);
            if (!supported.contains(base + 0x20)) break;
        }
        List<Test> tests = new ArrayList<>();
        for (int mid : mids) {
            if (mid % 0x20 == 0) continue; // Bitmap-MID, kein Test
            String raw = q.query(Mode06.command(mid), 3000);
            if (raw == null || ObdParser.isNoData(raw) || ObdParser.isLinkError(raw)) continue;
            for (Mode06.TestResult r : Mode06.results(raw)) {
                if (r.mid == mid) tests.add(evaluate(r));
            }
        }
        return tests;
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
        if (r.min == 0 && r.max == 0) hasMax = false;

        if (!hasMin && !hasMax) {
            return new Test(r.mid, r.tid, r.uasid, v, min, max, false, false, Status.NO_LIMIT, null);
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
        Status status = fail ? Status.FAIL
                : reserve != null && reserve < CLOSE_PERCENT ? Status.CLOSE : Status.OK;
        return new Test(r.mid, r.tid, r.uasid, v, min, max, hasMin, hasMax, status, reserve);
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
        StringBuilder sb = new StringBuilder("Monitortests (Mode 06)\n");
        if (tests.isEmpty()) {
            sb.append("\nDas Motorsteuergerät liefert keine Mode-06-Testergebnisse.");
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
                .append("ihre Bedeutung ist ohne Mercedes-Unterlagen unbekannt, die Bewertung gegen die ")
                .append("Grenzwerte gilt trotzdem.");
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
        sb.append(tidName(t.tid)).append(": ");
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
