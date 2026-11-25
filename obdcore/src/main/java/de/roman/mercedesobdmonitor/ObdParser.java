package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ObdParser {
    // Vorkompiliert: der Parser läuft im Polling-Takt für jede Antwort.
    private static final Pattern NON_HEX = Pattern.compile("[^0-9A-F]");
    /** Interner Adapterfehler „ERR94“ u. a. */
    private static final Pattern ELM_ERR = Pattern.compile("\\bERR[0-9]{2}");
    private static final Pattern ISO_TP_LENGTH = Pattern.compile("[0-9A-F]{3}");
    private static final Pattern ISO_TP_SEGMENT = Pattern.compile("[0-9A-F]:[0-9A-F]*");
    /** 11-bit-Antwortkennung 7E8–7EF + Single-Frame-PCI 01–07 (ATH1, ATS0), z. B. „7E803410C0EE7“. */
    private static final Pattern CAN_SINGLE_FRAME = Pattern.compile("^(7E[89A-F])0([1-7])([0-9A-F]*)$");
    /** Mit Header (ATH1): ISO-TP-Erstrahmen „7E9 10 18 …“ (Länge 12 bit) und Folgerahmen „7E9 21 …“. */
    private static final Pattern CAN_FIRST_FRAME = Pattern.compile("^(7E[89A-F])1([0-9A-F]{3})([0-9A-F]*)$");
    private static final Pattern CAN_CONSECUTIVE_FRAME = Pattern.compile("^(7E[89A-F])2([0-9A-F])([0-9A-F]*)$");

    /** Antwortkennung des Motorsteuergeräts (ME9.7 beim M272). */
    public static final String ENGINE_ECU = "7E8";

    private ObdParser() { }

    private static String hexOnly(String s) {
        return NON_HEX.matcher(s).replaceAll("");
    }

    public static byte[] mode01Data(String raw, int pid, int minBytes) {
        if (raw == null) return null;
        String marker = String.format(Locale.US, "41%02X", pid & 0xFF);
        for (String line : payloadLines(raw)) {
            // Antwort beginnt mit der Kennung; gestörte Zeilen (Fremdzeichen, halbes Byte) zählen nicht
            if (!line.startsWith(marker) || NON_HEX.matcher(line).find()) continue;
            String hex = line.substring(marker.length());
            if (hex.length() < minBytes * 2 || hex.length() % 2 != 0) continue;
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

    /**
     * Mode 02 (Freeze Frame): "42 PP FF data…" – PP = PID, FF = Frame-Nummer.
     * Liefert die Nutzdaten ab dem ersten Datenbyte nach der Frame-Nummer.
     */
    public static byte[] mode02Data(String raw, int pid, int minBytes) {
        if (raw == null) return null;
        String marker = String.format(Locale.US, "42%02X", pid & 0xFF);
        for (String line : payloadLines(raw)) {
            String hex = hexOnly(line);
            if (!hex.startsWith(marker)) continue;
            String body = hex.substring(marker.length());
            if (body.length() < 2 + minBytes * 2) continue;
            body = body.substring(2); // Frame-Nummer
            byte[] out = new byte[body.length() / 2];
            try {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(body.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    /** Rohantwort → zusammengesetzte CAN-Nachrichten (ISO-TP-Segmente verbunden, nur Hex). */
    public static List<String> canMessages(String raw) {
        if (raw == null) return new ArrayList<>();
        return assembleCanMessages(payloadLines(raw));
    }

    public static Set<Integer> supportedPids(String raw, int basePid) {
        Set<Integer> result = new HashSet<>();
        if (raw == null) return result;

        // Mercedes kann auf funktionale OBD-Anfragen mit mehreren ECUs antworten.
        // Deshalb alle 41xx-Bitmaps logisch vereinigen statt nur die erste Zeile zu verwenden.
        String marker = String.format(Locale.US, "41%02X", basePid & 0xFF);
        long unionMask = 0L;

        for (String line : payloadLines(raw)) {
            if (!line.startsWith(marker)) continue;
            String hex = hexOnly(line.substring(marker.length()));
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

    /** „NO DATA“: Bus erreichbar, aber kein Steuergerät hat Daten zu dieser Anfrage. */
    public static boolean isNoData(String raw) {
        if (raw == null) return true;
        String u = raw.toUpperCase(Locale.US);
        return u.contains("NO DATA") || u.contains("NODATA");
    }

    /**
     * ELM327-Meldung, dass die Anfrage das Fahrzeug nicht erreicht hat bzw. die Antwort
     * gestört ist. Anders als „NO DATA“ sagt das nichts über den Fahrzeugzustand aus.
     */
    public static boolean isLinkError(String raw) {
        if (raw == null) return false;
        String u = raw.toUpperCase(Locale.US);
        return u.contains("UNABLE TO CONNECT") || u.contains("BUS ERROR") || u.contains("BUS INIT")
                || u.contains("CAN ERROR") || u.contains("FB ERROR") || u.contains("DATA ERROR")
                || u.contains("BUFFER FULL") || u.contains("STOPPED") || u.contains("RX ERROR")
                || u.contains("LV RESET") || u.contains("ACT ALERT") || ELM_ERR.matcher(u).find();
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

    /**
     * true, wenn mindestens ein Steuergerät eine gültige Antwort des Modus geliefert hat
     * (z. B. "43 00", "4A 00", Legacy "4A 00 00 00 00 00 00") – unabhängig davon, ob DTCs enthalten sind.
     */
    public static boolean hasDtcResponse(String raw, int responseMode, Boolean can) {
        if (raw == null) return false;
        String marker = String.format(Locale.US, "%02X", responseMode & 0xFF);
        List<String> lines = normalizedLines(raw);
        boolean isCan = can != null ? can : looksLikeCan(lines, marker);
        for (String msg : isCan ? assembleCanMessages(lines) : legacyFrames(lines, marker)) {
            if (msg.startsWith(marker)) return true;
        }
        return false;
    }

    /** Gültige Antwort ohne DTCs („43 00“, „47 00“, „4A 00“, auch mehrere ECUs). */
    public static boolean isEmptyDtcResponse(String raw, int responseMode, Boolean can) {
        return hasDtcResponse(raw, responseMode, can) && dtcs(raw, responseMode, can).isEmpty();
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
        return ISO_TP_LENGTH.matcher(line).matches();
    }

    private static boolean isIsoTpSegment(String line) {
        return ISO_TP_SEGMENT.matcher(line).matches();
    }

    private static boolean looksLikeCan(List<String> lines, String marker) {
        for (String line : lines) {
            if (isIsoTpLength(line) || isIsoTpSegment(line)) return true;
        }
        for (String line : lines) {
            if (!line.startsWith(marker)) continue;
            String hex = hexOnly(line);
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

    /** true, wenn eine mehrteilige Antwort verworfen wurde (Segment fehlt, falsche Reihenfolge, zu kurz). */
    public static boolean hasIncompleteMessage(String raw) {
        return raw != null && new IsoTp(payloadLines(raw)).incomplete;
    }

    /**
     * Fügt ISO-TP-Segmente zusammen; Single-Frame-Zeilen bleiben einzeln. Mehrteilige Antworten mit fehlendem
     * oder vertauschtem Segment oder weniger Bytes als angekündigt fallen weg.
     */
    static List<String> assembleCanMessages(List<String> lines) {
        return new IsoTp(lines).messages;
    }

    private static final class IsoTp {
        final List<String> messages = new ArrayList<>();
        boolean incomplete;
        private StringBuilder current;
        private int expectedBytes = -1, nextSegment;

        IsoTp(List<String> lines) {
            for (String line : lines) {
                if (isIsoTpLength(line)) {
                    flush();
                    current = new StringBuilder();
                    expectedBytes = Integer.parseInt(line, 16);
                } else if (isIsoTpSegment(line)) {
                    segment(Character.digit(line.charAt(0), 16), line.substring(2));
                } else {
                    // Einzelantwort eines zweiten Steuergeräts zwischen zwei Segmenten beendet die Nachricht nicht
                    if (current == null || expectedBytes < 0 || complete()) flush();
                    messages.add(hexOnly(line));
                }
            }
            flush();
        }

        private void segment(int number, String data) {
            if (current == null) {
                if (number != 0) {     // Rest einer Nachricht, deren Anfang fehlt
                    incomplete = true;
                    return;
                }
                current = new StringBuilder();
            }
            if (number != nextSegment) {
                incomplete = true;
                reset();
                return;
            }
            current.append(data);
            nextSegment = (nextSegment + 1) & 0x0F;
        }

        private boolean complete() {
            return expectedBytes < 0 || current.length() >= expectedBytes * 2;
        }

        private void flush() {
            if (current != null) {
                if (current.length() == 0 || !complete()) incomplete = true;
                else messages.add(expectedBytes > 0 && current.length() > expectedBytes * 2
                        ? current.substring(0, expectedBytes * 2) : current.toString());
            }
            reset();
        }

        private void reset() {
            current = null;
            expectedBytes = -1;
            nextSegment = 0;
        }
    }

    /** Legacy: jeder Frame beginnt mit der Kennung und ist 7 Byte lang. */
    private static List<String> legacyFrames(List<String> lines, String marker) {
        List<String> frames = new ArrayList<>();
        for (String line : lines) {
            String hex = hexOnly(line);
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

    /** Zwei Rohbytes → DTC-Text (z. B. 01 71 → P0171). */
    public static String decodeDtcCode(int a, int b) {
        return decodeDtc(a & 0xFF, b & 0xFF);
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

    /**
     * Absender und Nutzdaten einer Single-Frame-Zeile mit 11-bit-Header (ATH1), sonst null. Ohne Header
     * (ATH0) beginnt eine Antwort mit dem Service-Byte (4x/7F) oder einer ISO-TP-Kennung, nie mit „7E“.
     * Nutzdaten werden auf die PCI-Länge gekürzt (Padding fällt weg).
     */
    static String[] splitCanHeader(String line) {
        Matcher m = CAN_SINGLE_FRAME.matcher(line);
        if (!m.matches()) return null;
        int len = Integer.parseInt(m.group(2));
        String data = m.group(3);
        if (data.length() < len * 2) return null;
        return new String[] {m.group(1), data.substring(0, len * 2)};
    }

    /**
     * Antwortzeilen ohne CAN-Header; mit Header (Live-Polling auf 11-bit-CAN) stehen die Antworten des
     * Motorsteuergeräts (7E8) vorn, danach die übrigen in Empfangsreihenfolge. Am C350 beantworten Motor
     * und Getriebe u. a. 01 0C/0D/04/05/42/49 (Log 07.11.2026, bis 1,1 V Unterschied bei 01 42); ohne Header
     * hätte die zuerst eintreffende Antwort gegolten.
     */
    static List<String> payloadLines(String raw) {
        // Je Steuergerät gesammelt (Motor 7E8 zuerst), damit mehrteilige Antworten zweier Steuergeräte nicht
        // ineinander geraten; Zeilen ohne Header bleiben in ihrer Reihenfolge am Ende.
        Map<String, List<String>> byEcu = new LinkedHashMap<>();
        byEcu.put(ENGINE_ECU, new ArrayList<>());
        List<String> plain = new ArrayList<>();
        for (String line : normalizedLines(raw)) {
            String[] split = splitCanHeader(line);
            if (split != null) {
                byEcu.computeIfAbsent(split[0], k -> new ArrayList<>()).add(split[1]);
                continue;
            }
            // Mehrteilige Antwort mit Header (Getriebe live, ADR-008): in die Form ohne Header übersetzen
            // (Längenzeile, „N:“-Segmente), die assembleCanMessages zusammensetzt.
            Matcher first = CAN_FIRST_FRAME.matcher(line);
            Matcher next = CAN_CONSECUTIVE_FRAME.matcher(line);
            if (first.matches()) {
                List<String> target = byEcu.computeIfAbsent(first.group(1), k -> new ArrayList<>());
                target.add(first.group(2));
                target.add("0:" + first.group(3));
            } else if (next.matches()) {
                byEcu.computeIfAbsent(next.group(1), k -> new ArrayList<>()).add(next.group(2) + ":" + next.group(3));
            } else {
                plain.add(line);
            }
        }
        List<String> lines = new ArrayList<>();
        for (List<String> ecu : byEcu.values()) lines.addAll(ecu);
        lines.addAll(plain);
        return lines;
    }

    static List<String> normalizedLines(String raw) {
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
            // „… <RX ERROR“ / „… <DATA ERROR“: diese Zeile ist gestört, ihre Bytes sind nicht verlässlich
            if (s.indexOf('<') >= 0) continue;
            List<String> frames = flattenedFrames(s);
            if (frames != null) lines.addAll(frames);
            else lines.add(s.replace(" ", "").replace("\t", ""));
        }
        return lines;
    }

    /**
     * Mehrere Header-Antworten in einer Zeile, getrennt durch Leerzeichen: so steht eine Rohantwort im CSV-Log
     * ({@link CsvLogWriter} ersetzt CR/LF durch Leerzeichen), z. B. „7E90341055B 7E80341055C“. Nur wenn jedes
     * Wort für sich ein vollständiger Single-Frame mit Header ist, wird getrennt; ATS1-Antworten („7E8 03 41 …“)
     * und Zeilen ohne Header bleiben eine Zeile. Sonst würde beim Wiedereinlesen eines Logs die zuerst
     * empfangene Antwort gelten (Log 19.11.2026: 763 von 1 899 Doppelantworten kamen zuerst vom Getriebe).
     */
    private static List<String> flattenedFrames(String line) {
        String[] words = line.trim().split("\\s+");
        if (words.length < 2) return null;
        for (String w : words) if (splitCanHeader(w) == null) return null;
        return java.util.Arrays.asList(words);
    }
}
