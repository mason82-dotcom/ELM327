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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Diagnoseabläufe gegen einen ELM327-Nachbau: welche Befehle in welcher Reihenfolge gesendet werden, ob der Header
 * zurückgesetzt und bei Störungen die Verbindung verworfen wird (Tech-Debt Phase 2, vorher ohne Test).
 */
public class DiagnosticJobsTest {
    /** Antwort je Befehl; null = keine Antwort (Timeout). */
    private Function<String, String> answers;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private ServerSocket server;
    private Thread thread;
    private Elm327Client client;
    private FakeHost host;
    private java.util.function.LongSupplier savedClock;

    static class FakeHost implements DiagnosticJobs.Host {
        final List<String> log = new ArrayList<>();
        final List<Elm327Client> dropped = new ArrayList<>();
        String protocolNumber = "6";
        volatile boolean canceled;
        long timeouts;

        @Override public void log(String line) { log.add(line); }
        @Override public Elm327Client.CommandResult diag(Elm327Client c, String cmd, int timeoutMs) throws IOException {
            Elm327Client.CommandResult r = c.sendCommand(cmd, timeoutMs);
            log.add(cmd + " → " + r.raw);
            return r;
        }
        @Override public void drop(Elm327Client c) { dropped.add(c); c.close(); }
        @Override public String protocolNumber() { return protocolNumber; }
        @Override public String protocol() { return "ISO 15765-4 CAN 11 bit / 500 kbit/s"; }
        @Override public Boolean protocolIsCan() { return Boolean.TRUE; }
        @Override public void countTimeouts(long n) { timeouts += n; }
        @Override public void countIoError() { }
        @Override public void status(String text) { }
        @Override public boolean starterCancelled() { return false; }
        @Override public boolean dtcCancelled() { return canceled; }
        @Override public double recentAmbient() { return Double.NaN; }
        @Override public boolean livePid(int pid) { return true; }
        @Override public long now() { return System.nanoTime() / 1_000_000L; }
    }

    @Before
    public void setUp() throws Exception {
        savedClock = Elm327Client.clock;
        Elm327Client.clock = () -> System.nanoTime() / 1_000_000L;
        host = new FakeHost();
        server = new ServerSocket(0);
        thread = new Thread(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder cmd = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    if (b != '\r') {
                        cmd.append((char) b);
                        continue;
                    }
                    String c = cmd.toString();
                    cmd.setLength(0);
                    received.add(c);
                    String a = answers.apply(c);
                    if (a != null) {
                        out.write((a + "\r\r>").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // Verbindung zu
            }
        });
        thread.start();
        client = new Elm327Client("127.0.0.1", server.getLocalPort(), null);
        client.connect(2000);
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        server.close();
        thread.join(2000);
        Elm327Client.clock = savedClock;
    }

    private static Function<String, String> map(Map<String, String> m) {
        return c -> m.containsKey(c) ? m.get(c) : "?";
    }

    @Test
    public void atfSendsExactSequenceAndRestoresHeader() throws Exception {
        answers = map(Map.of("ATSH7E1", "OK", "2130", "61 30 00 0A 03 E8 00 64 06 08 01 01 12 82 00 C8 00 FA 00 00",
                "ATSH7DF", "OK"));
        String r = new DiagnosticJobs(host).readAtfTemperature(client);
        assertEquals(List.of("ATSH7E1", "2130", "ATSH7DF"), received);
        assertTrue(r, r.contains("ATF-Temperatur: 80 °C"));
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertTrue(host.dropped.isEmpty());
    }

    @Test
    public void atfDropsConnectionWhenHeaderRestoreFails() throws Exception {
        answers = c -> c.equals("ATSH7DF") ? null : c.equals("2130") ? "NO DATA" : "OK";
        try {
            new DiagnosticJobs(host).readAtfTemperature(client);
            fail("Timeout beim Rückweg muss an die Sitzung gehen");
        } catch (java.net.SocketTimeoutException expected) {
            // runExclusiveDiagnosis zählt ihn und zeigt den Abbruch
        }
        assertEquals(1, host.dropped.size());
    }

    /** ADR-004: genau diese sieben Befehle, in dieser Reihenfolge, Header am Ende 7DF. */
    @Test
    public void kwpSequenceIsExactlyTheAllowedTuples() throws Exception {
        answers = c -> switch (c) {
            case "1A86" -> "7F 1A 11";
            case "1802FF00" -> "58 01 07 15 E0";
            default -> "OK";
        };
        String r = new DiagnosticJobs(host).readKwpIdentDtc(client);
        assertEquals(List.of("ATSH7E0", "1A86", "ATSH7DF", "ATSH7E1", "1A86", "1802FF00", "ATSH7DF"), received);
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertTrue(r, r.contains("Code 11: Dienst nicht unterstützt"));
        assertTrue(r, r.contains("0715 (ISO-Lesart P0715) · Status E0"));
        assertTrue(host.dropped.isEmpty());
    }

    @Test
    public void kwpAndAtfRefuseWithoutElevenBitCan() throws Exception {
        answers = c -> "OK";
        host.protocolNumber = "3";   // ISO 9141-2 (K-Line)
        DiagnosticJobs jobs = new DiagnosticJobs(host);
        assertTrue(jobs.readKwpIdentDtc(client).contains("nur über CAN mit 11-bit"));
        assertTrue(jobs.readAtfTemperature(client).contains("nur über CAN mit 11-bit"));
        assertTrue(jobs.readCanMonitor(client).contains("11-bit"));
        assertTrue("nichts gesendet", received.isEmpty());
    }

    /** Timeout ohne Prompt in einem Modus: einmal gezählt, Verbindung verworfen, übrige Modi übersprungen. */
    @Test
    public void dtcTimeoutCountsOnceAndDropsConnection() throws Exception {
        answers = c -> c.equals("07") ? null : c.equals("03") ? "43 00" : "OK";
        String r = new DiagnosticJobs(host).readDtcReport(client);
        assertEquals(1, host.timeouts);
        assertEquals(1, host.dropped.size());
        assertTrue(r, r.contains("Pending: Timeout · Verbindung wird neu aufgebaut"));
        assertTrue(r, r.contains("Permanent: übersprungen"));
        assertEquals(List.of("03", "07"), received);
    }

    /**
     * Review #35–#37: STOP während des Resync nach einem 07-Timeout. Danach weder Timeout zählen noch die
     * Verbindung verwerfen (das hat der Abbruch schon getan). Aufrufe von dtcCancelled(): 03, 07, nach dem
     * Timeout (noch nicht abgebrochen), nach dem Resync (abgebrochen).
     */
    @Test
    public void dtcCancelDuringResyncIsNotCountedAsTimeout() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FakeHost cancelling = new FakeHost() {
            @Override public boolean dtcCancelled() { return calls.incrementAndGet() >= 4; }
        };
        answers = c -> c.equals("07") ? null : c.equals("03") ? "43 00" : "OK";
        String r = new DiagnosticJobs(cancelling).readDtcReport(client);
        assertEquals(0, cancelling.timeouts);
        assertTrue(cancelling.dropped.isEmpty());
        assertTrue(r, r.contains("Pending: abgebrochen"));
        assertEquals(List.of("03", "07"), received);
    }

    /** Abbruch während der blockierenden 07-Abfrage: kein 0A und kein 0100-Nachtest. */
    @Test
    public void dtcCancelClosesPendingReadAndNeverSendsRemainingModes() throws Exception {
        CountDownLatch reachedPending = new CountDownLatch(1);
        answers = cmd -> {
            if ("03".equals(cmd)) return "43 00";
            if ("07".equals(cmd)) {
                reachedPending.countDown();
                return null;  // ELM wartet auf Prompt, bis der Client-Socket geschlossen wird
            }
            return "OK";
        };
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                report.set(new DiagnosticJobs(host).readDtcReport(client));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "dtc-abort-test");
        worker.start();
        try {
            assertTrue("Pending-Anfrage wurde nicht erreicht", reachedPending.await(2, TimeUnit.SECONDS));
            host.canceled = true;
            client.close(); // dieselbe I/O-Unterbrechung wie cancelDtcScan
            worker.join(4500);
            assertFalse("DTC-Abbruch darf nicht 3,5 s auf Timeout warten", worker.isAlive());
            assertTrue("unerwartete Ausnahme: " + failure.get(), failure.get() == null);
            assertTrue(report.get(), report.get().contains("Pending: abgebrochen"));
            assertEquals(List.of("03", "07"), received);
            assertEquals("Abbruch darf kein Adapter-Timeout zählen", 0, host.timeouts);
        } finally {
            host.canceled = true;
            client.close();
            worker.join(4500);
        }
    }

    @Test
    public void dtcReportReadsThreeModesWithHintsAndProbe() throws Exception {
        answers = map(Map.of("03", "43 01 00 16", "07", "47 00", "0A", "NO DATA", "0100", "41 00 BE 3F A8 13"));
        String r = new DiagnosticJobs(host).readDtcReport(client);
        assertEquals(List.of("03", "07", "0A", "0100"), received);
        assertTrue(r, r.contains("P0016"));
        assertTrue(r, r.contains("M272-Werkstatthinweise"));
        assertTrue(r, r.contains("Baureihe 204 (W204/S204) nimmt das Bulletin ausdrücklich aus"));
        assertFalse(r, r.contains("Kommunikationsfehler"));
    }

    /** ADR-007: genau 0105, ATSH7E0, 21 01…21 FE, ATSH7DF; Header am Ende 7DF; Kandidat aus 21 2A; Lauf gespeichert. */
    @Test
    public void engineLocalIdScanSendsExactRangeAndRestoresHeader() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>();
        FakeHost h = new FakeHost() {
            @Override public void saveLocalIdScan(String text) { saved.set(text); }
        };
        answers = c -> {
            if (c.equals("0105")) return "41 05 7A";                    // 82 °C
            if (c.startsWith("ATSH")) return "OK";
            if (c.equals("212A")) return "61 2A 00 7B 10";              // Byte 1 = 83 °C (A−40)
            if (c.equals("2101")) return "61 01 FF FF";
            return "7F 21 12";
        };
        String r = new DiagnosticJobs(h).readEngineLocalIds(client);
        assertEquals(1 + 1 + 254 + 1, received.size());
        assertEquals("0105", received.get(0));
        assertEquals("ATSH7E0", received.get(1));
        assertEquals("2101", received.get(2));
        assertEquals("21FE", received.get(255));
        assertEquals("ATSH7DF", received.get(256));
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertTrue(h.dropped.isEmpty());
        assertTrue(r, r.contains("Antworten 2 · abgelehnt 252"));
        assertTrue(r, r.contains("21 2A Byte 1: A − 40 = 83,0 °C"));
        assertTrue(saved.get(), saved.get().startsWith("82.0\n"));
        // außerhalb der Suche bleibt 21 xx auch an 7E0 gesperrt (Client nicht mehr im Suchmodus)
        client.sendCommand("ATSH7E0", 1500);
        try {
            client.sendCommand("212A", 1000);
            fail("21 xx außerhalb der Kennungssuche muss gesperrt sein");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Read-Only-Schutz"));
        }
        client.sendCommand("ATSH7DF", 1500);
    }

    /** Review: NO DATA zählt als Schweigen – nach acht in Folge endet die Suche, Header trotzdem zurück. */
    @Test
    public void engineLocalIdScanStopsWhenEcuIsSilent() throws Exception {
        answers = c -> c.equals("0105") ? "41 05 3C" : c.startsWith("ATSH") ? "OK" : "NO DATA";
        String r = new DiagnosticJobs(host).readEngineLocalIds(client);
        assertEquals(1 + 1 + 8 + 1, received.size());
        assertTrue(r, r.contains("VORZEITIG BEENDET: 8 Anfragen in Folge ohne Antwort"));
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
    }

    /** Review: „Dienst nicht unterstützt“ gilt für alle Kennungen – eine Anfrage genügt. */
    @Test
    public void engineLocalIdScanStopsOnServiceNotSupported() throws Exception {
        answers = c -> c.equals("0105") ? "41 05 3C" : c.startsWith("ATSH") ? "OK" : "7F 21 11";
        String r = new DiagnosticJobs(host).readEngineLocalIds(client);
        assertEquals(List.of("0105", "ATSH7E0", "2101", "ATSH7DF"), received);
        assertTrue(r, r.contains("lehnt den Dienst 21 grundsätzlich ab"));
    }

    /** Review: STOP behält die bis dahin gelesenen Antworten. */
    @Test
    public void engineLocalIdScanStopKeepsPartialResults() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        FakeHost h = new FakeHost() {
            @Override public boolean localIdScanCancelled() { return asked.incrementAndGet() > 3; }
        };
        answers = c -> c.equals("0105") ? "41 05 3C" : c.startsWith("ATSH") ? "OK" : "61" + c.substring(2) + " 3C";
        String r = new DiagnosticJobs(h).readEngineLocalIds(client);
        assertEquals(List.of("0105", "ATSH7E0", "2101", "2102", "2103", "ATSH7DF"), received);
        assertTrue(r, r.contains("Antworten 3"));
        assertTrue(r, r.contains("VORZEITIG BEENDET: abgebrochen"));
    }

    @Test
    public void engineLocalIdScanNeedsCan11Bit() throws Exception {
        host.protocolNumber = "3";
        answers = c -> "OK";
        String r = new DiagnosticJobs(host).readEngineLocalIds(client);
        assertTrue(r, r.contains("nur über CAN"));
        assertTrue(received.isEmpty());
    }

    /** A: Gemischadaption je Bank steht im Monitortest-Bericht unter der Überschrift und wird gespeichert. */
    @Test
    public void monitorTestsReportMixtureAdaptationPerBank() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>();
        FakeHost h = new FakeHost() {
            @Override public void saveMixtureAdaptation(String text) { saved.set(text); }
            @Override public double liveCoolant() { return 88; }
        };
        answers = map(Map.of(
                "0600", "46 00 00 00 00 01", "0620", "46 20 00 00 00 01", "0640", "46 40 00 00 00 01",
                "0660", "46 60 00 00 00 01", "0680", "46 80 C0 00 00 00",
                "0681", "46 81 80 AF FF CC FE 3E 01 C2 81 82 05 86 8E 59 A7 A6 81",
                "0682", "46 82 80 AF FF D1 FE 3E 01 C2 82 82 05 83 2C 59 A7 A6 81"));
        String r = new DiagnosticJobs(h).readMonitorTests(client);
        assertTrue(r, r.startsWith("Monitortests (Mode 06)\n\nGEMISCHADAPTION JE BANK"));
        assertTrue(r, r.contains("Bank 1 (rechts, Zyl. 1–3): Faktor 1,051"));
        assertTrue(r, r.contains("Bank 2 (links, Zyl. 4–6): Faktor 1,024"));
        assertTrue(r, r.contains("Gemischadaption Faktor (Ref. 0,70–1,30)"));
        assertTrue(saved.get(), saved.get() != null && saved.get().contains(";88.00000;"));
    }
}
