package de.roman.mercedesobdmonitor;

import java.util.List;

/**
 * Isolierter, Android-freier Demonstrator für mögliche W204-Serviceaktionen.
 *
 * ABSICHTLICH KEINE Verbindung zu ObdSession, Elm327Client oder CommandSafety.
 * Er erzeugt ausschließlich eine Checkliste für Simulator- und Werkstattprüfung.
 * Es gibt hier keine Fahrzeugbefehle und keine Möglichkeit, einen solchen auszuführen.
 */
public final class ServiceActionLab {
    public enum Action {
        CLEAR_EMISSIONS_DTC,
        SUNROOF_OPEN,
        SUNROOF_CLOSE,
        CODING_REVIEW,
        EEPROM_REVIEW,
        ACTUATOR_REVIEW
    }

    public enum Readiness { RESEARCH_ONLY, REQUIRES_VEHICLE_VERIFICATION }

    /**
     * Fahrzeugangabe: normales Schiebe-/Hebedach, kein Panorama.
     * Code 414/N70/M12m1 stammen aus W204-Unterlagen;
     * SA-Code und N70-Teilenummer müssen noch mit der Datenkarte abgeglichen werden.
     * Diese Metadaten berechtigen zu keiner Fahrzeugsteuerung.
     */
    public static final class RoofConfiguration {
        public final String type;
        public final String expectedSaCode;
        public final String expectedController;
        public final String expectedMotor;
        public final boolean confirmedFromVehicleDataCard;

        private RoofConfiguration(String type, String expectedSaCode,
                String expectedController, String expectedMotor, boolean confirmedFromVehicleDataCard) {
            this.type = type;
            this.expectedSaCode = expectedSaCode;
            this.expectedController = expectedController;
            this.expectedMotor = expectedMotor;
            this.confirmedFromVehicleDataCard = confirmedFromVehicleDataCard;
        }
    }

    public static RoofConfiguration currentVehicleRoof() {
        return new RoofConfiguration("Normales elektrisches Glas-Schiebe-/Hebedach",
                "414", "N70", "M12m1", false);
    }

    public static final class Proposal {
        public final Action action;
        public final String label;
        public final Readiness readiness;
        public final List<String> prerequisites;
        public final String limitation;

        private Proposal(Action action, String label, Readiness readiness,
                List<String> prerequisites, String limitation) {
            this.action = action;
            this.label = label;
            this.readiness = readiness;
            this.prerequisites = List.copyOf(prerequisites);
            this.limitation = limitation;
        }
    }

    private ServiceActionLab() { }

    public static Proposal describe(Action action) {
        if (action == null) throw new IllegalArgumentException("Aktion fehlt");
        return switch (action) {
            case CLEAR_EMISSIONS_DTC -> new Proposal(action, "Emissions-DTC löschen",
                    Readiness.REQUIRES_VEHICLE_VERIFICATION,
                    List.of("DTC und Freeze-Frame vorab exportieren", "Auswirkungen auf Readiness dokumentieren",
                            "Motor aus und Zündung an als Prüfbedingung", "Explizite Bestätigung durch den Bediener"),
                    "Nur Laborplanung: kein Mode-04-Sendepfad. Das Löschen kann Diagnose- und Monitordaten zurücksetzen.");
            case SUNROOF_OPEN, SUNROOF_CLOSE -> new Proposal(action,
                    action == Action.SUNROOF_OPEN ? "Schiebedach öffnen" : "Schiebedach schließen",
                    Readiness.RESEARCH_ONLY,
                    List.of("Normales Glas-Schiebe-/Hebedach bestätigt; SA 414 laut W204-Unterlagen, Datenkarte prüfen",
                            "Dachbedieneinheit N70 und Dachmotor M12m1 am konkreten Fahrzeug verifizieren",
                            "Einklemmschutz, Normierung und Blockiererkennung nach Herstellerunterlage nachweisen",
                            "Erreichbarkeit von N70 über OBD-Gateway und unterstützte Werkstattfunktion belegen",
                            "Bediener am stehenden Fahrzeug mit freiem Sichtbereich"),
                    "Keine verifizierte N70/M272/W204-ELM327-Diagnosesequenz. Panoramadach A98 nicht relevant. "
                            + "Keine CAN-Injektion oder Stellgliedsteuerung.");
            case CODING_REVIEW -> new Proposal(action, "Codierung prüfen", Readiness.RESEARCH_ONLY,
                    List.of("SA-Codes und Steuergeräteteilenummern dokumentieren",
                            "Originalcodierung mit zugelassenem Werkstattgerät sichern",
                            "Kompatibilität der Hardware- und Softwarestände prüfen"),
                    "Keine Variantencodierung oder Security-Access-Freigabe.");
            case EEPROM_REVIEW -> new Proposal(action, "EEPROM-Daten prüfen", Readiness.RESEARCH_ONLY,
                    List.of("Steuergerät und Speicherarchitektur identifizieren",
                            "Nur dokumentierte, rechtmäßig zugängliche Lesepfade evaluieren",
                            "Datenminimierung und Wiederherstellbarkeit bewerten"),
                    "Kein EEPROM-Lese- oder Schreibzugriff über ELM327.");
            case ACTUATOR_REVIEW -> new Proposal(action, "Stellgliedtest prüfen", Readiness.RESEARCH_ONLY,
                    List.of("Werkstattfunktion und konkrete Gefährdung identifizieren",
                            "Freigabe- und Abbruchbedingungen spezifizieren",
                            "Zunächst ausschließlich im Simulator nachweisen"),
                    "Keine realen Aktuatorbefehle.");
        };
    }

    public static List<Proposal> catalog() {
        return java.util.Arrays.stream(Action.values()).map(ServiceActionLab::describe).toList();
    }

    /** In dieser Entwicklungsstufe ist keine Aktion zur Fahrzeugausführung freigegeben. */
    public static boolean canExecuteOnVehicle(Action action) {
        if (action == null) throw new IllegalArgumentException("Aktion fehlt");
        return false;
    }
}
