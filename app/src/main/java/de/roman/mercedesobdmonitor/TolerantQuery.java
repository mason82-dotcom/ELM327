package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Einzelne Diagnoseabfragen fehlertolerant (Android-unabhängig, unit-testbar).
 *
 * Ein Timeout wird per Prompt-Resync aufgefangen und die Abfrage als „nicht verfügbar“
 * (null) gemeldet. Nur wenn der Adapter nicht mehr synchron ist (Resync fehlgeschlagen
 * oder zu viele Timeouts in Folge) oder die Verbindung abbricht, wird eine Exception
 * geworfen – dann ist ein Reconnect nötig.
 */
final class TolerantQuery {
    /** Ab so vielen resynchronisierten Timeouts in Folge wird abgebrochen (Adapter/Bus gestört). */
    static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    private final InspectionReader.Transport transport;
    private int consecutiveTimeouts;
    private int timeoutCount;

    TolerantQuery(InspectionReader.Transport transport) {
        this.transport = transport;
    }

    int timeoutCount() {
        return timeoutCount;
    }

    /**
     * @return Rohantwort oder null bei überbrücktem Timeout
     * @throws IOException wenn der Adapter nicht mehr synchron ist oder die Verbindung weg ist
     */
    String query(String cmd, int timeoutMs) throws IOException {
        try {
            String raw = transport.send(cmd, timeoutMs);
            consecutiveTimeouts = 0;
            return raw;
        } catch (SocketTimeoutException e) {
            timeoutCount++;
            consecutiveTimeouts++;
            boolean resynced = transport.resync();
            if (!resynced || consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                throw new SocketTimeoutException("Timeout bei " + cmd
                        + (resynced ? " (wiederholt)" : " – Adapter nicht mehr synchron"));
            }
            return null;
        }
    }
}
