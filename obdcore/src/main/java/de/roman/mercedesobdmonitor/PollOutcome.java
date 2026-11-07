package de.roman.mercedesobdmonitor;

/**
 * Ergebnis einer empfangenen Live-Antwort (Android-frei, testbar). Nur {@link #VALUE} liefert einen
 * anzeigbaren Messwert; jedes andere Ergebnis bedeutet „–“. Zeitüberschreitung und I/O-Fehler liefern
 * keine Antwort und laufen im Poll-Loop über eigene Zweige; dort sorgt die Freshness-Sperre für „–“.
 */
public enum PollOutcome {
    VALUE, NO_DATA, LINK_ERROR, PARSE_ERROR;

    public boolean isValue() {
        return this == VALUE;
    }

    /** Einstufung plus Messwert (nur bei {@link #VALUE} gesetzt). */
    public static final class Result {
        public final PollOutcome outcome;
        public final Double value;

        private Result(PollOutcome outcome, Double value) {
            this.outcome = outcome;
            this.value = value;
        }
    }

    /** Einzige Stelle der Einstufung: Busfehler vor NO DATA, dann der Parser des PIDs. */
    public static Result classify(ObdPid pid, String raw) {
        if (ObdParser.isLinkError(raw)) return new Result(LINK_ERROR, null);
        if (ObdParser.isNoData(raw)) return new Result(NO_DATA, null);
        Double v = pid.parse(raw);
        if (v == null || v.isNaN() || v.isInfinite()) return new Result(PARSE_ERROR, null);
        return new Result(VALUE, v);
    }
}
