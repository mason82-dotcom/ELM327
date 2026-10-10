package de.roman.mercedesobdmonitor;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mode 09 (Fahrzeuginformationen, SAE J1979): FIN (09 02), Kalibrierungs-IDs (09 04),
 * Prüfsummen CVN (09 06) und In-Use-Performance-Zähler IUPR für Ottomotoren (09 08).
 * Android-unabhängig, unit-testbar.
 */
public final class VehicleInfo {
    /** Zähler-/Nennerpaar eines Monitors aus 09 08. */
    public static final class Iupr {
        public final String name;
        public final int completions;
        public final int conditions;

        Iupr(String name, int completions, int conditions) {
            this.name = name;
            this.completions = completions;
            this.conditions = conditions;
        }

        /** Durchläufe je passender Fahrt; null, wenn noch keine Fahrbedingung gezählt wurde. */
        public Double ratio() {
            return conditions == 0 ? null : completions / (double) conditions;
        }
    }

    /** 09 08 (Otto): allgemeiner Nenner, Zündzyklen und die Monitorpaare. */
    public static final class IuprData {
        public final int generalDenominator;
        public final int ignitionCycles;
        public final List<Iupr> monitors;

        IuprData(int generalDenominator, int ignitionCycles, List<Iupr> monitors) {
            this.generalDenominator = generalDenominator;
            this.ignitionCycles = ignitionCycles;
            this.monitors = monitors;
        }
    }

    /** Reihenfolge der Paare nach OBDCOND/IGNCNTR (16 oder 20 Werte). */
    private static final String[] IUPR_NAMES = {
            "Katalysator Bank 1", "Katalysator Bank 2",
            "Lambdasonde Bank 1", "Lambdasonde Bank 2",
            "AGR/VVT", "Sekundärluft", "Tankentlüftung (EVAP)",
            "Lambdasonde hinten Bank 1", "Lambdasonde hinten Bank 2"
    };

    /** Unter diesem Verhältnis läuft ein Monitor im Alltag auffällig selten (EU-Mindestwert 0,1). */
    public static final double RARE_RATIO = 0.1;

    private VehicleInfo() { }

    /** FIN aus 09 02; null, wenn keine Antwort. */
    public static String vin(String raw, Boolean isCan) {
        List<byte[]> payloads = payloads(raw, 0x02, isCan);
        if (payloads.isEmpty()) return null;
        String text = ascii(payloads.get(0));
        if (text.isEmpty()) return null;
        return text.length() > 17 ? text.substring(text.length() - 17) : text;
    }

    /** Kalibrierungs-IDs aus 09 04, 16 Zeichen je ID, alle Steuergeräte. */
    public static List<String> calibrationIds(String raw, Boolean isCan) {
        List<String> out = new ArrayList<>();
        for (byte[] p : payloads(raw, 0x04, isCan)) {
            for (int i = 0; i + 16 <= p.length; i += 16) {
                String id = ascii(Arrays.copyOfRange(p, i, i + 16));
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
    }

    /** CVN aus 09 06, 4 Byte je Prüfsumme, alle Steuergeräte. */
    public static List<String> cvns(String raw, Boolean isCan) {
        List<String> out = new ArrayList<>();
        for (byte[] p : payloads(raw, 0x06, isCan)) {
            for (int i = 0; i + 4 <= p.length; i += 4) {
                out.add(String.format(Locale.US, "%02X%02X%02X%02X",
                        p[i] & 0xFF, p[i + 1] & 0xFF, p[i + 2] & 0xFF, p[i + 3] & 0xFF));
            }
        }
        return out;
    }

    /** IUPR aus 09 08 (Otto); null, wenn keine Antwort. Nur das erste Steuergerät mit Daten. */
    public static IuprData iupr(String raw, Boolean isCan) {
        for (byte[] p : payloads(raw, 0x08, isCan)) {
            int words = p.length / 2;
            if (words < 2) continue;
            int[] w = new int[words];
            for (int i = 0; i < words; i++) w[i] = ((p[2 * i] & 0xFF) << 8) | (p[2 * i + 1] & 0xFF);
            List<Iupr> monitors = new ArrayList<>();
            for (int k = 0; k < IUPR_NAMES.length && 2 + 2 * k + 1 < words; k++) {
                monitors.add(new Iupr(IUPR_NAMES[k], w[2 + 2 * k], w[2 + 2 * k + 1]));
            }
            return new IuprData(w[0], w[1], monitors);
        }
        return null;
    }

    /**
     * Nutzdaten je Antwort ohne Kennung 49 xx. CAN: eine Nachricht je Steuergerät, das
     * Anzahl-Byte (NODI) wird entfernt. Legacy: Frames „49 xx NN d1–d4“ nach NN sortiert
     * zusammengesetzt (ein Steuergerät).
     */
    static List<byte[]> payloads(String raw, int infoType, Boolean isCan) {
        List<byte[]> out = new ArrayList<>();
        if (raw == null || ObdParser.isNoData(raw) || ObdParser.isLinkError(raw)) return out;
        String marker = String.format(Locale.US, "49%02X", infoType);
        if (!Boolean.FALSE.equals(isCan)) {
            List<String> msgs = new ArrayList<>();
            for (String msg : ObdParser.canMessages(raw)) {
                if (msg.startsWith(marker) && msg.length() >= marker.length() + 2) msgs.add(msg);
            }
            // Protokoll unbekannt: lauter 7-Byte-Zeilen „49 xx NN d1–d4“ sind Legacy-Frames.
            boolean legacyShape = !msgs.isEmpty();
            for (String m : msgs) legacyShape &= m.length() == marker.length() + 2 + 8;
            if (Boolean.TRUE.equals(isCan) || !legacyShape) {
                for (String msg : msgs) {
                    byte[] p = hex(msg.substring(marker.length() + 2));
                    if (p.length > 0) out.add(p);
                }
                return out;
            }
        }
        Map<Integer, byte[]> frames = new TreeMap<>();
        for (String line : ObdParser.normalizedLines(raw)) {
            if (!line.startsWith(marker) || line.length() < marker.length() + 2) continue;
            try {
                int seq = Integer.parseInt(line.substring(marker.length(), marker.length() + 2), 16);
                frames.put(seq, hex(line.substring(marker.length() + 2)));
            } catch (RuntimeException ignored) { }
        }
        if (!frames.isEmpty()) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] f : frames.values()) all.write(f, 0, f.length);
            out.add(all.toByteArray());
        }
        return out;
    }

    private static byte[] hex(String s) {
        String h = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** Druckbare ASCII-Zeichen; Füllbytes (0x00) und Steuerzeichen entfallen. */
    private static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xFF;
            if (c >= 0x20 && c <= 0x7E) sb.append((char) c);
        }
        return sb.toString().trim();
    }
}
