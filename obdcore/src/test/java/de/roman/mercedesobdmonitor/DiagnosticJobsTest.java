package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
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
    /** Antwort je Befehl; null = keine Antwort (Timeout). Wird vor dem ersten Befehl gesetzt. */
    private Function<String, String> answers;
    private List<String> received;
    private ElmFake elm;
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
        elm = new ElmFake();
        elm.answers = c -> answers.apply(c);
        received = elm.received;
        client = elm.connect();
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        elm.close();
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
        assertEquals(List.of("ATSH7E1", "1A86", "1802FF00", "ATSH7DF"), received);
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertTrue(r, r.contains("Code 11: Dienst nicht unterstützt"));
        assertTrue(r, r.contains("keine verwertbare Kennung"));
        assertTrue(r, r.contains("0715 · Status E0 (aktuell vorhanden, Warnlampe) – nicht in der 722.9-Tabelle"));
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
        assertTrue(r, r.contains("ME9.7, einer von: Einlassnockenwelle rechts dauerhaft Richtung früh"));
        assertTrue(r, r.contains("M272-Werkstatthinweise"));
        assertTrue(r, r.contains("Baureihe 204 (W204/S204) nimmt das Bulletin ausdrücklich aus"));
        assertFalse(r, r.contains("Kommunikationsfehler"));
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

    /** ATMA-Antworten wie am C350: ungefiltert sofort voll, mit Filter 30D zwei Frames, sonst still. */
    private static String monitorAnswer(String filter) {
        if (filter == null) return "105 00 1C E4 00 00 00 00 00\r105 00 1C E5 00 00 00 00 00\rBUFFER FULL";
        return filter.equals("30D") ? "@stream=30D 5A 3C 6E 41 02 00 0A 00\r30D 5A 3C 6E 41 02 00 0B 00\r" : "@stream=";
    }

    /** Elm-Nachbau mit Filterzustand; {@code probe} liefert die Antworten auf 01 00 der Reihe nach. */
    private void filteringElm(String... probe) {
        String[] filter = {null};
        int[] probes = {0};
        answers = c -> {
            if (c.startsWith("ATCRA")) filter[0] = c.length() > 5 ? c.substring(5) : null;
            return switch (c) {
                case "ATMA" -> monitorAnswer(filter[0]);
                case "0100" -> probe[Math.min(probes[0]++, probe.length - 1)];
                default -> "OK";
            };
        };
    }

    /** ADR-002 Option C: Vergleich, Überblick, Rücksetz-Probe, je Kennung ein Filter, Rücksetzen, Prüfanfrage. */
    @Test
    public void canMonitorFiltersEachKnownFrameAndVerifiesTheReset() throws Exception {
        filteringElm("41 00 BE 3F A8 13");
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        List<String> expected = new ArrayList<>(List.of("0100", "ATH1", "ATCAF0", "ATMA", "ATCRA"));
        for (int id : CanMonitor.W204_POWERTRAIN.keySet()) {
            expected.add(CommandSafety.receiveFilter(id));
            expected.add("ATMA");
        }
        expected.addAll(List.of("ATCRA", "ATCAF1", "ATH0", "0100"));
        assertEquals(expected, received);
        assertTrue(host.dropped.isEmpty());
        assertTrue(r, r.contains("BUFFER FULL"));
        assertTrue(r, r.contains("• 30D ECM_A1 (Motorsteuergerät) · 2 Frames"));
        assertTrue(r, r.contains("Motoröltemperatur: 110"));                    // Byte 2 = 0x6E, roh
        assertTrue(r, r.contains("• 2F1 TCM_A1 (Getriebesteuergerät) – nicht empfangen"));
        assertTrue(r, r.contains("1 von 5 bekannten Botschaften kommen am OBD-Port an"));
        assertFalse(r, r.contains("Hinweis: Nach dem Mitlesen"));
    }

    @Test
    public void canMonitorNeverSetsAFilterTheAdapterCannotReset() throws Exception {
        // Klon kennt ATCRA ohne Adresse nicht: kein einziger Filter, Verbindung danach neu (Bestätigung fehlt)
        answers = c -> switch (c) {
            case "ATMA" -> monitorAnswer(null);
            case "ATCRA" -> "?";
            case "0100" -> "41 00 BE 3F A8 13";
            default -> "OK";
        };
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertTrue(received.toString(), received.stream().noneMatch(c -> c.startsWith("ATCRA") && c.length() > 5));
        assertTrue(r, r.contains("übersprungen: der Adapter kennt das Zurücksetzen des Empfangsfilters (ATCRA) nicht"));
        // C350, 22.11.: ohne gesetzten Filter kein zweites ATCRA und kein Neuaufbau
        assertEquals(1, received.stream().filter("ATCRA"::equals).count());
        assertTrue(host.dropped.isEmpty());
        assertFalse(r, r.contains("neu aufgebaut"));
    }

    @Test
    public void canMonitorNamesTheUnconfirmedStep() throws Exception {
        answers = c -> switch (c) {
            case "ATMA" -> monitorAnswer(null);
            case "ATCRA" -> "?";
            case "ATCAF1" -> "?";
            case "0100" -> "41 00 BE 3F A8 13";
            default -> "OK";
        };
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertEquals(1, host.dropped.size());
        assertTrue(r, r.contains("der Adapter hat ATCAF1 (normales Format) nicht bestätigt"));
    }

    @Test
    public void canMonitorDropsWhenTheRawFormatStays() throws Exception {
        // ATCAF1 ohne Wirkung: 01 00 kommt mit PCI-Byte zurück – kein gültiges normales Format
        filteringElm("41 00 BE 3F A8 13", "06 41 00 BE 3F A8 13");
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertEquals(1, host.dropped.size());
        assertTrue(r, r.contains("keine gültige Antwort auf 01 00"));
    }

    @Test
    public void canMonitorKeepsTheConnectionWithIgnitionOff() throws Exception {
        filteringElm("NO DATA");
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertTrue(host.dropped.isEmpty());
        assertTrue(r, r.contains("Zündung aus?"));
    }

    @Test
    public void canMonitorKeepsResultsWhenAFilterTimesOut() throws Exception {
        String[] filter = {null};
        answers = c -> {
            if (c.startsWith("ATCRA")) filter[0] = c.length() > 5 ? c.substring(5) : null;
            if (c.equals("ATCRA105")) return null;                          // keine Antwort
            return c.equals("ATMA") ? monitorAnswer(filter[0]) : c.equals("0100") ? "41 00 BE 3F A8 13" : "OK";
        };
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertTrue(r, r.contains("• 30D ECM_A1 (Motorsteuergerät) · 2 Frames"));   // vor dem Abbruch gesammelt
        assertTrue(r, r.contains("Mitlesen unterbrochen"));
        assertEquals(1, host.dropped.size());
    }

    @Test
    public void canMonitorSkipsFiltersWhenAtmaIsUnknown() throws Exception {
        answers = c -> c.equals("ATMA") ? "?" : c.equals("0100") ? "41 00 BE 3F A8 13" : "OK";
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertTrue(r, r.contains("übersprungen: der Adapter kennt ATMA nicht"));
        // kein Filter gesetzt; das abschließende Zurücksetzen (ATCRA ohne Adresse) läuft immer
        assertTrue(received.toString(), received.stream().noneMatch(c -> c.startsWith("ATCRA") && c.length() > 5));
        assertTrue(host.dropped.isEmpty());
    }

    @Test
    public void canMonitorStopsAtOnceWhenTheAdapterKeepsStreaming() throws Exception {
        answers = c -> c.equals("ATMA") ? "@stuck=105 00 1C E4 00 00 00 00 00\r" : c.equals("0100") ? "41 00 BE 3F A8 13" : "OK";
        String r = new DiagnosticJobs(host).readCanMonitor(client);
        assertEquals(List.of("0100", "ATH1", "ATCAF0", "ATMA"), received);   // keine Filter, kein Rücksetzen ins Leere
        assertEquals(1, host.dropped.size());
        assertTrue(r, r.contains("nicht sauber beendet"));
    }

    @Test
    public void dtcIncompleteAnswerIsNotReportedAsNoCodes() throws Exception {
        answers = map(Map.of("03", "4300\r00A\r0:430401710172", "07", "43 00", "0A", "NO DATA", "0100", "41 00 BE 3F A8 13"));
        String r = new DiagnosticJobs(host).readDtcReport(client);
        assertTrue(r, r.contains("Gespeichert: nicht gelesen – Antwort unvollständig"));
    }

    @Test
    public void misfireReportNamesCylindersThatDidNotAnswer() throws Exception {
        answers = c -> switch (c) {
            case "06A0" -> "46 A0 60 00 00 00";       // A2, A3
            case "06A2" -> "013\r0:46A20B240000\r1:0000FFFFA20C24\r2:00000000FFFF";
            default -> "CAN ERROR";
        };
        String r = new DiagnosticJobs(host).readMisfires(client);
        assertTrue(r, r.contains("Zyl. 2: nicht gelesen"));
        assertTrue(r, r.contains("nur 1 von 2 Zylindern"));
    }

    /** 01 42 im Starttest: mit Headern, damit das Motorsteuergerät gilt und nicht die zuerst eintreffende Antwort. */
    @Test
    public void starterTestReadsTheEngineEcu() throws Exception {
        FakeHost h = new FakeHost() {
            @Override public boolean starterCancelled() { return true; }
        };
        answers = c -> c.equals("0142") ? "7E9 04 41 42 2A 94\r7E8 04 41 42 2C EC" : "OK";
        DiagnosticJobs jobs = new DiagnosticJobs(h);
        jobs.readStarterTest(client);
        assertEquals(List.of("ATH1", "0142"), received);
        received.clear();
        h.protocolNumber = "3";
        jobs.readStarterTest(client);
        assertEquals(List.of("0142"), received);
        assertEquals(11.5, ObdPid.of(0x42).parse("7E9 04 41 42 2A 94\r7E8 04 41 42 2C EC"), 0.01);
    }
}
