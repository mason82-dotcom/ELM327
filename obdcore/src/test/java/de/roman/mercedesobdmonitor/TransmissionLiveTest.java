package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** ADR-008: Getriebe live – Sequenz, Header-Rückweg, Gang-Text, Takt und Selbstabschaltung. */
public class TransmissionLiveTest {
    /** Antwort 61 30 der Fahrt am 21.11.2026 (D, Gang 2 → 3, ATF 0x70 = 62 °C), im Live-Format mit ATH1. */
    static final String DRIVE_ATH1 = "7E9 10 18 61 30 01 56 00 2D\r7E9 21 03 C8 01 07 01 02 32\r"
            + "7E9 22 70 00 A3 00 A2 02 7B\r7E9 23 03 81 00 90 00 00 00";
    /** Dieselbe Antwort ohne Header (ATH0), wie die exklusive Diagnose sie sieht. */
    static final String DRIVE_ATH0 = "018\r0: 61 30 01 56 00 2D\r1: 03 C8 01 07 01 02 32\r"
            + "2: 70 00 A3 00 A2 02 7B\r3: 03 81 00 90 00 00 00";

    private ElmFake elm;
    private Elm327Client client;
    private final AtomicInteger drops = new AtomicInteger();
    private java.util.function.LongSupplier savedClock;

    @Before
    public void setUp() throws Exception {
        savedClock = Elm327Client.clock;
        Elm327Client.clock = () -> System.nanoTime() / 1_000_000L;
        elm = new ElmFake();
        client = elm.connect();
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        elm.close();
        Elm327Client.clock = savedClock;
    }

    private TransmissionLive.Sample poll() throws IOException {
        return TransmissionLive.poll(client, drops::incrementAndGet);
    }

    @Test
    public void sendsExactlyTheAdr001SequenceAndReadsGearAndAtfWithHeaders() throws Exception {
        elm.answers = c -> c.equals("2130") ? DRIVE_ATH1 : "OK";
        TransmissionLive.Sample s = poll();
        assertEquals(List.of("ATSH7E1", "2130", "ATSH7DF"), elm.received);
        assertEquals(62.0, s.celsius, 0.01);
        assertEquals("D 2→3", s.gear);
        assertEquals(2, s.gearNumber);
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertEquals(0, drops.get());
    }

    @Test
    public void parsesTheSameAnswerWithoutHeaders() {
        AtfTemperature.Reading r = AtfTemperature.parse(DRIVE_ATH0);
        assertEquals(62.0, r.celsius, 0.01);
        assertEquals("D 2→3", TransmissionLive.sample(r, 0, false).gear);
        assertEquals(62.0, AtfTemperature.parse(DRIVE_ATH1).celsius, 0.01);
    }

    @Test
    public void lateAnswerIsResyncedAndHeaderStillRestored() throws Exception {
        elm.answers = c -> c.equals("2130") ? "@delay=2300;" + DRIVE_ATH1 : "OK";
        TransmissionLive.Sample s = poll();
        assertFalse(s.valid());
        assertTrue(s.timedOut);
        assertEquals("Zeitüberschreitung", s.problem);
        assertEquals(List.of("ATSH7E1", "2130", "ATSH7DF"), elm.received);
        assertEquals(CommandSafety.DEFAULT_HEADER, client.header());
        assertEquals(0, drops.get());
    }

    @Test
    public void failedHeaderRestoreDropsTheConnection() throws Exception {
        elm.answers = c -> c.equals("ATSH7DF") ? null : c.equals("2130") ? DRIVE_ATH1 : "OK";
        try {
            poll();
            fail("Timeout beim Rückweg muss an die Sitzung gehen");
        } catch (TransmissionLive.HeaderNotRestored expected) {
            // ObdSession schaltet Getriebe live für die Verbindung ab
        }
        assertEquals(1, drops.get());
    }

    @Test
    public void lostConnectionIsNoHeaderFailure() throws Exception {
        elm.answers = c -> c.equals("2130") ? DRIVE_ATH1 : "OK";
        client.close();
        try {
            poll();
            fail("ohne Verbindung keine Abfrage");
        } catch (TransmissionLive.HeaderNotRestored wrong) {
            fail("Verbindungsabriss darf Getriebe live nicht abschalten");
        } catch (IOException expected) {
            // normaler Neuaufbau in ObdSession
        }
        assertEquals(1, drops.get());
    }

    @Test
    public void rejectionIsAnInvalidSampleNotAnError() throws Exception {
        elm.answers = c -> c.equals("2130") ? "7E9 03 7F 21 11" : "OK";
        TransmissionLive.Sample s = poll();
        assertFalse(s.valid());
        assertTrue(s.problem, s.problem.contains("lehnt"));
        assertEquals(0, drops.get());
    }

    @Test
    public void gearTextFollowsSelectorAndNibbles() {
        assertEquals("P", gear(10, 0xDD));
        assertEquals("R", gear(9, 0xBB));
        assertEquals("N", gear(8, 0x00));
        assertEquals("D 1", gear(7, 0x11));
        assertEquals("D 2→3", gear(7, 0x32));
        assertEquals("D", gear(7, 0x00));       // D im Stand ohne eingelegten Gang
        assertNull(gear(3, 0x11));              // unbekannter Wählhebel: keine Vermutung
    }

    private static String gear(int selector, int gearByte) {
        byte[] r = new byte[24];
        r[9] = (byte) selector;
        r[12] = (byte) gearByte;
        return TransmissionLive.gearText(r);
    }

    @Test
    public void scheduleKeepsIntervalAndSwitchesOffAfterRepeatedFailures() {
        TransmissionLive.Schedule s = new TransmissionLive.Schedule();
        assertTrue(s.due(0));
        s.started(0);
        assertFalse(s.due(TransmissionLive.INTERVAL_MS - 1));
        assertTrue(s.due(TransmissionLive.INTERVAL_MS));
        assertFalse(s.onResult(false, "keine Antwort"));
        assertFalse(s.onResult(true, null));          // gültige Antwort setzt den Zähler zurück
        assertFalse(s.onResult(false, "keine Antwort"));
        assertFalse(s.onResult(false, "keine Antwort"));
        assertTrue(s.onResult(false, "keine Antwort"));
        assertFalse(s.due(Long.MAX_VALUE / 2));
        assertTrue(s.offReason(), s.offReason().contains("3× keine gültige Antwort"));
    }

    @Test
    public void noCanHintOnlyOncePerConnection() {
        TransmissionLive.Schedule s = new TransmissionLive.Schedule();
        assertTrue(s.noteNoCanOnce());
        assertFalse(s.noteNoCanOnce());
    }

    @Test
    public void droppedHeaderSwitchesOffAtOnce() {
        TransmissionLive.Schedule s = new TransmissionLive.Schedule();
        s.onDropped("Timeout");
        assertFalse(s.due(0));
        assertTrue(s.offReason().contains("Header-Rückweg"));
    }

    @Test
    public void csvAndDisplayKeysAreOutsideObdPids() {
        assertTrue(TransmissionLive.KEY_ATF > 0xFF);
        assertTrue(TransmissionLive.KEY_GEAR > 0xFF);
        assertEquals("62 °C", TransmissionLive.ATF_PID.format(62));
    }
}
