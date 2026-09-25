package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * HU/AU-Vorab-Check aus rein lesenden OBD-Daten. Keine amtliche Prüfung – die
 * Bewertung ist eine Einschätzung auf Basis von MIL, DTCs und Readiness.
 */
public final class InspectionCheck {
    public enum Verdict { READY, NOT_READY, UNCERTAIN }

    public static final class Input {
        public Readiness readiness;
        public List<String> stored = new ArrayList<>();
        public List<String> pending = new ArrayList<>();
        public List<String> permanent = new ArrayList<>();
        public boolean permanentSupported;
        public Integer kmSinceCleared;      // PID 31
        public Integer warmupsSinceCleared; // PID 30
        public Integer kmWithMil;           // PID 21
        public String freezeFrameDtc;       // aus 0202 00
        public List<String> freezeFrame = new ArrayList<>(); // formatierte Zeilen
    }

    /** Unter diesen Werten gelten die Fehlercodes als "kürzlich gelöscht". */
    public static final int RECENT_CLEAR_KM = 100;
    public static final int RECENT_CLEAR_WARMUPS = 10;

    private InspectionCheck() { }

    public static Verdict verdict(Input in) {
        if (in.readiness == null) return Verdict.UNCERTAIN;
        if (in.readiness.milOn || !in.stored.isEmpty() || !in.permanent.isEmpty()) {
            return Verdict.NOT_READY;
        }
        if (in.readiness.incompleteCount() > 0) return Verdict.UNCERTAIN;
        return Verdict.READY;
    }

    public static boolean recentlyCleared(Input in) {
        return (in.kmSinceCleared != null && in.kmSinceCleared < RECENT_CLEAR_KM)
                || (in.warmupsSinceCleared != null && in.warmupsSinceCleared < RECENT_CLEAR_WARMUPS);
    }

    public static String report(Input in) {
        StringBuilder sb = new StringBuilder();
        Verdict v = verdict(in);
        sb.append(switch (v) {
            case READY -> "✅ Vorab-Einschätzung: bereit";
            case NOT_READY -> "❌ Vorab-Einschätzung: nicht bereit";
            case UNCERTAIN -> "⚠️ Vorab-Einschätzung: noch nicht bereit / unklar";
        }).append('\n');
        sb.append("Keine amtliche Prüfung; maßgeblich ist die Prüfstelle.\n\n");

        Readiness r = in.readiness;
        if (r == null) {
            sb.append("Readiness (01 01): keine gültige Antwort\n");
        } else {
            sb.append("MIL (Motorkontrollleuchte): ").append(r.milOn ? "AN" : "aus").append('\n');
            sb.append("Bestätigte DTCs laut ECU: ").append(r.dtcCount);
            if (r.ecuCount > 1) sb.append(" (").append(r.ecuCount).append(" Steuergeräte)");
            sb.append("\n\nReadiness-Monitore:\n");
            for (Readiness.Monitor m : r.monitors) {
                sb.append(m.complete ? "  ✓ " : "  ✗ ").append(m.name)
                        .append(m.complete ? "" : " – nicht abgeschlossen").append('\n');
            }
            if (r.monitors.isEmpty()) sb.append("  (keine Monitore gemeldet)\n");
        }

        sb.append("\nFehlercodes:\n");
        appendCodes(sb, "Gespeichert", in.stored);
        appendCodes(sb, "Pending", in.pending);
        if (in.permanentSupported) appendCodes(sb, "Permanent", in.permanent);
        else sb.append("  Permanent: nicht unterstützt\n");

        if (in.kmSinceCleared != null || in.warmupsSinceCleared != null || in.kmWithMil != null) {
            sb.append("\nSeit dem letzten Löschen:\n");
            if (in.kmSinceCleared != null) sb.append("  Strecke: ").append(in.kmSinceCleared).append(" km\n");
            if (in.warmupsSinceCleared != null) sb.append("  Warmlaufzyklen: ").append(in.warmupsSinceCleared).append('\n');
            if (in.kmWithMil != null) sb.append("  Strecke mit MIL an: ").append(in.kmWithMil).append(" km\n");
        }

        if (in.freezeFrameDtc != null) {
            sb.append("\nFreeze Frame (auslösender Code ").append(in.freezeFrameDtc).append("):\n");
            for (String line : in.freezeFrame) sb.append("  ").append(line).append('\n');
        }

        sb.append("\nHinweise:\n");
        List<String> notes = notes(in);
        if (notes.isEmpty()) sb.append("  keine\n");
        for (String n : notes) sb.append("  • ").append(n).append('\n');
        return sb.toString().trim();
    }

    public static List<String> notes(Input in) {
        List<String> out = new ArrayList<>();
        Readiness r = in.readiness;
        if (r != null && r.milOn) out.add("MIL ist an – Ursache beheben, bevor das Fahrzeug vorgeführt wird.");
        if (!in.stored.isEmpty()) out.add("Gespeicherte Fehlercodes vorhanden – im DTC-Dialog stehen M272-Hinweise.");
        if (!in.permanent.isEmpty()) {
            out.add("Permanente Codes löschen sich nur selbst, nachdem das Steuergerät den Fehler "
                    + "in eigenen Fahrzyklen nicht mehr sieht – Löschen per Tester hilft nicht.");
        }
        if (!in.pending.isEmpty() && in.stored.isEmpty()) {
            out.add("Nur Pending-Codes: Fehler noch nicht bestätigt, kann sich beim nächsten Fahrzyklus verfestigen.");
        }
        if (r != null && r.incompleteCount() > 0) {
            out.add(r.incompleteCount() + " Monitor(e) nicht abgeschlossen. Typisch nach Löschen der "
                    + "Fehlercodes oder Batterie-Abklemmen. Gemischter Fahrzyklus (Kaltstart, Stadt, "
                    + "gleichmäßige Landstraße 80–100 km/h, Leerlaufphasen) über mehrere Tage.");
        }
        if (recentlyCleared(in)) {
            out.add("Fehlercodes wurden kürzlich gelöscht (" + describeClear(in) + ") – "
                    + "Prüfstellen erkennen das an Readiness und diesen Zählern.");
        }
        return out;
    }

    private static String describeClear(Input in) {
        List<String> parts = new ArrayList<>();
        if (in.kmSinceCleared != null) parts.add(in.kmSinceCleared + " km");
        if (in.warmupsSinceCleared != null) parts.add(in.warmupsSinceCleared + " Warmläufe");
        return String.join(", ", parts);
    }

    private static void appendCodes(StringBuilder sb, String label, List<String> codes) {
        sb.append("  ").append(label).append(": ");
        if (codes.isEmpty()) {
            sb.append("keine\n");
            return;
        }
        sb.append(String.join(", ", codes)).append('\n');
    }

    /** Zweibyte-Zähler aus Mode 01 (PID 21/31): A*256+B. */
    public static Integer word(byte[] d) {
        if (d == null || d.length < 2) return null;
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    public static Integer byteValue(byte[] d) {
        if (d == null || d.length < 1) return null;
        return d[0] & 0xFF;
    }

    /** Freeze-Frame-DTC aus "42 02 00 AA BB". "0000" → null (kein Freeze Frame). */
    public static String freezeFrameDtc(String raw) {
        byte[] d = ObdParser.mode02Data(raw, 0x02, 2);
        if (d == null || d.length < 2) return null;
        int a = d[0] & 0xFF, b = d[1] & 0xFF;
        if (a == 0 && b == 0) return null;
        return ObdParser.decodeDtcCode(a, b);
    }
}
