package de.roman.mercedesobdmonitor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Schreibt Messzeilen fortlaufend in eine CSV-Datei (Android-unabhängig, unit-testbar).
 *
 * Eine Datei läuft vom ersten Messwert bis {@link #close()} („Neues Log“). Gepuffert
 * geschrieben und spätestens alle {@code flushIntervalMs} auf die Platte gebracht –
 * beendet Android den Prozess, fehlen höchstens die letzten Sekunden. Ältere Dateien
 * werden bis auf {@code keepFiles} gelöscht. Bei Größenlimit oder Schreibfehler hält
 * die Aufzeichnung an, bis ein neues Log begonnen wird. Thread-safe.
 */
public final class CsvLogWriter {
    public enum Result {
        /** Zeile geschrieben. */
        WRITTEN,
        /** Neue Datei begonnen und Zeile geschrieben. */
        STARTED,
        /** Größenlimit erreicht; Aufzeichnung angehalten. */
        LIMIT_REACHED,
        /** Schreibfehler; Aufzeichnung angehalten ({@link #lastError()}). */
        FAILED,
        /** Aufzeichnung ist angehalten; Zeile verworfen. */
        STOPPED
    }

    /** Stand einer Datei, deren Inhalt bis {@code bytes} vollständig auf der Platte ist. */
    public static final class Snapshot {
        public final File file;
        public final long bytes;

        Snapshot(File file, long bytes) {
            this.file = file;
            this.bytes = bytes;
        }
    }

    public static final String HEADER = "timestamp;pid;name;value;unit;latency_ms;raw";
    static final String PREFIX = "Mercedes_OBD2_Monitor_";
    static final String SUFFIX = ".csv";

    private final long maxBytes;
    private final int keepFiles;
    private final long flushIntervalMs;

    private File dir;
    private File file;
    private Writer out;
    private long bytes;
    private long rows;
    private long lastFlush;
    private boolean stopped;
    private String lastError;

    public CsvLogWriter(long maxBytes, int keepFiles, long flushIntervalMs) {
        this.maxBytes = maxBytes;
        this.keepFiles = keepFiles;
        this.flushIntervalMs = flushIntervalMs;
    }

    /** Zielordner für neue Dateien; wirkt ab der nächsten Datei. */
    public synchronized void setDirectory(File dir) {
        this.dir = dir;
    }

    /**
     * @param wallMs    Uhrzeit für den Dateinamen einer neuen Datei
     * @param elapsedMs monotone Zeit für das Flush-Intervall
     */
    public synchronized Result append(String row, long wallMs, long elapsedMs) {
        if (stopped) return Result.STOPPED;
        boolean started = false;
        try {
            if (out == null) {
                open(wallMs, elapsedMs);
                started = true;
            }
            byte[] line = (row + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes + line.length > maxBytes) {
                stopped = true;
                closeQuietly();
                return Result.LIMIT_REACHED;
            }
            out.write(row);
            out.write('\n');
            bytes += line.length;
            rows++;
            if (elapsedMs - lastFlush >= flushIntervalMs) {
                out.flush();
                lastFlush = elapsedMs;
            }
            return started ? Result.STARTED : Result.WRITTEN;
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            stopped = true;
            closeQuietly();
            return Result.FAILED;
        }
    }

    /** Bringt den Puffer auf die Platte; null, wenn noch keine Datei existiert. */
    public synchronized Snapshot flush() throws IOException {
        if (file == null) return null;
        if (out != null) out.flush();
        return new Snapshot(file, bytes);
    }

    /** Beendet die aktuelle Datei; der nächste Messwert beginnt eine neue (auch nach Limit/Fehler). */
    public synchronized void close() {
        closeQuietly();
        file = null;
        bytes = 0;
        rows = 0;
        stopped = false;
        lastError = null;
    }

    public synchronized File currentFile() {
        return file;
    }

    public synchronized long rows() {
        return rows;
    }

    public synchronized String lastError() {
        return lastError;
    }

    private void open(long wallMs, long elapsedMs) throws IOException {
        if (dir == null) throw new IOException("Kein Speicherort für das CSV-Log");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Ordner " + dir + " konnte nicht angelegt werden");
        }
        String base = PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(wallMs));
        File candidate = new File(dir, base + SUFFIX);
        for (int n = 2; candidate.exists(); n++) candidate = new File(dir, base + "_" + n + SUFFIX);

        Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(candidate), StandardCharsets.UTF_8), 16 * 1024);
        try {
            w.write(HEADER);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            try { w.close(); } catch (IOException ignored) { }
            throw e;
        }
        file = candidate;
        out = w;
        bytes = HEADER.length() + 1;
        rows = 0;
        lastFlush = elapsedMs;
        prune();
    }

    /** Löscht die ältesten Logs, sodass höchstens keepFiles übrig bleiben (Namen sind zeitlich sortierbar). */
    private void prune() {
        File[] logs = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (logs == null || logs.length <= keepFiles) return;
        Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < logs.length - keepFiles; i++) {
            if (!logs[i].equals(file) && !logs[i].delete()) break;
        }
    }

    private void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Eine CSV-Zeile im bisherigen Format; Texte in Anführungszeichen, ohne Zeilenumbrüche. */
    public static String formatRow(String timestamp, ObdPid pid, double value, long latencyMs, String raw) {
        return csv(timestamp) + ";" + String.format(Locale.US, "%02X", pid.pid) + ";" + csv(pid.label) + ";"
                + String.format(Locale.US, "%.6f", value) + ";" + csv(pid.unit) + ";" + latencyMs + ";" + csv(raw);
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
}
