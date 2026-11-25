package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;

/**
 * Regeln für angezeigte Live-Werte (Android-frei, damit testbar): ein Messwert, der nicht frisch
 * gelesen werden konnte (NO DATA, Parser-/Linkfehler, Zeitüberschreitung, Verbindungsverlust, zu alt), wird durch
 * {@link #INVALID} ersetzt und nie mit seinem letzten Wert weiter angezeigt. Entspricht dem auf
 * dem OpenNextion-Display validierten Verhalten („-- rpm“ nach Sitzungsverlust).
 */
public final class LiveValues {
    /** Anzeige für einen nicht (mehr) gültigen Messwert. */
    public static final String INVALID = "–";

    private LiveValues() { }

    /** Wert ungültig setzen; true, wenn sich die Anzeige dadurch ändert. */
    public static boolean markInvalid(Map<Integer, String> texts, int pid) {
        return !INVALID.equals(texts.put(pid, INVALID));
    }

    /** Alle bisher angezeigten Werte ungültig setzen; liefert die PIDs, deren Anzeige sich ändert. */
    public static List<Integer> markAllInvalid(Map<Integer, String> texts) {
        List<Integer> changed = new ArrayList<>();
        for (Integer pid : new ArrayList<>(texts.keySet())) {
            if (markInvalid(texts, pid)) changed.add(pid);
        }
        return changed;
    }

    /**
     * Höchstalter eines angezeigten Messwerts, der mindestens jede 3. Runde gelesen wird (Runde etwa 2 s; auch für
     * {@link LiveWarnings}). Langsame PIDs haben ein eigenes Höchstalter: {@link PollSchedule#maxAgeMs}.
     */
    public static final long MAX_AGE_MS = 15_000;

    /** PIDs, deren letzter frischer Wert älter als ihr Höchstalter ist (Zeit in ms, monoton). */
    public static List<Integer> expired(Map<Integer, Long> freshAt, long nowMs, IntToLongFunction maxAge) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : freshAt.entrySet()) {
            if (nowMs - e.getValue() > maxAge.applyAsLong(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    /** Messwert nur, wenn er nicht älter als sein Höchstalter ist; sonst null (Review: Prüfplan nahm alte Drehzahl). */
    public static Double fresh(Map<Integer, Double> values, Map<Integer, Long> freshAt, int pid, long nowMs,
            IntToLongFunction maxAge) {
        Double v = values.get(pid);
        Long at = freshAt.get(pid);
        return v == null || at == null || nowMs - at > maxAge.applyAsLong(pid) ? null : v;
    }

    public static boolean isValid(String text) {
        return text != null && !INVALID.equals(text);
    }
}
