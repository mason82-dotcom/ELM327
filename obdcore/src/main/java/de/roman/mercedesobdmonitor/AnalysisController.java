package de.roman.mercedesobdmonitor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import java.time.ZoneId;

/**
 * Analyse-Anbindung für beide Apps: {@link LiveAnalysis} (Werte, Trim-Karte, Kat-Tendenz) und {@link TrimRecorder}
 * (gespeicherter Verlauf, Import) samt Verbindungswechsel und gedrosselter Aktualisierung. Vorher lag das doppelt in
 * Dashboard- und Monitor-Activity, mit einer Aufrufreihenfolge, die beide einhalten mussten (Review).
 */
public final class AnalysisController {
    public final LiveAnalysis analysis = new LiveAnalysis();
    private final TrimRecorder recorder;
    private final Context app;
    private final String prefsName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long refreshMs;
    private Runnable listener;
    private boolean pending;

    /**
     * @param prefsName Einstellungsdatei der App (Verlauf je App getrennt)
     * @param refreshMs höchstens so oft wird der Bericht neu angefordert
     */
    public AnalysisController(Context context, String prefsName, long refreshMs) {
        this.app = context.getApplicationContext();
        this.recorder = new TrimRecorder(app, prefsName);
        this.prefsName = prefsName;
        this.refreshMs = refreshMs;
    }

    /** Wird (gedrosselt, UI-Thread) aufgerufen, wenn sich der Bericht geändert haben kann; null in onStop. */
    public void setListener(Runnable listener) {
        this.listener = listener;
        pending = false;
    }

    public void onSample(int pid, double value) {
        if (analysis.onSample(pid, value)) recorder.save(analysis.trimMap, false);
        requestRefresh();
    }

    public void onInvalid(int pid) {
        analysis.onInvalid(pid);
        requestRefresh();
    }

    /** Vor dem Verbinden: alte Verbindung sichern, Karte und Kat-Tendenz neu, neuer Verlaufseintrag. */
    /** Kennung der Sitzung, deren Werte die Karte gerade sammelt; -1 = noch keine. */
    private int connectionId = -1;

    /**
     * Hat der Dienst ohne diese Oberfläche neu verbunden (z. B. nach dem Radiostart, ADR-006), beginnt hier eine neue
     * Verbindung – sonst landeten ihre Werte im Eintrag der vorigen (Review).
     */
    public void syncConnection(int sessionId, boolean active) {
        if (active && sessionId != connectionId) {
            if (connectionId != -1) newConnection();
            connectionId = sessionId;
        }
    }

    public void newConnection() {
        recorder.endConnection(analysis.trimMap);
        analysis.resetConnection();
        recorder.beginConnection();
    }

    /** Trennen: laufende Karte sofort sichern (nicht erst beim nächsten Verbinden oder onStop). */
    public void connectionEnded() {
        recorder.save(analysis.trimMap, true);
    }

    /** Oberfläche nicht mehr angemeldet: keine veralteten Werte auswerten, Karte sichern. */
    public void onStop() {
        setListener(null);
        analysis.clearSamples();
        recorder.save(analysis.trimMap, true);
    }

    public String report() {
        return analysis.report(recorder.history(), ZoneId.systemDefault());
    }

    /**
     * Bericht in eine (markierbare) Textansicht schreiben – nicht, solange dort Text markiert ist: sonst verschwände
     * die Markierung im Sekundentakt und der Bericht ließe sich während der Fahrt nicht kopieren (Review).
     */
    public void showIn(TextView view) {
        if (view == null || view.hasSelection()) return;
        view.setText(report());
    }

    // ------------------------------------------------------------------ Fahrtenbuch

    /** Fahrtenbuch-Bericht (gespeicherter Stand; vorher ggf. {@link #refreshTrips}). */
    public String tripReport(int maxRows) {
        return TripBook.report(TripStore.load(app, prefsName), ZoneId.systemDefault(), System.currentTimeMillis(), maxRows);
    }

    /** So viele Fahrten zeigen beide Apps. */
    public static final int TRIP_ROWS = 8;

    /** Eigene CSV-Logs im Hintergrund nachlesen, danach {@code done} auf dem UI-Thread. */
    public void refreshTrips(Runnable done) {
        TripStore.refreshAsync(app, prefsName, done);
    }

    /** Gespeicherten Stand zeigen, Logs im Hintergrund nachlesen, danach aktualisieren (solange {@code owner} lebt). */
    public void refreshTripsInto(TextView view, Activity owner) {
        if (view == null) return;
        view.setText(tripReport(TRIP_ROWS) + "\n\nLese Logs …");
        refreshTrips(() -> { if (!owner.isFinishing() && !owner.isDestroyed()) showTripsIn(view); });
    }

    /** Gespeicherten Fahrtenbuch-Stand in {@code view} schreiben. */
    public void showTripsIn(TextView view) {
        if (view != null) view.setText(tripReport(TRIP_ROWS));
    }

    /** Fahrtenbuch als CSV teilen. */
    public void shareTrips(Activity activity) {
        try {
            activity.startActivity(TripStore.shareIntent(app, prefsName));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "Keine App zum Teilen verfügbar", Toast.LENGTH_LONG).show();
        }
    }

    /** Dateiauswahl für ein CSV-Log öffnen. */
    public void pickLog(Activity activity, int requestCode) {
        try {
            activity.startActivityForResult(TrimRecorder.pickIntent(), requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "Keine Dateiauswahl auf diesem Gerät verfügbar", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Ergebnis der Dateiauswahl; true, wenn es dieser Anfrage gehörte. {@code afterImport} läuft nach dem Import auf
     * dem UI-Thread (z. B. Fahrtenbuch neu anzeigen), darf null sein.
     */
    public boolean onActivityResult(int requestCode, int expected, int resultCode, Intent data, Runnable afterImport) {
        if (requestCode != expected) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Toast.makeText(app, "Log wird ausgewertet …", Toast.LENGTH_SHORT).show();
        recorder.importAsync(data.getData(), text -> {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            if (listener != null) listener.run();
            if (afterImport != null) afterImport.run();
        });
        return true;
    }

    private void requestRefresh() {
        if (listener == null || pending) return;
        pending = true;
        main.postDelayed(() -> {
            pending = false;
            if (listener != null) listener.run();
        }, refreshMs);
    }
}
