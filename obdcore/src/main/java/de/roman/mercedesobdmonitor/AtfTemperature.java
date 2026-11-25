package de.roman.mercedesobdmonitor;

import java.util.Locale;

/**
 * ATF-Temperatur der 7G-Tronic 722.9 (ADR-001/ADR-003, Android-frei, testbar).
 * Anfrage an das Getriebesteuergerät: Header 7E1, KWP2000 ReadDataByLocalIdentifier {@code 21 30};
 * Antwort {@code 61 30 …} von 7E9, °C = Datenbyte 11 nach {@code 61 30} − 50, also Index 13 ab 0x61 (Torque „L-50“,
 * OBDScanner bix 88, EGS52-Layout Bit 104). Für den C350 MJ 2008 nicht bestätigt – deshalb wird die Rohantwort
 * immer mit angezeigt, dazu die übrigen Felder nach EGS52-Layout als Rohwerte ({@link #layoutFields}).
 */
public final class AtfTemperature {
    private AtfTemperature() { }

    /**
     * Index des Temperaturbytes in der Antwort, gezählt ab dem Antwortservice 0x61 (= Datenbyte 11 nach {@code 61 30}).
     * Bis ADR-003 stand hier 11 – das ist im EGS52-Layout der erkannte Gang.
     */
    public static final int BYTE_INDEX = 13;
    public static final int OFFSET = 50;

    /** Ergebnis: Temperatur oder null samt Grund, immer mit Rohdaten. */
    public static final class Reading {
        public final Double celsius;
        public final String problem;
        public final String rawHex;
        /** Bytes der Antwort ab 0x61, leer ohne {@code 61 30}. */
        final byte[] response;

        Reading(Double celsius, String problem, String rawHex) {
            this(celsius, problem, rawHex, new byte[0]);
        }

        Reading(Double celsius, String problem, String rawHex, byte[] response) {
            this.celsius = celsius;
            this.problem = problem;
            this.rawHex = rawHex;
            this.response = response;
        }

        public String text() {
            String v = celsius != null ? String.format(Locale.GERMANY, "%.0f °C", celsius) : "kein gültiger Wert (" + problem + ")";
            StringBuilder sb = new StringBuilder("ATF-Temperatur: ").append(v);
            String shift = celsius == null ? null : shiftSummary(response);   // nur zu einer gültigen Antwort
            if (shift != null) sb.append("\n").append(shift);
            sb.append("\nRohantwort: ").append(rawHex.isEmpty() ? "–" : rawHex)
                    .append("\nFormel: Datenbyte 11 nach 61 30 − 50 – am C350 bestätigt (ADR-003, 21.11.2026: 8 °C kalt ")
                    .append("bei 5 °C außen, 62 °C warm).");
            String fields = layoutFields(response);
            if (!fields.isEmpty()) sb.append("\n\n").append(fields);
            return sb.toString();
        }
    }

    /** Wertet die Antwort aus (Leerzeichen, Zeilenumbrüche, ISO-TP-Zähler „0:“/„1:“ und Längenzeile werden toleriert). */
    public static Reading parse(String raw) {
        if (raw == null || ObdParser.isNoData(raw)) return new Reading(null, "keine Antwort", "");
        if (ObdParser.isLinkError(raw)) return new Reading(null, "Busfehler", raw.trim());
        // Same ISO-TP reassembly as every other answer (declared length, one message per responder).
        String data = null, rejected = null, other = "";
        for (String msg : ObdParser.canMessages(raw)) {
            if (msg.startsWith("6130")) { data = msg; break; }
            if (msg.startsWith("7F21") && rejected == null) rejected = msg;
            else if (other.isEmpty()) other = msg;
        }
        if (data == null && rejected != null) {
            int code = rejected.length() >= 6 ? Integer.parseInt(rejected.substring(4, 6), 16) : -1;
            return new Reading(null, "Steuergerät lehnt die Anfrage ab (7F"
                    + (code >= 0 ? String.format(Locale.GERMANY, ", Code %02X: %s", code, KwpReadout.nrc(code)) : "") + ")",
                    spaced(rejected));
        }
        if (data == null && ObdParser.hasIncompleteMessage(raw)) return new Reading(null, "Antwort unvollständig", raw.trim());
        if (data == null) return new Reading(null, "unerwartete Antwort", spaced(other));
        byte[] bytes = bytes(data);
        if (bytes.length < BYTE_INDEX + 1) return new Reading(null, "Antwort zu kurz", spaced(data), bytes);
        int b = bytes[BYTE_INDEX] & 0xFF;
        if (b <= 1) return new Reading(null, "Rohwert " + b + " = Sensor/Steuergerät ohne Wert", spaced(data), bytes);
        double c = b - OFFSET;
        if (c < -40 || c > 150) {
            return new Reading(null, String.format(Locale.GERMANY, "unplausibel (%.0f °C)", c), spaced(data), bytes);
        }
        return new Reading(c, null, spaced(data), bytes);
    }

    /**
     * Felder der Antwort nach dem EGS52-Layout von RLI 0x30 (ultimate-nag52-fw, egs_emulation.h), Index ab 0x61,
     * 16-Bit-Werte big-endian. ADR-003: Wählhebel [9], Gang [11]/[12] und ATF [13] sind am C350 bestätigt (21.11.2026);
     * die übrigen bleiben Rohwerte ohne Skalierung. Leer, wenn die Antwort keine Felder trägt.
     */
    static String layoutFields(byte[] r) {
        if (r == null || r.length < 3) return "";
        StringBuilder sb = new StringBuilder();
        word(sb, r, 2, "Wandler-Schlupfdrehzahl");
        word(sb, r, 4, "Wandler-Drehzahl");
        word(sb, r, 6, "Wandlerdruck");
        if (r.length > 8) sb.append(field(8, "Wandlerzustand", r[8] & 0xFF, egs52(tccState(r[8] & 0xFF))));
        if (r.length > 9) sb.append(field(9, "Wählhebelstellung", r[9] & 0xFF, selector(r[9] & 0xFF)));
        if (r.length > 10) sb.append(field(10, "Fahrprogramm", r[10] & 0xFF, null));
        if (r.length > 11) sb.append(field(11, "erkannter Gang", r[11] & 0xFF, gear(r[11] & 0xFF)));
        if (r.length > 12) {
            int v = r[12] & 0xFF;
            sb.append(String.format(Locale.GERMANY, "• [12] Ziel-/Istgang: %02X → Ist %s, Ziel %s%n",
                    v, gearNibble(v & 0x0F), gearNibble(v >> 4)));
        }
        signedWord(sb, r, 14, "Motormoment");
        signedWord(sb, r, 16, "Wandlermoment");
        word(sb, r, 18, "Abtriebsdrehzahl");
        if (r.length > 20) {
            StringBuilder hex = new StringBuilder();
            for (int i = 20; i < Math.min(r.length, 26); i++) hex.append(hex.length() > 0 ? " " : "").append(String.format("%02X", r[i] & 0xFF));
            sb.append("• [20…] Schaltbits (Kickdown, Wandlerkupplung, Notlauf …): ").append(hex).append('\n');
        }
        if (sb.length() == 0) return "";
        return ("Felder der Antwort (Index ab 0x61): Wählhebel [9], Gang [11]/[12] und ATF [13] am C350 bestätigt "
                + "(ADR-003); die übrigen nach EGS52-Layout (722.6), Rohwerte ohne Einheit:\n" + sb).trim();
    }

    /** Klartextzeile „Wählhebel D · Gang 2 → 3“; null ohne die Felder. */
    static String shiftSummary(byte[] r) {
        if (r == null || r.length <= 12) return null;
        String sel = selector(r[9] & 0xFF);
        int g = r[12] & 0xFF;
        String ist = gearNibble(g & 0x0F), ziel = gearNibble(g >> 4);
        return "Wählhebel " + (sel == null ? "? (" + (r[9] & 0xFF) + ")" : sel)
                + " · Gang " + (ist.equals(ziel) ? ist : ist + " → " + ziel);
    }

    /** Wählhebelstellung [9] des 722.9 (Fahrzeugtest 21.11.2026): P = 10, R = 9, N = 8, D = 7; sonst null. */
    static String selector(int v) {
        return switch (v) {
            case 10 -> "P";
            case 9 -> "R";
            case 8 -> "N";
            case 7 -> "D";
            default -> null;
        };
    }

    /** Halbbyte von [12]: 0 = N, 1–7 = Gang, 11 = R, 13 = P (Fahrzeugtest 21.11.2026); sonst Rohwert. */
    static String gearNibble(int v) {
        if (v >= 1 && v <= 7) return String.valueOf(v);
        return switch (v) {
            case 0 -> "N";
            case 11 -> "R";
            case 13 -> "P";
            default -> "? (" + v + ")";
        };
    }


    private static void signedWord(StringBuilder sb, byte[] r, int i, String name) {
        if (r.length > i + 1) sb.append(field(i, name, (short) (((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF)), null));
    }

    private static void word(StringBuilder sb, byte[] r, int i, String name) {
        if (r.length > i + 1) sb.append(field(i, name, ((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF), null));
    }

    /** Feldzeile „• [i] Name: Rohwert (Zusatz)“; Zusatz null = ohne Klammer. */
    private static String field(int i, String name, int raw, String extra) {
        return String.format(Locale.GERMANY, "• [%d] %s: %d%s%n", i, name, raw, extra == null ? "" : " (" + extra + ")");
    }

    /** Bezeichnung nach EGS52-Layout (am C350 unbestätigt) kennzeichnen. */
    private static String egs52(String text) {
        return text == null ? null : "EGS52: " + text;
    }

    /** Erkannter Gang [11] des 722.9: im Stand 0, in der Fahrt der eingelegte Gang (21.11.2026: 2 bei Ziel 2). */
    static String gear(int v) {
        if (v >= 1 && v <= 7) return "Gang " + v;
        return switch (v) {
            case 0 -> "kein Gang (Stand)";
            default -> null;
        };
    }

    /** Aufzählung TccState des EGS52 (Wandlerüberbrückungskupplung). */
    static String tccState(int v) {
        return switch (v) {
            case 0 -> "offen";
            case 1 -> "offen → schlupfend";
            case 2 -> "schlupfend → offen";
            case 3 -> "schlupfend";
            case 4 -> "schlupfend → geschlossen";
            case 5 -> "geschlossen → schlupfend";
            case 6 -> "geschlossen";
            default -> null;
        };
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static String spaced(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }
}
