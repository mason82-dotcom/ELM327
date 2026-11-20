package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Elm327Client gegen einen kleinen ELM327-Nachbau auf einem lokalen Socket (Tech-Debt-Audit 20.11.2026: der I/O-Kern
 * hatte keinen Test). Das Skript des Nachbaus bekommt jeden empfangenen Befehl und schreibt die Antwort selbst.
 */
public class Elm327ClientTest {
    interface Script {
        void handle(String command, OutputStream out, InputStream in) throws IOException;
    }

    private ServerSocket server;
    private Thread thread;
    private Elm327Client client;
    private final AtomicReference<Throwable> serverError = new AtomicReference<>();

    private java.util.function.LongSupplier savedClock;

    @Before
    public void clock() {
        savedClock = Elm327Client.clock;
        Elm327Client.clock = () -> System.nanoTime() / 1_000_000L;
    }

    @After
    public void close() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close();
        if (thread != null) thread.join(2000);
        Elm327Client.clock = savedClock;
    }

    private void start(Script script) throws Exception {
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
            } catch (IOException e) {
                // Ende der Verbindung
            } catch (Throwable t) {
                serverError.set(t);
            }
        });
        thread.start();
        client = new Elm327Client("127.0.0.1", server.getLocalPort(), null);
        client.connect(2000);
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void readsUntilPromptAndCountsBytes() throws Exception {
        start((cmd, out, in) -> write(out, cmd.equals("010C") ? "41 0C 1A F8\r\r>" : "?\r\r>"));
        Elm327Client.CommandResult r = client.sendCommand("010c", 1000);
        assertEquals("41 0C 1A F8", r.raw);
        assertEquals(5, client.getTxBytes());          // "010C\r"
        assertEquals(14, client.getRxBytes());   // "41 0C 1A F8\r\r>"
    }

    @Test
    public void blocksNonWhitelistedCommandsBeforeSending() throws Exception {
        start((cmd, out, in) -> {
            throw new AssertionError("darf nichts empfangen: " + cmd);
        });
        for (String cmd : new String[] {"04", "2E0101", "ATMA", "22F190", "1A86"}) {
            try {
                client.sendCommand(cmd, 500);
                fail(cmd);
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Read-Only-Schutz"));
            }
        }
        assertEquals(0, client.getTxBytes());
    }

    /** Exakter Service-Pfad erlaubt EINEN 04, allgemeiner Parser/Terminal bleiben gesperrt. */
    @Test
    public void mode04TicketWorksExactlyOnceOutsideReadOnlyTerminal() throws Exception {
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        start((cmd, out, in) -> {
            if (!"04".equals(cmd)) throw new AssertionError("unerlaubter Befehl: " + cmd);
            received.incrementAndGet();
            write(out, "44\r\r>");
        });
        try {
            client.sendCommand("04", 1000);
            fail("Allgemeiner Pfad muss Mode 04 sperren");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("Read-Only-Schutz"));
        }
        ServiceMode04Gate.Preflight p = new ServiceMode04Gate.Preflight(
                true, true, true, true, true,
                0.0, 900L, 0.0, 900L, 12.4, 900L, 1000L);
        ServiceMode04Gate.Ticket ticket = ServiceMode04Gate.issue(p);
        Elm327Client.CommandResult result = client.clearEmissionsOnce(ticket, 1000);
        assertTrue(ServiceMode04Gate.positiveAck(result.raw));
        assertEquals(1, received.get());
        try {
            client.clearEmissionsOnce(ticket, 1000);
            fail("Einmalfreigabe darf niemals ein zweites Mal senden");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Einmalfreigabe"));
        }
        assertEquals(1, received.get());
        assertEquals(3, client.getTxBytes()); // genau ein '04' + CR
    }

    /** Unter fremdem/unklarem Header darf auch ein gültiges Service-Ticket nichts senden. */
    @Test
    public void serviceTicketDeniedOutsideFunctionalDefaultHeader() throws Exception {
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        start((cmd, out, in) -> {
            received.incrementAndGet();
            write(out, "OK\r\r>");
        });
        client.sendCommand("ATSH7E1", 1000);
        ServiceMode04Gate.Ticket ticket = ServiceMode04Gate.issue(
                new ServiceMode04Gate.Preflight(true, true, true, true, true,
                        0.0, 900L, 0.0, 900L, 12.4, 900L, 1000L));
        try {
            client.clearEmissionsOnce(ticket, 1000);
            fail("Header 7E1 ist nicht zulässig");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Header 7DF"));
        }
        assertEquals(1, received.get()); // nur erlaubter ATSH-Befehl
        assertFalse(CommandSafety.isAllowed("04"));
    }

    /** Header gilt erst nach bestätigter Antwort; nach einem Timeout ist nur der Rückweg frei (ADR-001). */
    @Test
    public void headerFollowsOnlyConfirmedCommands() throws Exception {
        start((cmd, out, in) -> {
            if (cmd.equals("ATSH7E1")) write(out, "OK\r\r>");
            else if (cmd.equals("2130")) write(out, "61 30 00\r\r>");
            else if (cmd.equals("ATSH7DF")) {
                // keine Antwort: Timeout beim Rückweg
            } else write(out, "?\r\r>");
        });
        client.sendCommand("ATSH7E1", 1000);
        assertEquals("7E1", client.header());
        assertEquals("61 30 00", client.sendCommand("2130", 1000).raw);
        try {
            client.sendCommand("ATSH7DF", 200);
            fail("Timeout erwartet");
        } catch (SocketTimeoutException expected) {
            assertEquals(CommandSafety.UNKNOWN_HEADER, client.header());
        }
        try {
            client.sendCommand("010C", 200);   // bei unbekanntem Header keine normale Abfrage
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Header"));
        }
    }

    @Test
    public void rejectedHeaderKeepsEcuSpecificRequestsBlocked() throws Exception {
        start((cmd, out, in) -> {
            if (cmd.equals("ATSH7E1")) write(out, "?\r\r>");
            else if (cmd.equals("ATSH7DF")) write(out, "OK\r\r>");
            else if (cmd.equals("010C")) write(out, "41 0C 1A F8\r\r>");
            else write(out, "?\r\r>");
        });
        try {
            client.sendCommand("ATSH7E1", 1000);
            fail("ATSH ohne OK darf nicht erfolgreich sein");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("nicht bestätigt"));
        }
        assertEquals(CommandSafety.UNKNOWN_HEADER, client.header());
        try {
            client.sendCommand("2130", 1000);
            fail("Herstelleranfrage muss unter unbekanntem Header gesperrt bleiben");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Read-Only-Schutz"));
        }
        client.sendCommand("ATSH7DF", 1000);
        assertEquals("7DF", client.header());
        assertEquals("41 0C 1A F8", client.sendCommand("010C", 1000).raw);
        assertEquals(21, client.getTxBytes()); // ATSH7E1 (8), ATSH7DF (8), 010C (5) – kein 2130
    }

    @Test
    public void headerAcknowledgementRequiresOwnOkLine() {
        assertTrue(Elm327Client.headerAcknowledged("ATSH7E1", "ATSH7E1\rOK"));
        assertFalse(Elm327Client.headerAcknowledged("ATSH7E1", "ATSH7E1"));
        assertFalse(Elm327Client.headerAcknowledged("ATSH7E1", "?"));
        assertFalse(Elm327Client.headerAcknowledged("ATSH7E1", "OK\rERROR"));
        assertFalse(Elm327Client.headerAcknowledged("ATSH7E1", ""));
        assertTrue(Elm327Client.headerAcknowledged("ATZ", "ELM327 v1.5"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "?"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "ATZ"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "NO DATA"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "STOPPED"));
        // Nachbauten melden teils nur eine Versionsnummer oder einen eigenen Namen (Review #35–#37)
        assertTrue(Elm327Client.headerAcknowledged("ATZ", "ATZ\r\rv2.1\r\r>"));
        assertTrue(Elm327Client.headerAcknowledged("ATZ", "ATZ\rVgate iCar"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "ATZ\r\r>"));
        assertFalse(Elm327Client.headerAcknowledged("ATZ", "SEARCHING..."));
    }

    @Test
    public void resyncWaitsForLatePrompt() throws Exception {
        start((cmd, out, in) -> {
            if (cmd.equals("0100")) {
                out.flush();
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                write(out, "41 00 BE 3F A8 13\r\r>");
            }
        });
        try {
            client.sendCommand("0100", 100);
            fail("Timeout erwartet");
        } catch (SocketTimeoutException expected) {
            assertTrue(client.resync(1500));
        }
    }

    /** ADR-002: Mitlesen bis zur Zeitgrenze, Leerzeichen stoppt, nur vollständige Zeilen zählen. */
    @Test
    public void monitorStopsWithSpace() throws Exception {
        start((cmd, out, in) -> {
            if (!cmd.equals("ATMA")) { write(out, "OK\r\r>"); return; }
            for (int i = 0; i < 3; i++) write(out, "30D5A3C6E2800123400\r");
            write(out, "30D5A");                      // Frame noch unterwegs, als die Zeit abläuft
            out.flush();
            int b = in.read();                         // wartet auf das Stopp-Zeichen
            if (b == ' ') write(out, "3C6E2800123400\r>");   // ELM gibt den Frame zu Ende aus, dann den Prompt
        });
        Elm327Client.MonitorResult m = client.monitorPassive(300, 64 * 1024);
        assertFalse(m.stopTimedOut);
        assertFalse(m.endedByAdapter);
        assertEquals(4, CanMonitor.parse(m.raw).frames);
        assertEquals(0, CanMonitor.parse(m.raw).unparsed);
    }

    /** Antwortet der Adapter nach dem Stopp-Zeichen nicht, bleiben die Daten erhalten (Simulator-Verhalten). */
    @Test
    public void monitorKeepsDataWhenStopIsIgnored() throws Exception {
        start((cmd, out, in) -> {
            if (!cmd.equals("ATMA")) return;
            write(out, "2F164002000\r0F330\r");
            out.flush();
            in.read();                                 // Stopp-Zeichen ignorieren, kein Prompt
            try { Thread.sleep(Elm327Client.MONITOR_STOP_TIMEOUT_MS + 500); } catch (InterruptedException ignored) { }
        });
        Elm327Client.MonitorResult m = client.monitorPassive(200, 64 * 1024);
        assertTrue(m.stopTimedOut);
        assertEquals(2, CanMonitor.parse(m.raw).frames);
    }

    @Test
    public void monitorEndedByAdapterAndTruncated() throws Exception {
        start((cmd, out, in) -> {
            if (!cmd.equals("ATMA")) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) sb.append("30D5A3C6E2800123400\r");
            write(out, sb + "BUFFER FULL\r\r>");
        });
        Elm327Client.MonitorResult m = client.monitorPassive(2000, 100);
        assertTrue(m.endedByAdapter);
        assertTrue(m.truncated);
        assertTrue(m.raw.length() <= 100);
        assertEquals(m.raw.split("\r").length, CanMonitor.parse(m.raw).frames);
    }

    /**
     * Review #35–#37: connect() schließt vor jedem Aufbau; das darf den (Bluetooth-)Öffner nicht endgültig abbrechen.
     * Der Nachbau-Öffner verhält sich wie BluetoothLink: nach cancel() scheitert jedes weitere open().
     */
    @Test
    public void reconnectDoesNotCancelOpener() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ElmLink.Opener sticky = new ElmLink.Opener() {
            @Override public ElmLink open(int timeoutMs) throws IOException {
                if (cancelled.get()) throw new IOException("abgebrochen");
                opens.incrementAndGet();
                return new FakeLink();
            }
            @Override public String describe() { return "Nachbau"; }
            @Override public void cancel() { cancelled.set(true); }
        };
        Elm327Client c = new Elm327Client(sticky);
        c.connect(1000);
        c.close();
        c.connect(1000);   // Wiederverbinden nach Abriss
        c.connect(1000);   // connect() ruft intern close()
        assertEquals(3, opens.get());
        assertFalse(cancelled.get());
        c.close();
    }

    private static final class FakeLink implements ElmLink {
        private volatile boolean open = true;
        @Override public InputStream input() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream output() { return new java.io.ByteArrayOutputStream(); }
        @Override public void setReadTimeout(int ms) { }
        @Override public boolean isOpen() { return open; }
        @Override public String describe() { return "Nachbau"; }
        @Override public void close() { open = false; }
    }
}
