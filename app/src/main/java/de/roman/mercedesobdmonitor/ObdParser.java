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

    /**
     * Wertet die ATDPN-Antwort aus ("6", "A6" bei Auto-Erkennung, …).
     * ELM 6–9 = ISO 15765-4 CAN, A–C = benutzerdefiniertes CAN, 1–5 = Legacy.
     * @return null, wenn unbekannt
     */
    public static Boolean isCanProtocol(String dpn) {
        String code = dpn == null ? "" : dpn.trim().toUpperCase(Locale.US);
        if (code.startsWith("A") && code.length() > 1) code = code.substring(1);
        if (code.length() != 1) return null;
        char ch = code.charAt(0);
        if (ch >= '6' && ch <= '9') return Boolean.TRUE;
        if (ch >= 'A' && ch <= 'C') return Boolean.TRUE;
        if (ch >= '1' && ch <= '5') return Boolean.FALSE;
        return null;
    }

    /** Protokoll unbekannt: CAN vs. Legacy wird aus dem Antwortformat abgeleitet. */
    public static List<String> dtcs(String raw, int responseMode) {
        return dtcs(raw, responseMode, null);
    }

    /**
     * Dekodiert Mode 03/07/0A-Antworten (Header aus, ATH0).
     *
     * CAN (ISO 15765-4): [4x][Anzahl][DTC-Paare…], ggf. als ISO-TP-Multiframe
     * ("00A" / "0:…" / "1:…"). Legacy (J1850/ISO 9141/KWP): pro Frame
     * [4x] + genau 3 DTC-Paare, 0000 = Füllwert.
     *
     * @param can true = CAN, false = Legacy, null = automatisch erkennen
     */
    public static List<String> dtcs(String raw, int responseMode, Boolean can) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String marker = String.format(Locale.US, "%02X", responseMode & 0xFF);
        List<String> lines = normalizedLines(raw);
        boolean isCan = can != null ? can : looksLikeCan(lines, marker);

        for (String msg : isCan ? assembleCanMessages(lines) : legacyFrames(lines, marker)) {
            if (!msg.startsWith(marker)) continue;
            String body = msg.substring(marker.length());
            if (isCan) {
                if (body.length() < 2) continue;
                int count;
                try {
                    count = Integer.parseInt(body.substring(0, 2), 16);
                } catch (RuntimeException e) {
                    continue;
                }
                body = body.substring(2);
                // Anzahl-Byte begrenzt die Nutzdaten; Padding dahinter ignorieren.
                body = body.substring(0, Math.min(body.length(), count * 4));
            }
            addDtcPairs(body, out);
        }
        return out;
    }

    private static void addDtcPairs(String hex, List<String> out) {
        for (int i = 0; i + 3 < hex.length(); i += 4) {
            try {
                int a = Integer.parseInt(hex.substring(i, i + 2), 16);
                int b = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
                if (a == 0 && b == 0) continue;
                String code = decodeDtc(a, b);
                if (!out.contains(code)) out.add(code);
            } catch (RuntimeException ignored) { }
        }
    }

    private static boolean isIsoTpLength(String line) {
        return line.matches("[0-9A-F]{3}");
    }

    private static boolean isIsoTpSegment(String line) {
        return line.matches("[0-9A-F]:[0-9A-F]*");
    }

    private static boolean looksLikeCan(List<String> lines, String marker) {
        for (String line : lines) {
            if (isIsoTpLength(line) || isIsoTpSegment(line)) return true;
        }
        for (String line : lines) {
            if (!line.startsWith(marker)) continue;
            String hex = line.replaceAll("[^0-9A-F]", "");
            // Legacy-Frames sind immer 7 Byte (Kennung + 3 DTC-Paare).
            if (hex.length() == 14) return false;
            // CAN-Single-Frame: Kennung + Anzahl + Anzahl*2 Byte (max. 7 Byte).
            if (hex.length() >= 4) {
                try {
                    int count = Integer.parseInt(hex.substring(2, 4), 16);
                    if (hex.length() == 4 + count * 4) return true;
                } catch (RuntimeException ignored) { }
            }
        }
        return false;
    }

    /** Fügt ISO-TP-Segmente zusammen; Single-Frame-Zeilen bleiben einzeln. */
    private static List<String> assembleCanMessages(List<String> lines) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = null;
        int expectedBytes = -1;

        for (String line : lines) {
            if (isIsoTpLength(line)) {
                flush(messages, current, expectedBytes);
                current = new StringBuilder();
                expectedBytes = Integer.parseInt(line, 16);
            } else if (isIsoTpSegment(line)) {
                if (current == null) {
                    current = new StringBuilder();
                    expectedBytes = -1;
                }
                current.append(line.substring(2));
            } else {
                flush(messages, current, expectedBytes);
                current = null;
                expectedBytes = -1;
                messages.add(line.replaceAll("[^0-9A-F]", ""));
            }
        }
        flush(messages, current, expectedBytes);
        return messages;
    }

    private static void flush(List<String> messages, StringBuilder current, int expectedBytes) {
        if (current == null || current.length() == 0) return;
        String hex = current.toString();
        if (expectedBytes > 0 && hex.length() > expectedBytes * 2) {
            hex = hex.substring(0, expectedBytes * 2);
        }
        messages.add(hex);
    }

    /** Legacy: jeder Frame beginnt mit der Kennung und ist 7 Byte lang. */
    private static List<String> legacyFrames(List<String> lines, String marker) {
        List<String> frames = new ArrayList<>();
        for (String line : lines) {
            String hex = line.replaceAll("[^0-9A-F]", "");
            if (!hex.startsWith(marker)) continue;
            // Falls mehrere Frames ohne Zeilenumbruch ankommen, in 7-Byte-Blöcke teilen.
            if (hex.length() > 14 && hex.length() % 14 == 0) {
                for (int i = 0; i < hex.length(); i += 14) frames.add(hex.substring(i, i + 14));
            } else {
                frames.add(hex);
            }
        }
        return frames;
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
