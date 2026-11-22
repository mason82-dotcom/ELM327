package de.roman.mercedesobdmonitor;

import java.util.EnumSet;
import java.util.Locale;

/**
 * ADR-004 (Testfreigabe, Android-frei): Daimler-Kennung {@code 1A 86} und Getriebe-Fehlerspeicher {@code 18 02 FF 00}
 * auswerten. Formate nach der Referenzbibliothek ecu_diagnostics (KWP2000 nach Daimler-Standard); am C350 ungeprüft –
 * deshalb steht die Rohantwort immer dabei, und nichts wird erraten.
 */
public final class KwpReadout {
    private KwpReadout() { }

    /** Länge der Antwort {@code 5A 86 …} laut Referenz. */
    static final int IDENT_LENGTH = 18;

    /** Bericht zur Kennung eines Steuergeräts ({@code name} z. B. „Motor (7E0)“). */
    public static String ident(String name, String raw) {
        StringBuilder sb = new StringBuilder(name).append(" – Kennung 1A 86\n");
        Answer a = answer(raw, "5A86", "7F1A");
        if (a.problem != null) return sb.append("  ").append(a.problem).toString();
        byte[] r = a.bytes;
        sb.append("  Rohantwort: ").append(spaced(r)).append('\n');
        if (r.length != IDENT_LENGTH) {
            return sb.append("  Länge ").append(r.length).append(" statt ").append(IDENT_LENGTH)
                    .append(" Bytes – Aufbau weicht von der Referenz ab, nur Rohwerte.").toString();
        }
        String part = bcd(r, 2, 5);
        sb.append("  Teilenummer: ").append(part == null ? "– (keine BCD-Ziffern: " + spaced(r, 2, 5) + ")" : partNumber(part)).append('\n');
        sb.append("  Hardware: KW ").append(week(r[7], r[8])).append(" · Software: KW ").append(week(r[9], r[10])).append('\n');
        sb.append(String.format(Locale.GERMANY, "  Lieferant: %02X · Diagnose-Info: %02X %02X (%s%s)%n", r[11] & 0xFF,
                r[12] & 0xFF, r[13] & 0xFF, (r[12] & 0x80) == 0 ? "Serie" : "Entwicklung",
                (r[13] & 0xFF) >= 0xE0 ? ", Bootmodus" : ""));
        String date = date(r[15], r[16], r[17]);
        sb.append("  Herstelldatum: ").append(date == null ? "– (" + spaced(r, 15, 3) + ")" : date);
        return sb.toString();
    }

    /** Bericht zum Fehlerspeicher des Getriebes ({@code 58 <Anzahl> (Code hi, Code lo, Status)*}). */
    public static String dtcs(String raw) {
        StringBuilder sb = new StringBuilder("Getriebe (7E1) – Fehlerspeicher 18 02 FF 00\n");
        Answer a = answer(raw, "58", "7F18");
        if (a.problem != null) return sb.append("  ").append(a.problem).toString();
        byte[] r = a.bytes;
        sb.append("  Rohantwort: ").append(spaced(r)).append('\n');
        if (r.length < 2) return sb.append("  Antwort zu kurz.").toString();
        int count = r[1] & 0xFF;
        int listed = (r.length - 2) / 3;
        if ((r.length - 2) % 3 != 0) sb.append("  Hinweis: Länge passt nicht zu 3 Bytes je Code – Rest ignoriert.\n");
        if (count == 0 && listed == 0) return sb.append("  Keine gespeicherten Fehler.").toString();
        sb.append(String.format(Locale.GERMANY, "  %d gespeichert%s:%n", count,
                count != listed ? " (aufgelistet " + listed + ")" : ""));
        EnumSet<Vgs7229Codes.Group> active = EnumSet.noneOf(Vgs7229Codes.Group.class);
        EnumSet<Vgs7229Codes.Group> storedOnly = EnumSet.noneOf(Vgs7229Codes.Group.class);
        boolean matched = false, unknown = false;
        for (int i = 0; i < listed; i++) {
            int hi = r[2 + 3 * i] & 0xFF, lo = r[3 + 3 * i] & 0xFF, status = r[4 + 3 * i] & 0xFF;
            String code = Vgs7229Codes.code(hi, lo);   // immer die gesendeten 2 Bytes, nichts vorangestellt
            Vgs7229Codes.Entry e = Vgs7229Codes.lookup(hi, lo);
            sb.append(String.format(Locale.GERMANY, "  • %s · Status %02X (%s) – ", code, status, statusText(status)));
            if (e != null) {
                matched = true;
                (isPresent(status) ? active : storedOnly).addAll(e.groups);
                sb.append(e.text).append('\n');
                continue;
            }
            unknown = true;
            sb.append("nicht in der 722.9-Tabelle (ISO-Lesart ").append(iso(hi, lo)).append(", nur zum Nachschlagen)");
            Vgs7229Codes.Entry maybe = Vgs7229Codes.possibleECode(hi, lo);
            if (maybe != null) {
                sb.append("\n    möglich, nicht belegt: ").append(maybe.code).append(" – ").append(maybe.text);
            }
            sb.append('\n');
        }
        sb.append(Vgs7229Codes.advice(active));
        storedOnly.removeAll(active);
        if (!storedOnly.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Vgs7229Codes.Group g : storedOnly) names.append(names.length() == 0 ? "" : ", ").append(g.title);
            sb.append("  Nur gespeichert, derzeit nicht vorhanden (").append(names)
                    .append("): kein akuter Handlungsbedarf. Tritt der Fehler wieder auf, erneut lesen.\n");
        }
        if (matched) sb.append("  Kurztexte: eigene Fassung der WIS-Tabelle „ETC2 (722.9) ab 2008“.\n");
        if (unknown) sb.append("  Codes ohne Eintrag: Daimler-Kennungen anderer Stände oder Steuergeräte.\n");
        sb.append("  Statusbyte nach ISO 14230 gelesen – am C350 noch nicht mit einem gespeicherten Fehler bestätigt. "
                + "Löschen bleibt gesperrt.");
        return sb.toString().trim();
    }

    /** Speicherzustand im Statusbyte nach ISO 14230-3 (Bits 6–5); Bit 7 = Warnlampe angefordert. */
    static String statusText(int status) {
        String state = switch ((status >> 5) & 0x3) {
            case 3 -> "aktuell vorhanden";
            case 2 -> "in Prüfung";
            case 1 -> "gespeichert, derzeit nicht vorhanden";
            default -> "ohne Speicherzustand";
        };
        return (status & 0x80) != 0 ? state + ", Warnlampe" : state;
    }

    /** Aktuell vorhanden oder noch in Prüfung – dann gilt der Rat sofort. */
    static boolean isPresent(int status) {
        int s = (status >> 5) & 0x3;
        return s == 3 || s == 2;
    }

    /** ISO-15031-6-Schreibweise eines 2-Byte-Codes, z. B. 0x0715 → P0715. */
    static String iso(int hi, int lo) {
        char sys = "PCBU".charAt(hi >> 6);
        return String.format(Locale.US, "%c%X%X%02X", sys, (hi >> 4) & 0x3, hi & 0xF, lo);
    }

    /** Mercedes-Schreibweise „A 272 153 07 79“ aus 10 Ziffern. */
    static String partNumber(String d) {
        return "A " + d.substring(0, 3) + " " + d.substring(3, 6) + " " + d.substring(6, 8) + " " + d.substring(8, 10);
    }

    static String nrc(int code) {
        return switch (code) {
            case 0x10 -> "allgemeine Ablehnung";
            case 0x11 -> "Dienst nicht unterstützt";
            case 0x12 -> "Unterfunktion/Format nicht unterstützt";
            case 0x13 -> "falsche Nachrichtenlänge";
            case 0x21 -> "beschäftigt, später erneut";
            case 0x22 -> "Bedingungen nicht erfüllt";
            case 0x24 -> "falsche Reihenfolge der Anfragen";
            case 0x31 -> "Kennung nicht vorhanden";
            case 0x33 -> "Zugriff verweigert (Security Access)";
            case 0x78 -> "Antwort folgt – der Adapter hat nicht nachgewartet";
            case 0x80 -> "in dieser Sitzung nicht verfügbar";
            default -> "unbekannter Code";
        };
    }

    // ------------------------------------------------------------------ intern

    static final class Answer {
        final byte[] bytes;
        final String problem;

        Answer(byte[] bytes, String problem) {
            this.bytes = bytes;
            this.problem = problem;
        }
    }

    static Answer answer(String raw, String positive, String negative) {
        if (raw == null || ObdParser.isNoData(raw)) return new Answer(null, "keine Antwort (NO DATA)");
        if (ObdParser.isLinkError(raw)) return new Answer(null, "Busfehler: " + raw.trim());
        String rejected = null, other = null;
        for (String msg : ObdParser.canMessages(raw)) {
            if (msg.startsWith(positive)) return new Answer(bytes(msg), null);
            if (msg.startsWith(negative) && rejected == null) rejected = msg;
            else if (other == null) other = msg;
        }
        if (rejected != null) {
            byte[] r = bytes(rejected);
            int code = r.length > 2 ? r[2] & 0xFF : -1;
            return new Answer(null, String.format(Locale.GERMANY, "abgelehnt: %s (Code %02X: %s)", spaced(r), code & 0xFF, nrc(code)));
        }
        return new Answer(null, "unerwartete Antwort: " + (other == null ? raw.trim() : spaced(bytes(other))));
    }

    /** {@code n} Bytes als BCD-Ziffern oder null, wenn ein Halbbyte keine Ziffer ist. */
    static String bcd(byte[] r, int from, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + n; i++) {
            int hi = (r[i] >> 4) & 0xF, lo = r[i] & 0xF;
            if (hi > 9 || lo > 9) return null;
            sb.append(hi).append(lo);
        }
        return sb.toString();
    }

    private static String week(byte w, byte y) {
        String ww = bcd(new byte[] {w}, 0, 1), yy = bcd(new byte[] {y}, 0, 1);
        return ww == null || yy == null ? String.format(Locale.US, "%02X/%02X", w & 0xFF, y & 0xFF) : ww + "/" + yy;
    }

    private static String date(byte y, byte m, byte d) {
        String yy = bcd(new byte[] {y}, 0, 1), mm = bcd(new byte[] {m}, 0, 1), dd = bcd(new byte[] {d}, 0, 1);
        if (yy == null || mm == null || dd == null) return null;
        int month = Integer.parseInt(mm), day = Integer.parseInt(dd);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return dd + "." + mm + ".20" + yy;
    }

    static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String spaced(byte[] r) {
        return spaced(r, 0, r.length);
    }

    private static String spaced(byte[] r, int from, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(r.length, from + n); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", r[i] & 0xFF));
        }
        return sb.toString();
    }
}
