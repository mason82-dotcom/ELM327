package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reine Verbindungs-Policy ohne Android-Abhängigkeiten (unit-testbar).
 */
public final class LinkPolicy {
    /** Ab so vielen Timeouts in Folge wird die TCP-Verbindung neu aufgebaut. */
    public static final int MAX_CONSECUTIVE_TIMEOUTS = 2;
    /** Timeout für die erste Anfrage nach Reconnect (ggf. Protokollsuche bei ATSP0). */
    public static final int FIRST_REQUEST_TIMEOUT_MS = 6000;

    private LinkPolicy() { }

    /**
     * Liefert ATSPx für das per ATDPN erkannte Standardprotokoll 1–9 (optional mit
     * Auto-Präfix "A", z. B. "A6"). Unbekannt, benutzerdefiniert (A–C) oder
     * ungültig → ATSP0 (automatische Suche).
     */
    public static String protocolSelect(String dpn) {
        if (dpn == null) return "ATSP0";
        String code = dpn.trim().toUpperCase(Locale.US).replace(" ", "");
        if (code.length() == 2 && code.charAt(0) == 'A') code = code.substring(1);
        if (code.matches("[1-9]")) return "ATSP" + code;
        return "ATSP0";
    }

    /** Vollständige Adapter-Initialisierung nach einem Reconnect (ohne ATZ). */
    public static List<String> reconnectInit(String dpn) {
        List<String> cmds = new ArrayList<>();
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add("ATH0");
        cmds.add("ATAT1");
        cmds.add(protocolSelect(dpn));
        cmds.add("ATST64");
        return cmds;
    }

    /**
     * Terminalbefehle, die das Antwortformat des Adapters verändern (Echo, Header,
     * Leerzeichen, Zeilenvorschub, Adaptive Timing, Timeout, Reset), würden den Parser
     * für Live-Polling und DTC-Scan stören. Liefert die Befehle, die danach den
     * Polling-Zustand wiederherstellen; leer, wenn nichts zu tun ist.
     * Eine bewusst gewählte Protokollauswahl (ATSPx) bleibt unangetastet.
     */
    public static List<String> restoreAfterTerminal(String command, String dpn) {
        String cmd = CommandSafety.normalize(command);
        List<String> out = new ArrayList<>();
        switch (cmd) {
            case "ATZ" -> out.addAll(reconnectInit(dpn));
            case "ATE1" -> out.add("ATE0");
            case "ATL1" -> out.add("ATL0");
            case "ATS1" -> out.add("ATS0");
            case "ATH1" -> out.add("ATH0");
            case "ATAT0", "ATAT2" -> out.add("ATAT1");
            default -> {
                if (cmd.startsWith("ATST") && !"ATST64".equals(cmd)) out.add("ATST64");
            }
        }
        return out;
    }

    /**
     * Zählt Timeouts in Folge. Ein einzelner Timeout mit erfolgreicher
     * Prompt-Resynchronisation hält die Verbindung; erst wiederholte Timeouts
     * oder eine fehlgeschlagene Resynchronisation erzwingen einen Reconnect.
     * Nicht thread-safe – nur aus einem Polling-Thread verwenden.
     */
    public static final class TimeoutTracker {
        private int consecutive;

        /** @return true, wenn die Verbindung neu aufgebaut werden muss. */
        public boolean onTimeout(boolean resynced) {
            consecutive++;
            return !resynced || consecutive >= MAX_CONSECUTIVE_TIMEOUTS;
        }

        /** Jede vollständige Antwort (auch NO DATA) setzt den Zähler zurück. */
        public void onResponse() {
            consecutive = 0;
        }

        public void reset() {
            consecutive = 0;
        }

        public int consecutive() {
            return consecutive;
        }
    }
}
