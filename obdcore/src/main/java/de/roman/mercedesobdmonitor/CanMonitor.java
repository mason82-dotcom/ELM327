package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Auswertung des passiven Mitlesens am OBD-Port (ADR-002, {@code ATMA} mit ATH1/ATCAF0 auf 11-bit-CAN).
 *
 * Android-frei und rein lesend: zählt die gesehenen CAN-Kennungen und zeigt für einige W204-Antriebsstrang-
 * Botschaften die Rohwerte an den Bitpositionen, die die XSMonitor-Beschreibungen im Projekt rnd-ash/MBUX-Port
 * (Ordner 204/POWERTRAIN.txt, Stand 2021) nennen. Dort fehlen Faktor und Versatz; deshalb rechnet diese Klasse
 * nichts in physikalische Einheiten um. Ob der C350 diese Botschaften überhaupt am OBD-Port zeigt (Gateway),
 * ist offen – genau das soll der Test am Fahrzeug klären.
 */
public final class CanMonitor {

    /** Ein Signal an einer festen Bitposition (Zählung wie MBUX-Port: Bit 0 = höchstwertiges Bit von Byte 0). */
    public static final class Signal {
        public final String name;
        public final int offset;
        public final int length;
        public final String note;

        Signal(String name, int offset, int length, String note) {
            this.name = name;
            this.offset = offset;
            this.length = length;
            this.note = note;
        }
    }

    /** Bekannte Botschaft aus der W204-Beschreibung. */
    public static final class KnownFrame {
        public final int id;
        public final String name;
        public final String sender;
        public final List<Signal> signals;

        KnownFrame(int id, String name, String sender, List<Signal> signals) {
            this.id = id;
            this.name = name;
            this.sender = sender;
            this.signals = signals;
        }
    }

    /** Auswahl für den C350 (M272/722.9); Namen und Positionen aus MBUX-Port/204/POWERTRAIN.txt. */
    public static final Map<Integer, KnownFrame> W204_POWERTRAIN = new LinkedHashMap<>();

    static {
        add(0x30D, "ECM_A1", "Motorsteuergerät",
                new Signal("Kühlmitteltemperatur", 0, 8, "°C, Versatz unbekannt"),
                new Signal("Ansauglufttemperatur", 8, 8, "°C, Versatz unbekannt"),
                new Signal("Motoröltemperatur", 16, 8, "°C, Versatz unbekannt"),
                new Signal("Motorölstand", 24, 8, "mm, Skalierung unbekannt"),
                new Signal("Motorölqualität", 32, 8, "ohne Einheit"),
                new Signal("Kraftstoffverbrauch", 40, 16, "µl je 250 ms laut Beschreibung"));
        add(0x105, "ENG_RS3_PT", "Motorsteuergerät",
                new Signal("Motordrehzahl", 2, 14, "U/min laut Beschreibung"));
        add(0x2F1, "TCM_A1", "Getriebesteuergerät",
                new Signal("Getriebeöltemperatur", 0, 8, "°C, Versatz unbekannt"),
                new Signal("Übertemperatur Getriebe", 15, 1, "1 = ja"),
                new Signal("Wählhebelstellung", 17, 3, "Kodierung unbekannt"));
        add(0x0F3, "ENG_RQ2_TCM", "Getriebesteuergerät",
                new Signal("Zielgang", 0, 4, "Kodierung unbekannt"),
                new Signal("Istgang", 4, 4, "Kodierung unbekannt"));
        add(0x19F, "IC_A1", "Kombiinstrument",
                new Signal("Angezeigte Geschwindigkeit", 4, 12, "km/h laut Beschreibung"),
                new Signal("Angezeigte Außentemperatur", 16, 8, "°C, Versatz unbekannt"));
    }

    private static void add(int id, String name, String sender, Signal... signals) {
        W204_POWERTRAIN.put(id, new KnownFrame(id, name, sender, List.of(signals)));
    }

    /** Statistik einer Kennung. */
    public static final class IdStats {
        public final int id;
        public int count;
        public byte[] last;
        public int changes;

        IdStats(int id) {
            this.id = id;
        }
    }

    /** Ergebnis eines Mitlese-Laufs. */
    public static final class Result {
        public final Map<Integer, IdStats> ids = new TreeMap<>();
        public int frames;
        public int unparsed;
        public boolean bufferFull;
        public boolean canError;
        public boolean noData;
        /** Adapter hat ATMA nicht verstanden („?“), typisch für manche Klone. */
        public boolean unsupported;
    }

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("[0-9A-F]+");

    private CanMonitor() { }

    /**
     * Nur vollständige Zeilen behalten: was nach dem letzten CR/LF steht, hat das Datenlimit oder das
     * Stopp-Zeichen mitten im Frame abgeschnitten und würde als kürzerer, falscher Frame gezählt.
     */
    static String completeLines(String raw) {
        if (raw == null) return "";
        int cut = Math.max(raw.lastIndexOf('\r'), raw.lastIndexOf('\n'));
        return (cut < 0 ? "" : raw.substring(0, cut)).trim();
    }

    /** Zerlegt die ATMA-Ausgabe (11-bit, mit Header, mit oder ohne Leerzeichen, ohne DLC-Anzeige). */
    public static Result parse(String raw) {
        Result r = new Result();
        if (raw == null) return r;
        for (String line : raw.split("[\\r\\n]+")) {
            String l = line.trim().toUpperCase(Locale.US);
            if (l.isEmpty() || l.equals(CommandSafety.MONITOR_ALL)) continue; // Echo bei ATE1
            if (l.equals("?")) { r.unsupported = true; continue; }
            if (l.contains("BUFFER FULL")) { r.bufferFull = true; continue; }
            if (l.contains("CAN ERROR") || l.contains("BUS ERROR")) { r.canError = true; continue; }
            if (l.contains("NO DATA")) { r.noData = true; continue; }
            if (l.contains("SEARCHING") || l.contains("STOPPED")) continue;
            String hex = l.replace(" ", "");
            if (!HEX.matcher(hex).matches() || hex.length() < 3 || hex.length() > 3 + 16 || (hex.length() - 3) % 2 != 0) {
                r.unparsed++;
                continue;
            }
            int id = Integer.parseInt(hex.substring(0, 3), 16);
            if (id > 0x7FF) { r.unparsed++; continue; }
            byte[] data = new byte[(hex.length() - 3) / 2];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) Integer.parseInt(hex.substring(3 + 2 * i, 5 + 2 * i), 16);
            }
            IdStats s = r.ids.computeIfAbsent(id, IdStats::new);
            if (s.last != null && !java.util.Arrays.equals(s.last, data)) s.changes++;
            s.last = data;
            s.count++;
            r.frames++;
        }
        return r;
    }

    /**
     * Bits nach MBUX-Port-Zählung lesen: Bit 0 ist das höchstwertige Bit von Byte 0, ein Feld reicht
     * big-endian über Bytegrenzen. -1, wenn der Frame zu kurz ist.
     */
    public static long bits(byte[] data, int offset, int length) {
        if (data == null || length < 1 || length > 32 || offset < 0 || offset + length > data.length * 8) return -1;
        long v = 0;
        for (int i = 0; i < length; i++) {
            int bit = offset + i;
            int b = data[bit / 8] & 0xFF;
            v = (v << 1) | ((b >> (7 - bit % 8)) & 1);
        }
        return v;
    }

    /** Diagnose-Antworten (7E8–7EF) sind keine Bus-Botschaften, sondern Reste einer Abfrage. */
    static boolean isDiagnosticResponse(int id) {
        return id >= 0x7E8 && id <= 0x7EF;
    }

    public static String report(Result r, long elapsedMs, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        double sec = Math.max(0.001, elapsedMs / 1000.0);
        sb.append(String.format(Locale.GERMANY, "Mitgelesen: %.1f s · %d Frames · %d Kennungen%n",
                sec, r.frames, r.ids.size()));
        if (r.unsupported) {
            sb.append("Der Adapter kennt ATMA nicht (Antwort „?“). Mitlesen ist mit diesem Adapter nicht möglich.\n");
            return sb.toString().trim();
        }
        if (r.canError) sb.append("Hinweis: CAN ERROR – Adapter meldet Busfehler.\n");
        if (r.bufferFull) sb.append("Hinweis: BUFFER FULL – der Adapter kam nicht mit; die Liste ist unvollständig.\n");
        if (truncated) sb.append("Hinweis: Datenmenge begrenzt, der Rest wurde verworfen.\n");
        if (r.frames == 0) {
            if (r.noData) sb.append("Der Adapter meldet NO DATA.\n");
            sb.append("\nKeine Bus-Botschaften am OBD-Port. Das ist beim W204 zu erwarten, wenn das Gateway nur ")
                    .append("Diagnoseverkehr durchreicht. Passive Werte (Öl, ATF, Gang) sind dann nur per Abfrage ")
                    .append("erreichbar.");
            return sb.toString().trim();
        }
        int busIds = 0;
        for (IdStats s : r.ids.values()) if (!isDiagnosticResponse(s.id)) busIds++;
        if (busIds == 0) {
            sb.append("\nNur Diagnose-Antworten (7E8–7EF), keine Bus-Botschaften: das Gateway reicht ")
                    .append("den Antriebsstrang-Bus nicht an den OBD-Port durch.");
            return sb.toString().trim();
        }

        sb.append("\nBekannte W204-Botschaften (Rohwerte, Umrechnung unbelegt):\n");
        boolean anyKnown = false;
        for (KnownFrame k : W204_POWERTRAIN.values()) {
            IdStats s = r.ids.get(k.id);
            if (s == null) continue;
            anyKnown = true;
            appendKnown(sb, k, s, sec);
        }
        if (!anyKnown) sb.append("• keine der bekannten Kennungen gesehen\n");

        sb.append("\nAlle Kennungen (Anzahl · Wechsel · letzter Inhalt):\n");
        for (IdStats s : r.ids.values()) {
            sb.append(String.format(Locale.GERMANY, "%03X  %4d · %3d · %s%s%n", s.id, s.count, s.changes, hex(s.last),
                    isDiagnosticResponse(s.id) ? "  (Diagnose-Antwort)" : ""));
        }
        if (r.unparsed > 0) sb.append(r.unparsed).append(" Zeilen nicht lesbar\n");
        return sb.toString().trim();
    }

    private static void appendKnown(StringBuilder sb, KnownFrame k, IdStats s, double sec) {
        sb.append(String.format(Locale.GERMANY, "• %03X %s (%s) · %d Frames · %.1f/s · %s%n",
                k.id, k.name, k.sender, s.count, s.count / sec, hex(s.last)));
        for (Signal sig : k.signals) {
            long v = bits(s.last, sig.offset, sig.length);
            sb.append("    ").append(sig.name).append(": ")
                    .append(v < 0 ? "–" : String.valueOf(v))
                    .append(" (").append(sig.note).append(")\n");
        }
    }

    /** Ein gefilterter Lauf (ADR-002 Option C): nur Frames einer Kennung. */
    public static final class FilteredRun {
        final Result result;
        final long elapsedMs;
        final boolean truncated;

        public FilteredRun(Result result, long elapsedMs, boolean truncated) {
            this.result = result;
            this.elapsedMs = elapsedMs;
            this.truncated = truncated;
        }
    }

    /**
     * Bericht der gefilterten Läufe: je bekannter Kennung empfangen oder nicht, mit Rohwerten. Leer, wenn keiner lief.
     */
    public static String filteredReport(Map<Integer, FilteredRun> runs) {
        if (runs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nGefiltert je Kennung (Empfangsfilter, je etwa 2 s):\n");
        int received = 0;
        for (Map.Entry<Integer, FilteredRun> e : runs.entrySet()) {
            KnownFrame k = W204_POWERTRAIN.get(e.getKey());
            FilteredRun run = e.getValue();
            IdStats s = run.result.ids.get(k.id);
            if (run.result.unsupported) {
                sb.append(String.format(Locale.GERMANY, "• %03X %s – ATMA nicht verstanden (Antwort „?“)%n", k.id, k.name));
            } else if (s == null) {
                sb.append(String.format(Locale.GERMANY, "• %03X %s (%s) – nicht empfangen%s%n", k.id, k.name, k.sender,
                        run.result.bufferFull ? " (BUFFER FULL trotz Filter)" : ""));
            } else {
                received++;
                appendKnown(sb, k, s, Math.max(0.001, run.elapsedMs / 1000.0));
                if (run.result.bufferFull) sb.append("    Hinweis: BUFFER FULL trotz Filter\n");
            }
            int other = run.result.frames - (s == null ? 0 : s.count);
            if (other > 0) sb.append("    ").append(other).append(" andere Frames trotz Filter – Filter wirkt nicht\n");
            if (run.truncated) sb.append("    Hinweis: Datenmenge begrenzt, der Rest wurde verworfen.\n");
        }
        sb.append(received == 0
                ? "Keine der bekannten Botschaften kam gefiltert an: Öl-, ATF- und Ganganzeige vom Bus sind am OBD-Port "
                        + "nicht erreichbar (oder der Adapter filtert nicht)."
                : received + " von " + runs.size() + " bekannten Botschaften kommen am OBD-Port an – Rohwerte noch ohne "
                        + "Eichung (Vergleich mit den OBD-Werten offen).");
        return sb.toString();
    }

    static String hex(byte[] data) {
        if (data == null) return "";
        List<String> parts = new ArrayList<>(data.length);
        for (byte b : data) parts.add(String.format(Locale.US, "%02X", b & 0xFF));
        return String.join(" ", parts);
    }
}
