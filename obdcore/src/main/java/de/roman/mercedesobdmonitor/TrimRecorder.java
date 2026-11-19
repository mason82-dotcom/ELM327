package de.roman.mercedesobdmonitor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.function.Consumer;

/**
 * Gespeicherter {@link TrimHistory Trim-Verlauf} einer App: je Verbindung ein Eintrag, alle 30 s und beim Verlassen
 * gesichert, dazu der Import von CSV-Logs. Gespeichert wird immer per Lesen-Ändern-Schreiben unter einer Sperre, damit
 * Live-Aufzeichnung, Import-Thread und eine neu erzeugte Activity sich nicht gegenseitig überschreiben (Review #17).
 * Jede App hat ihre eigene Einstellungsdatei; die beiden Apps teilen den Verlauf nicht.
 */
public final class TrimRecorder {
    /** Höchstens so oft wird während der Fahrt gesichert. */
    public static final long SAVE_INTERVAL_MS = 30_000;
    public static final String KEY = "trim_history";
    private static final Object LOCK = new Object();

    private final Context app;
    private final String prefsName;
    private long sessionStart;
    private long savedAt;

    public TrimRecorder(Context context, String prefsName) {
        this.app = context.getApplicationContext();
        this.prefsName = prefsName;
    }

    /**
     * Gespeicherter Stand, immer frisch gelesen (SharedPreferences liegt im Speicher, höchstens 30 Zeilen): so sieht
     * auch eine neu erzeugte Activity einen Import, der noch unter der alten begonnen wurde (Review).
     */
    public TrimHistory history() {
        return load(app, prefsName);
    }

    /**
     * Neue Verbindung: die laufende Karte wird noch als Eintrag der alten Verbindung gesichert, danach setzt der
     * Aufrufer die Karte zurück ({@link LiveAnalysis#resetConnection()}) – siehe {@link #beginConnection}.
     */
    public void endConnection(EngineAnalysis.TrimMap map) {
        save(map, true);
    }

    /** Startzeitpunkt der neuen Verbindung (Schlüssel ihres Eintrags). */
    public void beginConnection() {
        sessionStart = System.currentTimeMillis();
        savedAt = 0;
    }

    /**
     * Abbild der Karte sichern; ohne {@code force} höchstens alle {@link #SAVE_INTERVAL_MS}. Wurde die Activity neu
     * erzeugt, während der Dienst verbunden blieb, gelten die Werte ab jetzt als eigene Verbindung (eigener Start,
     * überschreibt nie den Eintrag einer anderen).
     */
    public void save(EngineAnalysis.TrimMap map, boolean force) {
        if (sessionStart == 0) sessionStart = System.currentTimeMillis();
        long now = SystemClock.elapsedRealtime();
        if (!force && savedAt != 0 && now - savedAt < SAVE_INTERVAL_MS) return;
        TrimHistory.Entry entry = TrimHistory.snapshot(sessionStart, System.currentTimeMillis(), map);
        if (entry == null) return;
        savedAt = now;
        synchronized (LOCK) {
            TrimHistory h = load(app, prefsName);
            h.upsert(entry);
            store(app, prefsName, h);
        }
    }

    // ------------------------------------------------------------------ Import

    /** Systemdialog zum Öffnen eines CSV-Logs (nur Lesen). */
    public static Intent pickIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/csv", "text/comma-separated-values", "text/plain"});
    }

    /**
     * Log im Hintergrund auswerten und in den gespeicherten Verlauf eintragen; {@code done} erhält auf dem UI-Thread
     * die Rückmeldung. Der Thread hält nur den Anwendungskontext – keine Activity, die inzwischen ersetzt sein kann.
     */
    public void importAsync(Uri uri, Consumer<String> done) {
        Context ctx = app;
        String prefs = prefsName;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Datei lässt sich nicht öffnen");
                LogReplay.Result result = LogReplay.read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)),
                        ZoneId.systemDefault());
                String applied;
                synchronized (LOCK) {
                    TrimHistory h = load(ctx, prefs);
                    applied = result.applyTo(h);
                    store(ctx, prefs, h);
                }
                message = "Log ausgewertet (" + result.summary() + "): " + applied;
            } catch (IOException | RuntimeException e) {
                message = "Log nicht übernommen: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + ". Bitte ein CSV der Monitor-App wählen.";
            }
            final String text = message;
            main.post(() -> done.accept(text));
        }, "trim-log-import").start();
    }

    private static TrimHistory load(Context c, String prefsName) {
        return TrimHistory.parse(c.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(KEY, null));
    }

    private static void store(Context c, String prefsName, TrimHistory h) {
        // apply ändert den Speicherstand im Prozess sofort; das Schreiben auf die Platte folgt im Hintergrund
        SharedPreferences.Editor e = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit();
        e.putString(KEY, h.serialize()).apply();
    }
}
