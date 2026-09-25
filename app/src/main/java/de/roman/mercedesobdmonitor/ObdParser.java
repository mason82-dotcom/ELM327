package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ObdParser {
    private ObdParser() { }

    public static byte[] mode01Data(String raw, int pid, int minBytes) {
        if (raw == null) return null;
        String marker = String.format(Locale.US, "41%02X", pid & 0xFF);
        for (String line : normalizedLines(raw)) {
            int pos = line.indexOf(marker);
            if (pos < 0) continue;
            String hex = line.substring(pos + marker.length()).replaceAll("[^0-9A-F]", "");
            if (hex.length() < minBytes * 2) continue;
            byte[] out = new byte[hex.length() / 2];
            try {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    public static Set<Integer> supportedPids(String raw, int basePid) {
        Set<Integer> result = new HashSet<>();
        if (raw == null) return result;

        // Mercedes kann auf funktionale OBD-Anfragen mit mehreren ECUs antworten.
        // Deshalb alle 41xx-Bitmaps logisch vereinigen statt nur die erste Zeile zu verwenden.
        String marker = String.format(Locale.US, "41%02X", basePid & 0xFF);
        long unionMask = 0L;

        for (String line : normalizedLines(raw)) {
            int pos = line.indexOf(marker);
            if (pos < 0) continue;
            String hex = line.substring(pos + marker.length()).replaceAll("[^0-9A-F]", "");
            if (hex.length() < 8) continue;
            try {
                long mask = Long.parseLong(hex.substring(0, 8), 16);
                unionMask |= mask;
            } catch (RuntimeException ignored) { }
        }

        for (int bit = 0; bit < 32; bit++) {
            if ((unionMask & (1L << (31 - bit))) != 0) result.add(basePid + bit + 1);
        }
        return result;
    }

    public static boolean isNoData(String raw) {
        if (raw == null) return true;
        String u = raw.toUpperCase(Locale.US);
        return u.contains("NO DATA") || u.contains("NODATA") || u.contains("UNABLE TO CONNECT")
                || u.contains("BUS ERROR") || u.contains("CAN ERROR");
    }

    public static List<String> dtcs(String raw, int responseMode) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String marker = String.format(Locale.US, "%02X", responseMode & 0xFF);

        for (String line : normalizedLines(raw)) {
            int searchFrom = 0;
            while (searchFrom < line.length()) {
                int pos = line.indexOf(marker, searchFrom);
                if (pos < 0) break;

                int next = line.indexOf(marker, pos + marker.length());
                String hex = line.substring(
                        pos + marker.length(),
                        next >= 0 ? next : line.length()
                ).replaceAll("[^0-9A-F]", "");

                for (int i = 0; i + 3 < hex.length(); i += 4) {
                    try {
                        int a = Integer.parseInt(hex.substring(i, i + 2), 16);
                        int bb = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
                        if (a == 0 && bb == 0) continue;
                        String code = decodeDtc(a, bb);
                        if (!out.contains(code)) out.add(code);
                    } catch (RuntimeException ignored) { }
                }

                if (next < 0) break;
                searchFrom = next;
            }
        }
        return out;
    }

    private static String decodeDtc(int a, int b) {
        char[] family = {'P', 'C', 'B', 'U'};
        char f = family[(a >> 6) & 0x03];
        int d1 = (a >> 4) & 0x03;
        int d2 = a & 0x0F;
        int d3 = (b >> 4) & 0x0F;
        int d4 = b & 0x0F;
        return String.format(Locale.US, "%c%d%X%X%X", f, d1, d2, d3, d4);
    }

    private static List<String> normalizedLines(String raw) {
        String u = raw.toUpperCase(Locale.US).replace('\r', '\n');
        String[] pieces = u.split("\\n+");
        List<String> lines = new ArrayList<>();
        for (String p : pieces) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("SEARCHING")) {
                int dots = s.lastIndexOf("...");
                if (dots >= 0 && dots + 3 < s.length()) s = s.substring(dots + 3).trim();
                else continue;
            }
            lines.add(s.replace(" ", "").replace("\t", ""));
        }
        return lines;
    }
}
