package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ADR-005: Bluetooth-RFCOMM hat keinen Lese-Timeout. {@link TimedInputStream} liefert ihn; hier läuft der Client über
 * einen Transport, der – wie Bluetooth – nur diesen Weg kennt (kein setSoTimeout).
 */
public class TimedLinkTest {
    private LongSupplier savedClock;
    private ServerSocket server;
    private Thread thread;

    @Before
    public void clock() {
        savedClock = Elm327Client.clock;
        Elm327Client.clock = () -> System.nanoTime() / 1_000_000L;
    }

    @After
    public void restore() throws Exception {
        Elm327Client.clock = savedClock;
        if (server != null) server.close();
        if (thread != null) thread.join(2000);
    }

    // ------------------------------------------------------------------ TimedInputStream allein

    @Test
    public void passesDataAndTimesOut() throws Exception {
        PipedOutputStream w = new PipedOutputStream();
        TimedInputStream in = new TimedInputStream(new PipedInputStream(w, 4096), 16, "t");
        in.setTimeout(200);
        w.write("OK\r>".getBytes(StandardCharsets.US_ASCII));
        w.flush();
        byte[] b = new byte[10];
        int n = in.read(b, 0, 10);
        assertEquals("OK\r>", new String(b, 0, n, StandardCharsets.US_ASCII));
        long t0 = System.nanoTime();
        try {
            in.read();
            fail("Timeout erwartet");
        } catch (SocketTimeoutException expected) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("nach " + ms + " ms", ms >= 150 && ms < 1500);
        }
        w.close();
        in.setTimeout(1000);
        assertEquals(-1, in.read());       // Ende des Stroms
    }

    /** Mehr Daten als Puffer: nichts geht verloren, der Lesefaden wartet auf Platz. */
    @Test
    public void smallRingLosesNothing() throws Exception {
        PipedOutputStream w = new PipedOutputStream();
        TimedInputStream in = new TimedInputStream(new PipedInputStream(w, 8192), 8, "t");
        in.setTimeout(1000);
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < 300; i++) sent.append((char) ('A' + i % 26));
        Thread writer = new Thread(() -> {
            try {
                w.write(sent.toString().getBytes(StandardCharsets.US_ASCII));
                w.close();
            } catch (IOException ignored) { }
        });
        long t0 = System.nanoTime();
        writer.start();
        StringBuilder got = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) got.append((char) b);
        writer.join();
        assertEquals(sent.toString(), got.toString());
        // voller Puffer weckt den Leser (vorher wartete er je Füllung bis zu seiner Frist: 12 s statt ms)
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 2000);
    }

    @Test
    public void closeEndsWaitingReader() throws Exception {
        PipedOutputStream w = new PipedOutputStream();
        TimedInputStream in = new TimedInputStream(new PipedInputStream(w, 64), 16, "t");
        in.setTimeout(0);   // unbegrenzt
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(200);
                in.close();
            } catch (Exception ignored) { }
        });
        closer.start();
        assertEquals(-1, in.read());
        closer.join();
    }

    // ------------------------------------------------------------------ Client über einen „Bluetooth-artigen“ Weg

    /** Transport wie Bluetooth: Lese-Timeout nur über TimedInputStream. */
    private static ElmLink.Opener timedOpener(int port) {
        return new ElmLink.Opener() {
            @Override
            public ElmLink open(int timeoutMs) throws IOException {
                Socket s = new Socket("127.0.0.1", port);
                TimedInputStream in = new TimedInputStream(s.getInputStream(), 1024, "test-reader");
                return new ElmLink() {
                    @Override public InputStream input() { return in; }
                    @Override public OutputStream output() throws IOException { return s.getOutputStream(); }
                    @Override public void setReadTimeout(int ms) { in.setTimeout(ms); }
                    @Override public boolean isOpen() { return !s.isClosed(); }
                    @Override public String describe() { return "Test-Bluetooth"; }
                    @Override public void close() {
                        try { in.close(); } catch (IOException ignored) { }
                        try { s.close(); } catch (IOException ignored) { }
                    }
                };
            }

            @Override
            public String describe() {
                return "Test-Bluetooth";
            }
        };
    }

    interface Script {
        void handle(String cmd, OutputStream out, InputStream in) throws IOException;
    }

    private Elm327Client start(Script script) throws Exception {
        server = new ServerSocket(0);
        thread = new Thread(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder cmd = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    if (b == '\r') {
                        script.handle(cmd.toString(), out, in);
                        out.flush();
                        cmd.setLength(0);
                    } else {
                        cmd.append((char) b);
                    }
                }
            } catch (IOException ignored) {
                // Ende
            }
        });
        thread.start();
        Elm327Client c = new Elm327Client(timedOpener(server.getLocalPort()));
        c.connect(2000);
        return c;
    }

    private static void write(OutputStream out, String t) throws IOException {
        out.write(t.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void clientWorksOverTimedLink() throws Exception {
        Elm327Client c = start((cmd, out, in) -> {
            if (cmd.equals("010C")) write(out, "41 0C 1A F8\r\r>");
            else if (cmd.equals("0100")) {
                out.flush();
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                write(out, "41 00 BE 3F A8 13\r\r>");
            }
        });
        try {
            assertEquals("41 0C 1A F8", c.sendCommand("010C", 1000).raw);
            assertEquals("Test-Bluetooth", c.describe());
            try {
                c.sendCommand("0100", 100);
                fail("Timeout erwartet");
            } catch (SocketTimeoutException expected) {
                assertTrue(c.resync(1500));      // später Prompt wird abgeholt
            }
            assertEquals("41 0C 1A F8", c.sendCommand("010C", 1000).raw);
        } finally {
            c.close();
        }
    }

    @Test
    public void monitorStopTimeoutOverTimedLink() throws Exception {
        Elm327Client c = start((cmd, out, in) -> {
            if (!cmd.equals("ATMA")) return;
            write(out, "2F164002000\r0F330\r");
            out.flush();
            in.read();   // Stopp-Zeichen ignorieren
            try { Thread.sleep(Elm327Client.MONITOR_STOP_TIMEOUT_MS + 500); } catch (InterruptedException ignored) { }
        });
        try {
            Elm327Client.MonitorResult m = c.monitorPassive(200, 64 * 1024);
            assertTrue(m.stopTimedOut);
            assertEquals(2, CanMonitor.parse(m.raw).frames);
        } finally {
            c.close();
        }
    }
}
