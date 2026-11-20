package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ADR-007 (Testfreigabe, Android-frei): Antworten der Kennungssuche {@code 21 01}–{@code 21 FE} am Motorsteuergerät
 * einordnen und Kandidaten für die Motoröltemperatur nennen. Nichts wird als Öltemperatur ausgegeben: Kandidaten sind
 * Bytes, die – als Temperatur gelesen – in einem plausiblen Fenster um die Kühlmitteltemperatur liegen. Bestätigt ist
 * ein Kandidat erst, wenn er in einem zweiten Lauf bei mindestens {@link #CONFIRM_DELTA_K} anderer Temperatur wieder
 * im Fenster liegt (gleiche Kennung, gleiches Byte, gleiche Skalierung).
 */
public final class LocalIdScan {
    /** Fenster unter der Kühlmitteltemperatur (K). */
    static final double BELOW_K = 5;
    /** Fenster darüber: kalt liegen Öl und Kühlmittel nah beieinander, warm ist Öl meist 5–20 K wärmer. */
    static final double ABOVE_COLD_K = 5;
    static final double ABOVE_WARM_K = 25;
    static final double WARM_FROM_C = 60;
    /** Mindestabstand der Kühlmitteltemperatur zwischen zwei Läufen für eine Bestätigung. */
    static final double CONFIRM_DELTA_K = 20;

    /** Gängige Temperaturskalierungen von Steuergeräte-Bytes. */
    enum Scale {
        OFFSET_40("A − 40"), RAW("A"), BOSCH("0,75·A − 48");

        final String text;

        Scale(String text) {
            this.text = text;
        }

        double apply(int a) {
            return switch (this) {
                case OFFSET_40 -> a - 40;
                case RAW -> a;
                case BOSCH -> 0.75 * a - 48;
            };
        }
    }

    /** Einordnung einer Antwort. */
    public enum Kind { POSITIVE, REJECTED, SILENT, SERVICE_UNAVAILABLE }

    /** Eine positive Antwort {@code 61 <id> <Daten…>}. */
    public static final class Record {
        public final int id;
        public final byte[] data;

        Record(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }

    /** Ein Kandidat: Byte {@code index} der Daten von Kennung {@code id}. */
    public static final class Candidate {
        public final int id;
        public final int index;
        final Scale scale;
        public final double value;

        Candidate(int id, int index, Scale scale, double value) {
            this.id = id;
            this.index = index;
            this.scale = scale;
            this.value = value;
        }

        String key() {
            return id + ":" + index + ":" + scale;
        }
    }

    private final List<Record> records = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    private int silent;
    private String serviceProblem;

    /** Antwort auf {@code 21 <id>} aufnehmen. */
    public Kind add(int id, String raw) {
        String hexId = String.format(Locale.US, "%02X", id);
        KwpReadout.Answer a = KwpReadout.answer(raw, "61" + hexId, "7F21");
        if (a.problem == null) {
            byte[] r = a.bytes;
            byte[] data = new byte[Math.max(0, r.length - 2)];
            System.arraycopy(r, 2, data, 0, data.length);
            records.add(new Record(id, data));
            return Kind.POSITIVE;
        }
        if (a.problem.startsWith("abgelehnt")) {
            int nrc = nrc(raw);
            // 11 = Dienst nicht unterstützt, 7F/80 = nicht in dieser Sitzung: gilt für alle Kennungen
            if (nrc == 0x11 || nrc == 0x7F || nrc == 0x80) {
                serviceProblem = a.problem;
                return Kind.SERVICE_UNAVAILABLE;
            }
            rejected.add(hexId);
            return Kind.REJECTED;
        }
        silent++;
        return Kind.SILENT;
    }

    /** Zeitüberschreitung bei {@code 21 <id>}. */
    public void timeout() {
        silent++;
    }

    public List<Record> records() {
        return records;
    }

    private static int nrc(String raw) {
        for (String msg : ObdParser.canMessages(raw)) {
            if (msg.startsWith("7F21") && msg.length() >= 6) return Integer.parseInt(msg.substring(4, 6), 16);
        }
        return -1;
    }

    /** Bytes, die als Temperatur im Fenster um {@code coolantC} liegen. */
    public List<Candidate> candidates(double coolantC) {
        List<Candidate> out = new ArrayList<>();
        if (Double.isNaN(coolantC)) return out;
        double lo = coolantC - BELOW_K;
        double hi = coolantC + (coolantC >= WARM_FROM_C ? ABOVE_WARM_K : ABOVE_COLD_K);
        for (Record r : records) {
            for (int i = 0; i < r.data.length; i++) {
                int a = r.data[i] & 0xFF;
                for (Scale s : Scale.values()) {
                    double v = s.apply(a);
                    if (v >= lo && v <= hi) out.add(new Candidate(r.id, i, s, v));
                }
            }
        }
        return out;
    }

    /**
     * Kandidaten, die auch im früheren Lauf {@code previous} (bei {@code previousCoolantC}) im Fenster lagen – nur wenn
     * die Kühlmitteltemperaturen mindestens {@link #CONFIRM_DELTA_K} auseinanderliegen und der Wert mitgewandert ist.
     */
    public List<Candidate> confirmed(double coolantC, LocalIdScan previous, double previousCoolantC) {
        List<Candidate> out = new ArrayList<>();
        if (previous == null || Double.isNaN(coolantC) || Double.isNaN(previousCoolantC)
                || Math.abs(coolantC - previousCoolantC) < CONFIRM_DELTA_K) return out;
        java.util.Map<String, Candidate> before = new java.util.HashMap<>();
        for (Candidate c : previous.candidates(previousCoolantC)) before.put(c.key(), c);
        for (Candidate c : candidates(coolantC)) {
            Candidate b = before.get(c.key());
            // mitgewandert: gleiche Richtung wie das Kühlmittel
            if (b != null && Math.signum(c.value - b.value) == Math.signum(coolantC - previousCoolantC)) out.add(c);
        }
        return out;
    }

    /** Bericht für Dialog und Prüfplan. {@code previous} darf null sein. */
    public String report(double coolantC, int sent, String stopReason, LocalIdScan previous, double previousCoolantC) {
        StringBuilder sb = new StringBuilder("Motor (7E0) – Kennungssuche 21 01–21 FE (ADR-007, Test)\n");
        sb.append(String.format(Locale.GERMANY, "  gesendet %d · Antworten %d · abgelehnt %d · ohne Antwort %d%n",
                sent, records.size(), rejected.size(), silent));
        if (serviceProblem != null) sb.append("  Dienst 21 nicht verfügbar: ").append(serviceProblem).append('\n');
        if (stopReason != null) sb.append("  VORZEITIG BEENDET: ").append(stopReason).append('\n');
        sb.append(Double.isNaN(coolantC) ? "  Kühlmittel: unbekannt – keine Kandidaten\n"
                : String.format(Locale.GERMANY, "%s%.0f °C%n", COOLANT_PREFIX, coolantC));
        if (!Double.isNaN(coolantC)) {
            List<Candidate> conf = confirmed(coolantC, previous, previousCoolantC);
            if (!conf.isEmpty()) {
                sb.append(String.format(Locale.GERMANY, "  BESTÄTIGT gegen den Lauf bei %.0f °C:%n", previousCoolantC));
                for (Candidate c : conf) sb.append(line(c));
            } else if (previous != null) {
                sb.append(String.format(Locale.GERMANY, "  Kein Kandidat gegen den Lauf bei %.0f °C bestätigt%s.%n",
                        previousCoolantC, Math.abs(coolantC - previousCoolantC) < CONFIRM_DELTA_K
                                ? " (Temperaturen liegen weniger als " + (int) CONFIRM_DELTA_K + " K auseinander)" : ""));
            }
            List<Candidate> cands = candidates(coolantC);
            double above = coolantC >= WARM_FROM_C ? ABOVE_WARM_K : ABOVE_COLD_K;
            sb.append(String.format(Locale.GERMANY, "  Kandidaten im Fenster %.0f…%.0f °C: %d%n",
                    coolantC - BELOW_K, coolantC + above, cands.size()));
            int shown = 0;
            for (Candidate c : cands) {
                if (shown++ >= 40) {
                    sb.append("  … weitere in den Rohdaten\n");
                    break;
                }
                sb.append(line(c));
            }
        }
        if (!rejected.isEmpty()) sb.append("  Abgelehnt: ").append(String.join(" ", rejected)).append('\n');
        if (!records.isEmpty()) sb.append("  Positive Antworten (Kennung: Datenbytes):\n");
        for (Record r : records) {
            sb.append(String.format(Locale.GERMANY, "  21 %02X [%d]: %s%n", r.id, r.data.length,
                    r.data.length == 0 ? "–" : KwpReadout.spaced(r.data)));
        }
        sb.append("Bitte kalt (nach Standzeit) und warm (nach Fahrt) je einmal ausführen und den Prüfplan teilen.");
        return sb.toString();
    }

    private static String line(Candidate c) {
        return String.format(Locale.GERMANY, "  • 21 %02X Byte %d: %s = %.1f °C%n", c.id, c.index, c.scale.text, c.value);
    }

    static final String COOLANT_PREFIX = "  Kühlmittel (01 05) vorher: ";

    /** Kühlmitteltemperatur aus einem eigenen Bericht (für die Einordnung kalt/warm im Prüfplan); sonst NaN. */
    public static double coolantFromReport(String report) {
        if (report == null) return Double.NaN;
        int i = report.indexOf(COOLANT_PREFIX.trim());
        if (i < 0) return Double.NaN;
        String rest = report.substring(i + COOLANT_PREFIX.trim().length()).trim();
        int end = rest.indexOf(' ');
        try {
            return Double.parseDouble((end < 0 ? rest : rest.substring(0, end)).replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ Speichern (für den Vergleichslauf)

    /** Eine Zeile je positiver Antwort „id:hex“, erste Zeile die Kühlmitteltemperatur. */
    public String serialize(double coolantC) {
        StringBuilder sb = new StringBuilder(Double.isNaN(coolantC) ? "NaN" : String.valueOf(coolantC)).append('\n');
        for (Record r : records) {
            sb.append(String.format(Locale.US, "%02X:", r.id));
            for (byte b : r.data) sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Gegenstück zu {@link #serialize}; liefert null bei leerem oder beschädigtem Text. */
    public static Saved parse(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.split("\n");
        try {
            double coolant = Double.parseDouble(lines[0].trim());
            LocalIdScan s = new LocalIdScan();
            Set<Integer> seen = new LinkedHashSet<>();
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                int colon = l.indexOf(':');
                int id = Integer.parseInt(l.substring(0, colon), 16);
                if (!seen.add(id)) continue;
                s.records.add(new Record(id, KwpReadout.bytes(l.substring(colon + 1))));
            }
            return new Saved(s, coolant);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ein gespeicherter Lauf. */
    public static final class Saved {
        public final LocalIdScan scan;
        public final double coolantC;

        Saved(LocalIdScan scan, double coolantC) {
            this.scan = scan;
            this.coolantC = coolantC;
        }
    }
}
