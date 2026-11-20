package de.roman.mercedesobdmonitor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prüfplan für den nächsten Termin am C350 (Android-frei, testbar): sammelt die Ergebnisse der Diagnosen mit Zeit,
 * Kühlmitteltemperatur und Drehzahl, hakt die offenen Fahrzeugtests daran ab und erzeugt einen einzigen teilbaren
 * Bericht mit allen Rohantworten. Nicht thread-safe; die Sitzung ruft es nur unter ihrer eigenen Sperre auf.
 */
public final class TestPlan {
    /** So viele Ergebnisse bleiben gespeichert (die ältesten fallen heraus). */
    public static final int MAX_RECORDS = 60;
    static final double COLD_MAX_C = 40;
    static final double WARM_MIN_C = 70;
    static final int ATF_WARM_READINGS = 4;

    /** Ein gespeichertes Diagnose-Ergebnis. */
    public static final class Record {
        public final ObdSession.Op op;
        public final long timeMs;
        /** Kühlmittel (°C) und Drehzahl beim Ergebnis; NaN = unbekannt. */
        public final double coolantC;
        public final double rpm;
        public final String report;

        public Record(ObdSession.Op op, long timeMs, double coolantC, double rpm, String report) {
            this.op = op;
            this.timeMs = timeMs;
            this.coolantC = coolantC;
            this.rpm = rpm;
            this.report = report == null ? "" : report;
        }

        boolean cold() {
            return !Double.isNaN(coolantC) && coolantC < COLD_MAX_C;
        }

        boolean warm() {
            return !Double.isNaN(coolantC) && coolantC >= WARM_MIN_C;
        }
    }

    private final List<Record> records = new ArrayList<>();

    public void record(Record r) {
        if (r == null || r.op == null) return;
        records.add(r);
        while (records.size() > MAX_RECORDS) records.remove(0);
    }

    public List<Record> records() {
        return new ArrayList<>(records);
    }

    public void clear() {
        records.clear();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    // ------------------------------------------------------------------ Schritte

    /** Ein Schritt des Plans: Titel, Anleitung und wie weit er erfüllt ist. */
    public static final class Step {
        public final String title;
        public final String how;
        public final int done;
        public final int needed;

        Step(String title, String how, int done, int needed) {
            this.title = title;
            this.how = how;
            this.done = Math.min(done, needed);
            this.needed = needed;
        }

        public boolean complete() {
            return done >= needed;
        }
    }

    public List<Step> steps() {
        List<Step> out = new ArrayList<>();
        out.add(new Step("ATF-Temperatur kalt", "vor dem ersten Start des Tages, Kühlmittel unter 40\u00A0°C: Zündung an, "
                + "verbinden, ATF lesen", count(ObdSession.Op.ATF, Record::cold), 1));
        out.add(new Step("ATF-Temperatur warm in P, R, N, D", "warm, Fuß auf der Bremse, je Wählhebelstellung einmal "
                + "ATF lesen – genau in dieser Reihenfolge (prüft das Feldlayout, ADR-003)",
                count(ObdSession.Op.ATF, Record::warm), ATF_WARM_READINGS));
        out.add(new Step("Kennung + Getriebe-Fehler", "Schalter ADR-004 einschalten, dann einmal lesen (Motor aus oder "
                + "Leerlauf)", count(ObdSession.Op.KWP, r -> true), 1));
        out.add(new Step("Starttest", "nach mehreren Stunden Standzeit: Zündung an, Starttest, bei „Jetzt starten“ "
                + "den Motor starten", count(ObdSession.Op.STARTER, r -> true), 1));
        out.add(new Step("Bus mitlesen", "bei laufendem Motor einmal ausführen",
                count(ObdSession.Op.CAN_MONITOR, r -> Double.isNaN(r.rpm) || r.rpm > 300), 1));
        out.add(new Step("Monitortests (Mode 06)", "warm nach einer Fahrt von mindestens 15\u00A0min",
                count(ObdSession.Op.MONITOR_TESTS, Record::warm), 1));
        out.add(new Step("Aussetzer je Zylinder (Mode 06)", "warm nach einigen Minuten Leerlauf (Zylinder 2/3 "
                + "laut Forum auffällig)", count(ObdSession.Op.MISFIRE, Record::warm), 1));
        out.add(new Step("Fehlercodes", "einmal lesen", count(ObdSession.Op.DTC, r -> true), 1));
        return out;
    }

    private int count(ObdSession.Op op, java.util.function.Predicate<Record> ok) {
        int n = 0;
        for (Record r : records) if (r.op == op && ok.test(r)) n++;
        return n;
    }

    /** Checkliste, z. B. „✓ Starttest“ / „○ ATF-Temperatur warm in P, R, N, D (2/4) – …“. */
    public String checklist() {
        StringBuilder sb = new StringBuilder();
        int complete = 0;
        List<Step> steps = steps();
        for (Step s : steps) {
            if (s.complete()) complete++;
            sb.append(s.complete() ? "✓ " : "○ ").append(s.title);
            if (s.needed > 1) sb.append(" (").append(s.done).append('/').append(s.needed).append(')');
            if (!s.complete()) sb.append("\n    ").append(s.how);
            sb.append('\n');
        }
        String head = complete == steps.size()
                ? "Alle " + steps.size() + " Schritte erledigt – Bericht teilen."
                : complete + " von " + steps.size() + " Schritten erledigt · " + records.size()
                        + (records.size() == 1 ? " Ergebnis" : " Ergebnisse") + " gespeichert";
        return head + "\n\n" + sb.toString().trim();
    }

    /** Bericht zum Teilen: Checkliste und jedes Ergebnis mit Zeit, Kühlmittel, Drehzahl und vollständigem Text. */
    public String report(ZoneId zone, String appVersion) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(zone);
        StringBuilder sb = new StringBuilder("Prüfplan C350 (S204/M272)");
        if (appVersion != null && !appVersion.isEmpty()) sb.append(" · ").append(appVersion);
        sb.append("\n\n").append(checklist()).append("\n");
        for (Record r : records) {
            sb.append("\n==== ").append(title(r.op)).append(" · ").append(fmt.format(Instant.ofEpochMilli(r.timeMs)))
                    .append(" · Kühlmittel ").append(Double.isNaN(r.coolantC) ? "–" : String.format(Locale.GERMANY, "%.0f °C", r.coolantC))
                    .append(" · ").append(Double.isNaN(r.rpm) ? "–" : String.format(Locale.GERMANY, "%.0f U/min", r.rpm))
                    .append(" ====\n").append(r.report.trim()).append('\n');
        }
        return sb.toString().trim();
    }

    static String title(ObdSession.Op op) {
        return switch (op) {
            case DTC -> "Fehlercodes";
            case MISFIRE -> "Aussetzer (Mode 06)";
            case INSPECTION -> "HU/AU-Check";
            case MONITOR_TESTS -> "Monitortests (Mode 06)";
            case PROFILE -> "Fahrzeugprofil";
            case ATF -> "ATF-Temperatur";
            case STARTER -> "Starttest";
            case CAN_MONITOR -> "Bus mitlesen";
            case KWP -> "Kennung + Getriebe-Fehler";
        };
    }

    // ------------------------------------------------------------------ Speichern

    private static final String HEAD = "#### ";
    private static final String END = "#### ENDE";

    /** Textformat: je Ergebnis „#### OP;Zeit;Kühlmittel;Drehzahl“, dann der Bericht, dann „#### ENDE“. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            sb.append(HEAD).append(r.op.name()).append(';').append(r.timeMs).append(';')
                    .append(Double.isNaN(r.coolantC) ? "" : String.format(Locale.US, "%.1f", r.coolantC)).append(';')
                    .append(Double.isNaN(r.rpm) ? "" : String.format(Locale.US, "%.0f", r.rpm)).append('\n');
            for (String line : r.report.split("\n", -1)) {
                // Berichtzeilen, die mit '#' oder dem Maskierzeichen beginnen, bekommen ein '\\' davor
                sb.append(line.startsWith("#") || line.startsWith("\\") ? "\\" + line : line).append('\n');
            }
            sb.append(END).append('\n');
        }
        return sb.toString();
    }

    /** Liest {@link #serialize}; beschädigte Einträge werden übersprungen. */
    public static TestPlan parse(String text) {
        TestPlan plan = new TestPlan();
        if (text == null || text.isEmpty()) return plan;
        String[] head = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.equals(END)) {
                if (head != null) {
                    try {
                        plan.record(new Record(ObdSession.Op.valueOf(head[0]), Long.parseLong(head[1]),
                                head[2].isEmpty() ? Double.NaN : Double.parseDouble(head[2]),
                                head[3].isEmpty() ? Double.NaN : Double.parseDouble(head[3]),
                                body.length() > 0 ? body.substring(0, body.length() - 1) : ""));
                    } catch (RuntimeException ignored) { }
                }
                head = null;
                body.setLength(0);
            } else if (line.startsWith(HEAD)) {
                String[] f = line.substring(HEAD.length()).split(";", -1);
                head = f.length == 4 ? f : null;
                body.setLength(0);
            } else if (head != null) {
                body.append(line.startsWith("\\") ? line.substring(1) : line).append('\n');
            }
        }
        return plan;
    }
}
