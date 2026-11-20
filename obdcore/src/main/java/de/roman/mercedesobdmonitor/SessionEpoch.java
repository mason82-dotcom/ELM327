package de.roman.mercedesobdmonitor;

/**
 * Sitzt zwischen asynchronen Diagnose-Aufträgen und der UI: ein Ergebnis gehört exakt
 * zu der Verbindung, in der der Auftrag angenommen wurde. Ein Reconnect innerhalb
 * derselben Sitzung bleibt zulässig, explizites Trennen/Neuverbinden dagegen nicht.
 */
final class SessionEpoch {
    private SessionEpoch() { }

    static boolean matches(int acceptedGeneration, int currentGeneration,
                           boolean active, boolean userDisconnect) {
        return acceptedGeneration == currentGeneration && active && !userDisconnect;
    }
}
