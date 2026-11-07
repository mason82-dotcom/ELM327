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

    /**
     * Header-Anzeige im Live-Polling: auf 11-bit-CAN an (ATH1), damit die Antwort des Motorsteuergeräts (7E8)
     * von der des Getriebes (7E9) unterschieden wird (C350-Log 07.11.2026); sonst aus. Diagnosen laufen immer
     * mit ATH0 ({@link #DIAG_HEADERS}) und stellen danach diesen Zustand wieder her.
     */
    public static String liveHeaders(String dpn) {
        return isCan11Bit(dpn) ? "ATH1" : "ATH0";
    }

    /** Header-Anzeige für Diagnosen (DTC, Mode 06, HU/AU-Check, Freeze Frame …): deren Parser erwarten ATH0. */
    public static final String DIAG_HEADERS = "ATH0";

    /**
     * Vollständige Adapter-Initialisierung nach einem Reconnect (ohne ATZ). Ein WLAN-Adapter behält seinen
     * Header über TCP-Reconnects; bei 11-bit-CAN wird er deshalb ausdrücklich auf 7DF gesetzt (ADR-001).
     */
    public static List<String> reconnectInit(String dpn) {
        List<String> cmds = new ArrayList<>();
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add(liveHeaders(dpn));
        cmds.add("ATAT1");
        cmds.add(protocolSelect(dpn));
        if (isCan11Bit(dpn)) cmds.add(CommandSafety.OBD_HEADER);
        cmds.add("ATST64");
        return cmds;
    }

    /** ISO 15765-4 mit 11-bit-Kennungen (ATDPN 6 oder 8, optional mit „A“). */
    public static boolean isCan11Bit(String dpn) {
        String sp = protocolSelect(dpn);
        return "ATSP6".equals(sp) || "ATSP8".equals(sp);
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
            case "ATH0", "ATH1" -> {
                if (!cmd.equals(liveHeaders(dpn))) out.add(liveHeaders(dpn));
            }
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
