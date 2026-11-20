package de.roman.mercedesobdmonitor;

/**
 * Reines Offline-Modell für die Aktuator-UX. Keine ELM327-Referenz, kein CAN-Header,
 * keine Fahrzeugansteuerung. Für N70/A98 fehlen W204-variantenspezifisch verifizierte Kommandos.
 */
public final class RoofLab {
    public enum Position { GESCHLOSSEN, GEKIPPT, OFFEN }
    public enum Action { OEFFNEN, SCHLIESSEN, KIPPEN, STOPP }
    private Position position = Position.GESCHLOSSEN;
    private boolean blocked;

    public Position position() { return position; }
    public boolean isBlocked() { return blocked; }
    public void setObstacle(boolean obstacle) { blocked = obstacle; }

    public String execute(Action action) {
        if (action == null) return "SIMULATION: unbekannte Aktion";
        if (blocked && (action == Action.SCHLIESSEN || action == Action.KIPPEN)) {
            return "SIMULATION: Einklemmschutz/Blockade – Bewegung verweigert";
        }
        switch (action) {
            case OEFFNEN -> position = Position.OFFEN;
            case SCHLIESSEN -> position = Position.GESCHLOSSEN;
            case KIPPEN -> position = Position.GEKIPPT;
            case STOPP -> { return "SIMULATION: Stopp – keine Fahrzeuganforderung"; }
        }
        return "SIMULATION: Schiebedach " + position + " · ausschließlich lokaler Modellzustand";
    }

    /** Bewusst kein Fahrzeug-Backend; später nur mit belegten Befehlen und eigener ADR. */
    public boolean supportsVehicleActuation() { return false; }
}
