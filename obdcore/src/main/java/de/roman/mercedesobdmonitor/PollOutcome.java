package de.roman.mercedesobdmonitor;

/**
 * Ergebnis einer einzelnen Live-Abfrage (Android-frei, testbar). Nur {@link #VALUE} liefert einen
 * anzeigbaren Messwert; jedes andere Ergebnis bedeutet „–“ – der letzte Wert darf nicht stehen bleiben.
 */
public enum PollOutcome {
    VALUE, NO_DATA, LINK_ERROR, PARSE_ERROR, TIMEOUT, IO_ERROR;

    public boolean isValue() {
        return this == VALUE;
    }

    /**
     * Einstufung einer empfangenen Antwort. Reihenfolge wie im Poll-Loop: Busfehler vor NO DATA,
     * dann der Parser ({@code parsed} == null heißt: nicht auswertbar).
     */
    public static PollOutcome of(String raw, Double parsed) {
        if (ObdParser.isLinkError(raw)) return LINK_ERROR;
        if (ObdParser.isNoData(raw)) return NO_DATA;
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) return PARSE_ERROR;
        return VALUE;
    }
}
