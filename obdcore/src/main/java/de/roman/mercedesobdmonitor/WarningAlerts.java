package de.roman.mercedesobdmonitor;

/**
 * Wann eine Meldung aus {@link LiveWarnings} einen Ton auslöst (Android-frei, testbar). Nur Alarme tönen: beim
 * Auftreten und jede Minute erneut, bis „Ton aus“. Kurze Datenlücken (eine Abfrage ohne Antwort nimmt die Meldung
 * kurz weg) setzen weder „Ton aus“ zurück noch lösen sie beim Wiederkehren einen neuen Ton aus: beides gilt je
 * Ursache, bis sie {@link #FORGET_MS} lang nicht mehr gemeldet wurde oder sich ihre Stufe ändert.
 */
public final class WarningAlerts {
    public static final long REPEAT_MS = 60_000;
    public static final long FORGET_MS = 60_000;

    private String key;
    private LiveWarnings.Level level;
    private long lastSeen = Long.MIN_VALUE / 2;
    private long lastBeep = Long.MIN_VALUE / 2;
    private boolean silenced;
    private boolean beeped;

    /** Mit jeder Auswertung aufrufen; true = jetzt einen Ton ausgeben. */
    public boolean update(LiveWarnings.Warning w, long now) {
        if (w == null) {
            if (key != null && now - lastSeen > FORGET_MS) forget();
            return false;
        }
        if (!w.key.equals(key) || w.level != level || now - lastSeen > FORGET_MS) {
            forget();
            key = w.key;
            level = w.level;
        }
        lastSeen = now;
        if (w.level != LiveWarnings.Level.ALARM || silenced) return false;
        if (beeped && now - lastBeep < REPEAT_MS) return false;
        beeped = true;
        lastBeep = now;
        return true;
    }

    /** „Ton aus“ für die aktuelle Meldung; die Anzeige bleibt. */
    public void silence() {
        if (key != null) silenced = true;
    }

    /** true, wenn für diese Meldung ein „Ton aus“ angeboten werden soll. */
    public boolean canMute(LiveWarnings.Warning w) {
        return w != null && w.level == LiveWarnings.Level.ALARM
                && !(silenced && w.key.equals(key) && w.level == level);
    }

    public void reset() {
        forget();
    }

    private void forget() {
        key = null;
        level = null;
        silenced = false;
        beeped = false;
    }
}
