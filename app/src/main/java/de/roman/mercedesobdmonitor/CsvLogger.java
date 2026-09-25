package de.roman.mercedesobdmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CsvLogger {
    private final List<String> rows = new ArrayList<>();
    private static final int MAX_ROWS = 100_000;

    public CsvLogger() {
        clear();
    }

    public synchronized void clear() {
        rows.clear();
        rows.add("timestamp;pid;name;value;unit;latency_ms;raw");
    }

    public synchronized void record(long epochMs, ObdPid pid, double value, long latencyMs, String raw) {
        if (rows.size() >= MAX_ROWS) return;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(new Date(epochMs));
        rows.add(csv(ts) + ";" + String.format(Locale.US, "%02X", pid.pid) + ";" + csv(pid.label) + ";"
                + String.format(Locale.US, "%.6f", value) + ";" + csv(pid.unit) + ";" + latencyMs + ";" + csv(raw));
    }

    public synchronized int sampleCount() {
        return Math.max(0, rows.size() - 1);
    }

    public Uri export(Context context) throws IOException {
        final String fileName = "Mercedes_OBD2_Monitor_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        byte[] bytes;
        synchronized (this) {
            StringBuilder sb = new StringBuilder(rows.size() * 80);
            for (String row : rows) sb.append(row).append('\n');
            bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        }

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
                os.write(bytes);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }

        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MercedesOBD2Monitor");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Exportordner konnte nicht angelegt werden");
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(bytes); }
        return Uri.fromFile(file);
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
}
