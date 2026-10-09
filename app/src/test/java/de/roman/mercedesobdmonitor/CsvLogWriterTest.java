package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Fortlaufendes CSV-Log: Datei, Flush, Limit, Rotation, Neues Log, Fehler. */
public class CsvLogWriterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long WALL = 1791581400000L;

    private static List<String> lines(File f) throws IOException {
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }

    private CsvLogWriter writer(File dir, long maxBytes, int keep) {
        CsvLogWriter w = new CsvLogWriter(maxBytes, keep, 2000);
        w.setDirectory(dir);
        return w;
    }

    @Test public void firstRowStartsFileWithHeader() throws IOException {
        File dir = tmp.newFolder();
        CsvLogWriter w = writer(dir, 1_000_000, 20);
        assertNull(w.flush());
        assertEquals(CsvLogWriter.Result.STARTED, w.append("a", WALL, 0));
        assertEquals(CsvLogWriter.Result.WRITTEN, w.append("b", WALL, 10));
        File f = w.currentFile();
        assertTrue(f.getName().startsWith("Mercedes_OBD2_Monitor_"));
        assertTrue(f.getName().endsWith(".csv"));
        w.flush();
        assertEquals(Arrays.asList(CsvLogWriter.HEADER, "a", "b"), lines(f));
        assertEquals(2, w.rows());
    }

    @Test public void bufferIsFlushedAfterInterval() throws IOException {
        CsvLogWriter w = writer(tmp.newFolder(), 1_000_000, 20);
        w.append("a", WALL, 0);
        w.append("b", WALL, 500);
        // Noch im Puffer: nur die beim Öffnen geschriebene Kopfzeile ist auf der Platte.
        assertEquals(Arrays.asList(CsvLogWriter.HEADER), lines(w.currentFile()));
        w.append("c", WALL, 2000);
        assertEquals(Arrays.asList(CsvLogWriter.HEADER, "a", "b", "c"), lines(w.currentFile()));
    }

    @Test public void snapshotCoversOnlyCompleteRows() throws IOException {
        CsvLogWriter w = writer(tmp.newFolder(), 1_000_000, 20);
        w.append("äöü", WALL, 0);
        CsvLogWriter.Snapshot s = w.flush();
        assertNotNull(s);
        assertEquals(s.file.length(), s.bytes);
        w.append("weiter", WALL, 1);
        assertEquals(CsvLogWriter.HEADER.length() + 1 + "äöü\n".getBytes(StandardCharsets.UTF_8).length, s.bytes);
    }

    @Test public void limitStopsRecordingUntilNewLog() throws IOException {
        int header = CsvLogWriter.HEADER.length() + 1;
        CsvLogWriter w = writer(tmp.newFolder(), header + 4, 20);
        assertEquals(CsvLogWriter.Result.STARTED, w.append("abc", WALL, 0));   // 4 Byte: passt genau
        assertEquals(CsvLogWriter.Result.LIMIT_REACHED, w.append("x", WALL, 1));
        assertEquals(CsvLogWriter.Result.STOPPED, w.append("y", WALL, 2));
        File full = w.currentFile();
        assertEquals(Arrays.asList(CsvLogWriter.HEADER, "abc"), lines(full));
        assertNotNull(w.flush()); // Export der vollen Datei bleibt möglich

        w.close();
        assertEquals(CsvLogWriter.Result.STARTED, w.append("neu", WALL + 1000, 3));
        assertNotEquals(full, w.currentFile());
    }

    @Test public void newLogKeepsOldFileAndAvoidsNameClash() throws IOException {
        File dir = tmp.newFolder();
        CsvLogWriter w = writer(dir, 1_000_000, 20);
        w.append("eins", WALL, 0);
        File first = w.currentFile();
        w.close();
        assertNull(w.currentFile());
        w.append("zwei", WALL, 1); // gleiche Sekunde → eigener Name
        File second = w.currentFile();
        assertNotEquals(first, second);
        w.flush();
        assertEquals(Arrays.asList(CsvLogWriter.HEADER, "eins"), lines(first));
        assertEquals(Arrays.asList(CsvLogWriter.HEADER, "zwei"), lines(second));
    }

    @Test public void oldestLogsArePruned() throws IOException {
        File dir = tmp.newFolder();
        CsvLogWriter w = writer(dir, 1_000_000, 3);
        for (int i = 0; i < 5; i++) {
            w.append("r" + i, WALL + i * 1000L, i);
            w.close();
        }
        String[] names = dir.list();
        assertNotNull(names);
        Arrays.sort(names);
        assertEquals(3, names.length);
        // Die beiden ältesten (i = 0, 1) sind gelöscht; Dateiname nach lokaler Uhrzeit wie im Writer.
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        assertEquals("Mercedes_OBD2_Monitor_" + f.format(new Date(WALL + 2000)) + ".csv", names[0]);
        assertEquals("Mercedes_OBD2_Monitor_" + f.format(new Date(WALL + 4000)) + ".csv", names[2]);
    }

    @Test public void unwritableDirectoryFailsOnceThenStops() throws IOException {
        File notADir = tmp.newFile();
        CsvLogWriter w = writer(new File(notADir, "logs"), 1_000_000, 20);
        assertEquals(CsvLogWriter.Result.FAILED, w.append("a", WALL, 0));
        assertNotNull(w.lastError());
        assertEquals(CsvLogWriter.Result.STOPPED, w.append("b", WALL, 1));
        assertNull(w.currentFile());
    }

    @Test public void noDirectoryFails() {
        CsvLogWriter w = new CsvLogWriter(1_000_000, 20, 2000);
        assertEquals(CsvLogWriter.Result.FAILED, w.append("a", WALL, 0));
        assertFalse(w.lastError().isEmpty());
    }

    @Test public void rowFormatUnchanged() {
        ObdPid rpm = ObdPid.defaultPids().get(0);
        assertEquals("\"2026-10-09 21:30:00.000\";0C;\"Motordrehzahl\";750.250000;\"U/min\";42;\"41 0C 0B B9\"",
                CsvLogWriter.formatRow("2026-10-09 21:30:00.000", rpm, 750.25, 42, "41 0C 0B B9"));
        assertEquals("\"a\"\"b c\"", CsvLogWriter.formatRow("x", rpm, 0, 0, "a\"b\nc").split(";")[6]);
    }
}
