package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Regeln für angezeigte Live-Werte (Android-frei, damit testbar): ein Messwert, der nicht frisch
 * gelesen werden konnte (NO DATA, Parser-/Linkfehler, Verbindungsverlust), wird durch
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

    public static boolean isValid(String text) {
        return text != null && !INVALID.equals(text);
    }
}
