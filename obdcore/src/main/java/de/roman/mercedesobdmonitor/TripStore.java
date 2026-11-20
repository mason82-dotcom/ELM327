package de.roman.mercedesobdmonitor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gespeichertes Fahrtenbuch einer App. Quelle sind die CSV-Logs, die der Dienst durchgehend schreibt (auch ohne
 * sichtbare Oberfläche), plus importierte Logs. Eingelesen werden nur Logdateien, deren Größe sich seit dem letzten Mal
 * geändert hat, und davon nur der neue Teil ab der gemerkten Position ({@link LogReplay#readFrom}: Anfang der damals
 * offenen Fahrt) – das aktive Log (bis 200 MB) wird nicht bei jedem Öffnen ganz gelesen. Überlappende Teilstücke
 * ersetzen sich ({@link TripBook#merge}). Gelöschte Logs (die App behält 20) nehmen ihre Fahrten nicht mit.
 */
public final class TripStore {
    public static final String KEY = "trips";
    private static final String STAMPS = "trip_log_stamps";
    private static final Object LOCK = new Object();
    /** Rückrufe, die auf den laufenden Einlesevorgang warten; null = keiner läuft (nur unter SCAN). */
    private static List<Runnable> waiting;
    private static final Object SCAN = new Object();

    private TripStore() { }

    public static List<TripComputer.Trip> load(Context c, String prefsName) {
        return TripComputer.parse(prefs(c, prefsName).getString(KEY, null));
    }

    /** Fahrten zusammenführen und speichern (Lesen-Ändern-Schreiben unter Sperre). */
    public static void merge(Context c, String prefsName, List<TripComputer.Trip> fresh) {
        if (fresh.isEmpty()) return;
        synchronized (LOCK) {
            List<TripComputer.Trip> merged = TripBook.merge(load(c, prefsName), fresh);
            prefs(c, prefsName).edit().putString(KEY, TripComputer.serialize(merged)).apply();
        }
    }

    /**
     * Eigene Logs im Hintergrund einlesen; {@code done} läuft danach auf dem UI-Thread. Läuft schon ein Einlesevorgang,
     * wartet der Aufruf auf dessen Ergebnis statt einen zweiten zu starten (das aktive Log wächst ständig und würde sonst
     * bei jedem Öffnen parallel komplett gelesen – Review). Der Thread hält nur den Anwendungskontext.
     */
    public static void refreshAsync(Context context, String prefsName, Runnable done) {
        synchronized (SCAN) {
            if (waiting != null) {
                waiting.add(done);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(done);
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                refreshFromLogs(app, prefsName, CsvLogger.logDirectory(app));
            } finally {
                List<Runnable> callbacks;
                synchronized (SCAN) {
                    callbacks = waiting;
                    waiting = null;
                }
                main.post(() -> { for (Runnable r : callbacks) r.run(); });
            }
        }, "trip-log-scan").start();
    }

    static void refreshFromLogs(Context c, String prefsName, File dir) {
        File[] logs = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(".csv"));
        if (logs == null) return;
        Map<String, long[]> stamps = parseStamps(prefs(c, prefsName).getString(STAMPS, ""));
        Map<String, long[]> next = new HashMap<>();
        List<TripComputer.Trip> fresh = new ArrayList<>();
        for (File f : logs) {
            long len = f.length();
            long[] seen = stamps.get(f.getName());
            if (seen != null && seen[0] == len) {
                next.put(f.getName(), seen);
                continue;
            }
            // geschrumpft (ersetzt) oder neu: von vorn
            long from = seen != null && len > seen[0] ? seen[1] : 0;
            try (FileInputStream in = new FileInputStream(f)) {
                LogReplay.Result r = LogReplay.readFrom(in, ZoneId.systemDefault(), from);
                fresh.addAll(r.drives);
                next.put(f.getName(), new long[] {len, r.resumeOffset});
            } catch (IOException | RuntimeException e) {
                // beim nächsten Mal erneut versuchen (Eintrag fehlt)
            }
        }
        merge(c, prefsName, fresh);
        prefs(c, prefsName).edit().putString(STAMPS, formatStamps(next)).apply();
    }

    /** Je Zeile „Name=Größe;Position“; ältere Zeilen ohne Position lesen von vorn (Position 0). */
    static Map<String, long[]> parseStamps(String text) {
        Map<String, long[]> out = new HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String[] v = line.substring(eq + 1).split(";", -1);
            try {
                long size = Long.parseLong(v[0]);
                long pos = v.length > 1 ? Long.parseLong(v[1]) : 0;
                out.put(line.substring(0, eq), new long[] {size, Math.max(0, Math.min(pos, size))});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static String formatStamps(Map<String, long[]> stamps) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> e : stamps.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()[0]).append(';').append(e.getValue()[1]).append('\n');
        }
        return sb.toString();
    }

    /** Teilen-Dialog mit dem Fahrtenbuch als CSV-Text (z. B. in Mail, Notizen, Drive). */
    public static Intent shareIntent(Context c, String prefsName) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Fahrtenbuch C350 (OBD)")
                .putExtra(Intent.EXTRA_TEXT, TripBook.csv(load(c, prefsName), ZoneId.systemDefault()));
        return Intent.createChooser(send, "Fahrtenbuch teilen");
    }

    private static SharedPreferences prefs(Context c, String name) {
        return c.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
