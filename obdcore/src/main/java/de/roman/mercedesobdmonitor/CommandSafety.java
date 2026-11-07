package de.roman.mercedesobdmonitor;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Central hard safety gate for every command that can reach the ELM327.
 *
 * Vehicle-facing commands are restricted to read-only OBD-II services.
 * Adapter commands are restricted to a small whitelist that cannot select
 * arbitrary CAN headers or transmit arbitrary diagnostic payloads.
 */
public final class CommandSafety {
    private static final Set<String> SAFE_AT_EXACT = Set.of(
            "ATZ", "ATI",
            "ATE0", "ATE1",
            "ATL0", "ATL1",
            "ATS0", "ATS1",
            "ATH0", "ATH1",
            "ATAT0", "ATAT1", "ATAT2",
            "ATSP0",
            "ATDP", "ATDPN",
            "ATRV", "ATIGN",
            "AT@1", "AT@2"
    );

    /**
     * ADR-001 (docs/adr-001-atf-temperatur.md): the ONLY manufacturer-specific read and the ONLY header changes.
     * Exact strings, no patterns: header to the transmission ECU, KWP2000 ReadDataByLocalIdentifier 0x30
     * (ATF temperature) and back to the functional OBD header. Any other header, service or identifier
     * stays blocked. The session restores the header in a finally block (ObdSession#readAtfTemperature).
     */
    public static final String ATF_HEADER = "ATSH7E1";
    public static final String ATF_REQUEST = "2130";
    public static final String OBD_HEADER = "ATSH7DF";
    private static final Set<String> ADR001_EXACT = Set.of(ATF_HEADER, ATF_REQUEST, OBD_HEADER);

    private static final Pattern SAFE_TIMEOUT = Pattern.compile("^ATST[0-9A-F]{2}$");
    // Feste Auswahl der Standard-OBD-Protokolle 1–9 (reiner Adapterbefehl, kein Fahrzeug-Request).
    // A–C (benutzerdefinierte CAN-Parameter) bleiben gesperrt.
    private static final Pattern SAFE_PROTOCOL = Pattern.compile("^ATSP[0-9]$");
    private static final Pattern MODE_01 = Pattern.compile("^01[0-9A-F]{2}$");
    private static final Pattern MODE_02 = Pattern.compile("^02[0-9A-F]{4}$");
    private static final Pattern MODE_05 = Pattern.compile("^05(?:[0-9A-F]{2})?$");
    private static final Pattern MODE_06 = Pattern.compile("^06(?:[0-9A-F]{2,4})?$");
    private static final Pattern MODE_09 = Pattern.compile("^09[0-9A-F]{2}$");

    private CommandSafety() { }

    public static String normalize(String command) {
        if (command == null) return "";
        return command.trim().toUpperCase(Locale.US).replace(" ", "");
    }

    public static boolean isAllowed(String command) {
        String cmd = normalize(command);
        if (cmd.isEmpty()) return false;

        // Reject command stacking / embedded line breaks before any other test.
        if (cmd.indexOf('\r') >= 0 || cmd.indexOf('\n') >= 0 || cmd.indexOf(';') >= 0) return false;

        if (ADR001_EXACT.contains(cmd)) return true;
        if (SAFE_AT_EXACT.contains(cmd) || SAFE_TIMEOUT.matcher(cmd).matches()
                || SAFE_PROTOCOL.matcher(cmd).matches()) return true;

        // SAE J1979 read-only services only.
        if (MODE_01.matcher(cmd).matches()) return true; // current data
        if (MODE_02.matcher(cmd).matches()) return true; // freeze-frame data
        if ("03".equals(cmd)) return true;              // stored DTCs
        if (MODE_05.matcher(cmd).matches()) return true; // O2 monitor data
        if (MODE_06.matcher(cmd).matches()) return true; // monitor test results
        if ("07".equals(cmd)) return true;              // pending DTCs
        if (MODE_09.matcher(cmd).matches()) return true; // vehicle information
        return "0A".equals(cmd);                        // permanent DTCs
    }

    /** Header der Grundeinstellung nach ATZ (funktionale OBD-Adresse). */
    public static final String DEFAULT_HEADER = "7DF";

    /**
     * Zustandsabhängige Freigabe (ADR-001): {@code 2130} nur, solange der Header auf das Getriebe (7E1) zeigt;
     * bei Header 7E1 ist außer {@code 2130} nur der Rückweg ({@code ATSH7DF} oder {@code ATZ}) erlaubt. So kann
     * keine normale OBD-Abfrage versehentlich an das Getriebe gehen und die Herstelleranfrage nie an alle.
     */
    public static boolean isAllowed(String command, String currentHeader) {
        if (!isAllowed(command)) return false;
        String cmd = normalize(command);
        boolean transmission = "7E1".equals(currentHeader);
        if (ATF_REQUEST.equals(cmd)) return transmission;
        // On the transmission header, or when the adapter state is unknown (header command without a
        // confirmed answer), only the way back to the standard header is open.
        if (!DEFAULT_HEADER.equals(currentHeader)) return OBD_HEADER.equals(cmd) || "ATZ".equals(cmd);
        return true;
    }

    /** Header-Zustand, solange ein Header-Befehl unbestätigt ist (z. B. nach Zeitüberschreitung). */
    public static final String UNKNOWN_HEADER = "?";

    /** Ob dieser Befehl den Header ändern kann. */
    public static boolean changesHeader(String command) {
        String cmd = normalize(command);
        return ATF_HEADER.equals(cmd) || OBD_HEADER.equals(cmd) || "ATZ".equals(cmd);
    }

    /**
     * Freigabe für das Terminal: wie {@link #isAllowed(String)}, aber ohne die ADR-001-Sequenz – sie läuft nur
     * als exklusive Diagnose mit Header-Wiederherstellung („ATF-Temperatur“).
     */
    public static boolean isAllowedInTerminal(String command) {
        return isAllowed(command) && !ADR001_EXACT.contains(normalize(command));
    }

    /** Header nach erfolgreich gesendetem Befehl. */
    public static String headerAfter(String command, String currentHeader) {
        String cmd = normalize(command);
        if (ATF_HEADER.equals(cmd)) return "7E1";
        if (OBD_HEADER.equals(cmd) || "ATZ".equals(cmd)) return DEFAULT_HEADER;
        return currentHeader;
    }

    public static String blockedReason(String command) {
        String cmd = normalize(command);
        if (ATF_REQUEST.equals(cmd)) {
            return "21 30 (ATF-Temperatur) ist nur an das Getriebesteuergerät (Header 7E1) erlaubt.";
        }
        if ("04".equals(cmd)) {
            return "Mode 04 (Fehlercodes löschen / ECU-Reset der Emissionsdiagnose) ist gesperrt.";
        }
        if (cmd.startsWith("ATSH") || cmd.startsWith("ATCRA") || cmd.startsWith("ATCEA")
                || cmd.startsWith("ATTA") || cmd.startsWith("ATCP")) {
            return "Änderungen an CAN-Headern/Adressierung sind im Read-Only-Modus gesperrt "
                    + "(einzige Ausnahme: ATF-Temperatur nach ADR-001).";
        }
        return "Befehl ist nicht in der Read-Only-Whitelist freigegeben.";
    }
}
