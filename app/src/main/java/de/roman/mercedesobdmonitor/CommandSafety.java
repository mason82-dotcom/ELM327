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

    public static String blockedReason(String command) {
        String cmd = normalize(command);
        if ("04".equals(cmd)) {
            return "Mode 04 (Fehlercodes löschen / ECU-Reset der Emissionsdiagnose) ist gesperrt.";
        }
        if (cmd.startsWith("ATSH") || cmd.startsWith("ATCRA") || cmd.startsWith("ATCEA")
                || cmd.startsWith("ATTA") || cmd.startsWith("ATCP")) {
            return "Änderungen an CAN-Headern/Adressierung sind im Read-Only-Modus gesperrt.";
        }
        return "Befehl ist nicht in der Read-Only-Whitelist freigegeben.";
    }
}
