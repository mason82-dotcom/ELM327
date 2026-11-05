package de.roman.mercedesobdmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CSV-Log der Livewerte: fortlaufend in den App-Speicher geschrieben ({@link CsvLogWriter}),
 * „CSV exportieren“ kopiert die aktuelle Datei nach Downloads/MercedesOBD2Monitor.
 */
public final class CsvLogger {
    /** Meldung für die Konsole (Datei begonnen, angehalten, Fehler). */
    public interface Notice {
        void post(String message);
    }

    static final long MAX_BYTES = 200L * 1024 * 1024;
    static final int KEEP_FILES = 20;
    static final long FLUSH_INTERVAL_MS = 2000;

    private final CsvLogWriter writer = new CsvLogWriter(MAX_BYTES, KEEP_FILES, FLUSH_INTERVAL_MS);
    private final Notice notice;
    /** Nur unter dem Objekt-Lock verwenden (SimpleDateFormat ist nicht thread-safe). */
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY);

    public CsvLogger(Notice notice) {
        this.notice = notice;
    }

    /** App-eigener Ordner für die Logs (ohne Speicherberechtigung). */
    public static File logDirectory(Context context) {
        File external = context.getExternalFilesDir("logs");
        return external != null ? external : new File(context.getFilesDir(), "logs");
    }

    public void setDirectory(File dir) {
        writer.setDirectory(dir);
    }

    public void record(long epochMs, ObdPid pid, double value, long latencyMs, String raw) {
        String ts;
        synchronized (this) {
            ts = timestampFormat.format(new Date(epochMs));
        }
        String row = CsvLogWriter.formatRow(ts, pid, value, latencyMs, raw);
        switch (writer.append(row, epochMs, SystemClock.elapsedRealtime())) {
            case STARTED -> notice.post("CSV-Log: " + writer.currentFile());
            case LIMIT_REACHED -> notice.post("CSV-Log hat " + (MAX_BYTES / (1024 * 1024))
                    + " MB erreicht – Aufzeichnung angehalten. „Neues Log“ beginnt eine neue Datei.");
            case FAILED -> notice.post("CSV-Log-Fehler: " + writer.lastError()
                    + " – Aufzeichnung angehalten. „Neues Log“ versucht es erneut.");
            default -> { }
        }
    }

    /** Puffer auf die Platte bringen (z. B. beim Trennen). */
    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            notice.post("CSV-Log konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    /** Aktuelle Datei abschließen; der nächste Messwert beginnt eine neue. Die alte bleibt erhalten. */
    public void startNew() {
        writer.close();
    }

    /**
     * Kopiert die aktuelle Datei bis zum zuletzt vollständig geschriebenen Stand nach
     * Downloads; die Aufzeichnung läuft dabei weiter. Gibt es seit dem App-Start noch
     * kein Log (z. B. nachdem Android die App beendet hat), wird die neueste vorhandene
     * Datei exportiert.
     * @return Speicherort zur Anzeige
     */
    public String export(Context context) throws IOException {
        CsvLogWriter.Snapshot snap = writer.flush();
        if (snap == null) snap = CsvLogWriter.newestLog(logDirectory(context));
        if (snap == null) throw new IOException("Noch keine Messwerte im Log");
        final String fileName = snap.file.getName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MercedesOBD2Monitor");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore konnte keine Datei anlegen");
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IOException("CSV-Ausgabestrom konnte nicht geöffnet werden");
                copy(snap, os);
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return Environment.DIRECTORY_DOWNLOADS + "/MercedesOBD2Monitor/" + fileName;
        }

        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MercedesOBD2Monitor");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Exportordner konnte nicht angelegt werden");
        File target = new File(dir, fileName);
        try (OutputStream os = new FileOutputStream(target)) {
            copy(snap, os);
        }
        return target.getAbsolutePath();
    }

    /** Kopiert genau snap.bytes Bytes – nur vollständige Zeilen, auch wenn parallel weitergeschrieben wird. */
    private static void copy(CsvLogWriter.Snapshot snap, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = snap.bytes;
        try (InputStream in = new FileInputStream(snap.file)) {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                os.write(buf, 0, n);
                left -= n;
            }
        }
    }
}
