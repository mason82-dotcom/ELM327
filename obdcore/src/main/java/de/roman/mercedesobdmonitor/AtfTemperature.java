package de.roman.mercedesobdmonitor;

import java.util.Locale;

/**
 * ATF-Temperatur der 7G-Tronic 722.9 (ADR-001, Android-frei, testbar).
 * Anfrage an das Getriebesteuergerät: Header 7E1, KWP2000 ReadDataByLocalIdentifier {@code 21 30};
 * Antwort {@code 61 30 …} von 7E9, °C = Byte[11] − 50 (Byte[0] = 0x61). Community-Quelle (W204 C63, W164,
 * W212, W251), für den C350 MJ 2008 nicht bestätigt – deshalb wird die Rohantwort immer mit angezeigt.
 */
public final class AtfTemperature {
    private AtfTemperature() { }

    /** Index des Temperaturbytes in der Antwort, gezählt ab dem Antwortservice 0x61. */
    public static final int BYTE_INDEX = 11;
    public static final int OFFSET = 50;

    /** Ergebnis: Temperatur oder null samt Grund, immer mit Rohdaten. */
    public static final class Reading {
        public final Double celsius;
        public final String problem;
        public final String rawHex;

        Reading(Double celsius, String problem, String rawHex) {
            this.celsius = celsius;
            this.problem = problem;
            this.rawHex = rawHex;
        }

        public String text() {
            String v = celsius != null ? String.format(Locale.GERMANY, "%.0f °C", celsius) : "kein gültiger Wert (" + problem + ")";
            return "ATF-Temperatur: " + v + "\nRohantwort: " + (rawHex.isEmpty() ? "–" : rawHex)
                    + "\nFormel: Byte 11 − 50 (Community-Quelle, am C350 noch nicht bestätigt – bitte kalt und warm vergleichen).";
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
        if (data == null && rejected != null) return new Reading(null, "Steuergerät lehnt die Anfrage ab (7F)", spaced(rejected));
        if (data == null) return new Reading(null, "unerwartete Antwort", spaced(other));
        if (data.length() < (BYTE_INDEX + 1) * 2) return new Reading(null, "Antwort zu kurz", spaced(data));
        int b = Integer.parseInt(data.substring(BYTE_INDEX * 2, BYTE_INDEX * 2 + 2), 16);
        if (b <= 1) return new Reading(null, "Rohwert " + b + " = Sensor/Steuergerät ohne Wert", spaced(data));
        double c = b - OFFSET;
        if (c < -40 || c > 150) return new Reading(null, String.format(Locale.GERMANY, "unplausibel (%.0f °C)", c), spaced(data));
        return new Reading(c, null, spaced(data));
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
