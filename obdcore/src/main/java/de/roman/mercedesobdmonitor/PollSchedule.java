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

    public static boolean shouldPoll(int pid, int cycle, boolean fuelTrimFocus) {
        if (fuelTrimFocus) {
            return switch (pid) {
                case 0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x10, 0x0B -> true;
                default -> (cycle % 10) == 0;
            };
        }
        return switch (pid) {
            case 0x0C, 0x10, 0x06, 0x08, 0x44 -> true;
            case 0x0D, 0x04, 0x11, 0x0B -> (cycle % 2) == 0;
            // Analyses (consumption, lambda quality, catalyst tendency, E-gas) need these at medium rate.
            case 0x03, 0x0E, 0x15, 0x19, 0x24, 0x28, 0x34, 0x38, 0x14, 0x18, 0x49, 0x4A -> (cycle % 2) == 1;
            default -> (cycle % 5) == 0;
        };
    }
}
