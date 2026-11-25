package de.roman.mercedesobdmonitor;

/**
 * Gestaffeltes Mode-01-Polling (rein, unit-testbar).
 *
 * Normalbetrieb: schnelle PIDs jeder Zyklus, mittlere jeder 2., langsame jeder 5.
 * Während des Fuel-Trim-Tests werden alle für die Auswertung nötigen PIDs
 * (Trims, Drehzahl, Geschwindigkeit, MAF, MAP) in jedem Zyklus gelesen; übrige
 * PIDs nur jeden 10. Zyklus, um den Zyklus kurz zu halten.
 */
public final class PollSchedule {
    private PollSchedule() { }

    /** Höchstalter für langsame PIDs: im Log 21./22.11.2026 alle 10–12,7 s, einzeln über 15 s. */
    public static final long SLOW_MAX_AGE_MS = 30_000;

    /** Höchstalter eines Live-Werts, passend zum Abfragetakt des PIDs (Getriebewerte: wie schnelle PIDs). */
    public static long maxAgeMs(int key, boolean fuelTrimFocus) {
        if (key > 0xFF) return LiveValues.MAX_AGE_MS;
        return isSlow(key, fuelTrimFocus) ? SLOW_MAX_AGE_MS : LiveValues.MAX_AGE_MS;
    }

    /** Langsam = im Standardzweig von {@link #shouldPoll} (jede 5. bzw. 10. Runde). */
    private static boolean isSlow(int pid, boolean fuelTrimFocus) {
        for (int cycle = 1; cycle <= 4; cycle++) if (shouldPoll(pid, cycle, fuelTrimFocus)) return false;
        return true;
    }

    public static boolean shouldPoll(int pid, int cycle, boolean fuelTrimFocus) {
        if (fuelTrimFocus) {
            return switch (pid) {
                case 0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x10, 0x0B -> true;
                // Warnungen (LiveWarnings) brauchen Kühlmittel/Spannung auch während des Tests regelmäßig
                case 0x05, 0x42 -> (cycle % 3) == 0;
                default -> (cycle % 10) == 0;
            };
        }
        return switch (pid) {
            case 0x0C, 0x10, 0x06, 0x08, 0x44 -> true;
            // 05/42 für Kühlmittel-/Bordnetzwarnungen (LiveWarnings): vorher jeder 5. Zyklus, im Log bis 23 s Abstand.
            case 0x0D, 0x04, 0x11, 0x0B, 0x05, 0x42 -> (cycle % 2) == 0;
            // Analyses (consumption, lambda quality, catalyst tendency, E-gas) need these at medium rate.
            case 0x03, 0x0E, 0x15, 0x19, 0x24, 0x28, 0x34, 0x38, 0x14, 0x18, 0x45, 0x49, 0x4A -> (cycle % 2) == 1;
            default -> (cycle % 5) == 0;
        };
    }
}
