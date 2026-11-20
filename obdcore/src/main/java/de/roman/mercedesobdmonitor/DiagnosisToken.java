package de.roman.mercedesobdmonitor;

/**
 * Verhindert, dass ältere Aufträge derselben Verbindung nach einem neuen Scan
 * oder einem DTC-Abbruch UI-Zustände und Prüfplan überschreiben.
 * Der Auftragszähler wird bei jedem Start und jedem Abbruch erhöht.
 */
final class DiagnosisToken {
    private DiagnosisToken() { }

    static boolean matches(int acceptedGeneration, int currentGeneration,
                           long acceptedToken, long currentToken,
                           boolean active, boolean userDisconnect) {
        return acceptedToken > 0 && acceptedToken == currentToken
                && SessionEpoch.matches(acceptedGeneration, currentGeneration, active, userDisconnect);
    }
}
