package de.roman.mercedesobdmonitor;

/**
 * Sitzt zwischen asynchronen Diagnose-Aufträgen und der UI: ein Ergebnis gehört exakt
 * zu der Verbindung, in der der Auftrag angenommen wurde. Ein Reconnect innerhalb
 * derselben Sitzung bleibt zulässig, explizites Trennen/Neuverbinden dagegen nicht.
 */
final class SessionEpoch {
    private SessionEpoch() { }

    /** Ungültigmachen von Messwerten und Verbindungsstatus erfordert nur dieselbe Generation:
     * gerade nach Trennen (active=false) muss „Getrennt“ sichtbar bleiben. */
    static boolean matchesGeneration(int acceptedGeneration, int currentGeneration) {
        return acceptedGeneration == currentGeneration;
    }

    static boolean matches(int acceptedGeneration, int currentGeneration,
                           boolean active, boolean userDisconnect) {
        return matchesGeneration(acceptedGeneration, currentGeneration) && active && !userDisconnect;
    }
}
