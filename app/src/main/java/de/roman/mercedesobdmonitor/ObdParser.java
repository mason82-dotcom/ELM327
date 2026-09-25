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
        byte[] d = mode01Data(raw, basePid, 4);
        if (d == null || d.length < 4) return result;
        long mask = ((long) (d[0] & 0xFF) << 24)
                | ((long) (d[1] & 0xFF) << 16)
                | ((long) (d[2] & 0xFF) << 8)
                | (long) (d[3] & 0xFF);
        for (int bit = 0; bit < 32; bit++) {
            if ((mask & (1L << (31 - bit))) != 0) result.add(basePid + bit + 1);
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
            int pos = line.indexOf(marker);
            if (pos < 0) continue;
            String hex = line.substring(pos + marker.length()).replaceAll("[^0-9A-F]", "");
            for (int i = 0; i + 3 < hex.length(); i += 4) {
                try {
                    int a = Integer.parseInt(hex.substring(i, i + 2), 16);
                    int bb = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
                    if (a == 0 && bb == 0) continue;
                    String code = decodeDtc(a, bb);
                    if (!out.contains(code)) out.add(code);
                } catch (RuntimeException ignored) { }
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
