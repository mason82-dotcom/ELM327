package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;
import org.junit.Test;

/** Service-Labor: alle Voraussetzungen fail-closed und jede Freigabe einmalig. */
public class ServiceMode04GateTest {
    private static final long NOW = 100_000L;

    private ServiceMode04Gate.Preflight p(boolean connected, boolean armed, boolean confirmed,
            boolean evidence, Double rpm, Long rpmAt, Double kmh, Long speedAt,
            Double voltage, Long voltageAt) {
        return new ServiceMode04Gate.Preflight(true, connected, armed, confirmed, evidence,
                rpm, rpmAt, kmh, speedAt, voltage, voltageAt, NOW);
    }

    private ServiceMode04Gate.Preflight valid() {
        return p(true, true, true, true, 0.0, NOW - 500, 0.0, NOW - 500, 12.4, NOW - 500);
    }

    @Test public void onlyConfirmedStillEngineOffAndCurrentSnapshotCanIssueOnce() {
        assertNull(ServiceMode04Gate.blockedReason(valid()));
        ServiceMode04Gate.Ticket ticket = ServiceMode04Gate.issue(valid());
        assertTrue(ticket.consume());
        assertFalse("Mode 04 darf nicht wiederholt werden", ticket.consume());
    }

    @Test public void missingConsentOrConnectionOrSnapshotIsDenied() {
        for (ServiceMode04Gate.Preflight invalid : new ServiceMode04Gate.Preflight[] {
                p(false, true, true, true, 0.0, NOW, 0.0, NOW, 12.4, NOW),
                p(true, false, true, true, 0.0, NOW, 0.0, NOW, 12.4, NOW),
                p(true, true, false, true, 0.0, NOW, 0.0, NOW, 12.4, NOW),
                p(true, true, true, false, 0.0, NOW, 0.0, NOW, 12.4, NOW)
        }) {
            assertNotNull(ServiceMode04Gate.blockedReason(invalid));
            try { ServiceMode04Gate.issue(invalid); fail("Unzulässige Freigabe"); }
            catch (IllegalStateException expected) { }
        }
    }

    @Test public void runningOrMovingVehicleAlwaysBlocksClearance() {
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,600.0,NOW,0.0,NOW,12.4,NOW)));
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,0.0,NOW,1.0,NOW,12.4,NOW)));
    }

    @Test public void lowHighMissingAndStaleVoltageBlockClearance() {
        for (Double voltage : new Double[] {11.5,15.6,Double.NaN,null}) {
            assertNotNull(ServiceMode04Gate.blockedReason(
                    p(true,true,true,true,0.0,NOW,0.0,NOW,voltage,NOW)));
        }
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,0.0,NOW,0.0,NOW,12.3,NOW-9000)));
    }

    @Test public void staleOrMissingEngineAndSpeedBlocksClearance() {
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,0.0,NOW-9000,0.0,NOW,12.3,NOW)));
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,0.0,NOW,null,NOW,12.3,NOW)));
    }

    @Test public void mode04AckIsStrictAndErrorsCannotLookLikeSuccess() {
        assertTrue(ServiceMode04Gate.positiveAck("44\r\r>"));
        assertTrue(ServiceMode04Gate.positiveAck("44 00\r>"));
        for (String raw : new String[] {"", "04\r>", "7F 04 78\r>", "NO DATA\r>",
                "44 01\r>", "44\rERROR\r>", "?", "41 04 00\r>", "4444\r>"}) {
            assertFalse(raw, ServiceMode04Gate.positiveAck(raw));
        }
    }

    @Test public void incompleteOrUnparseableDtcReportCannotArmServiceClear() {
        assertTrue(ServiceMode04Gate.hasCompleteDtcSnapshot(
                "Gespeichert: keine Fehlercodes gemeldet\nPending: keine Fehlercodes gemeldet\n"
                + "Permanent: nicht unterstützt / NO DATA"));
        assertTrue(ServiceMode04Gate.hasCompleteDtcSnapshot(
                "Gespeichert:\n• P0016\nPending: keine Fehlercodes gemeldet\n"
                + "Permanent: keine permanenten Fehlercodes"));
        for (String bad : new String[] {
                "Gespeichert: keine Antwort",
                "Gespeichert: keine Fehlercodes erkannt\nRohantwort: 41 0C",
                "Pending: nicht gelesen – Adapter meldet CAN ERROR",
                "Permanent: Timeout – nicht gelesen",
                "Permanent: übersprungen · Verbindung wird neu aufgebaut",
                "Gespeichert: fehlgeschlagen"
        }) {
            assertFalse(bad, ServiceMode04Gate.hasCompleteDtcSnapshot(
                    "Gespeichert: keine Fehlercodes gemeldet\n"
                    + "Pending: keine Fehlercodes gemeldet\n"
                    + "Permanent: keine permanenten Fehlercodes\n" + bad));
        }
        assertFalse(ServiceMode04Gate.hasCompleteDtcSnapshot("Gespeichert: keine Fehlercodes gemeldet"));
        assertFalse(ServiceMode04Gate.hasCompleteDtcSnapshot(null));
    }

    @Test public void ambiguousLowRpmDuringCrankingMustNotPassEngineOffPreflight() {
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,1.0,NOW,0.0,NOW,12.4,NOW)));
        assertNotNull(ServiceMode04Gate.blockedReason(
                p(true,true,true,true,50.0,NOW,0.0,NOW,12.4,NOW)));
    }

    @Test public void safetyPreflightRequiresVerifiedEngineEcuNotTransmissionFallback() {
        ObdPid rpm = ObdPid.defaultPids().stream()
                .filter(pid -> pid.pid == 0x0C).findFirst().orElseThrow();
        ObdPid speed = ObdPid.defaultPids().stream()
                .filter(pid -> pid.pid == 0x0D).findFirst().orElseThrow();
        ObdPid voltage = ObdPid.defaultPids().stream()
                .filter(pid -> pid.pid == 0x42).findFirst().orElseThrow();
        assertEquals(0.0, ServiceMode04Gate.engineOnlyPid(rpm,
                "7E904410C0000\r7E804410C0000\r"), 1e-9);
        assertEquals(0.0, ServiceMode04Gate.engineOnlyPid(speed,
                "7E903410D00"), 1e-9);
        // Real 20.11.2026 example: 7E9 reports another voltage and arrives first.
        assertEquals(11.497, ServiceMode04Gate.engineOnlyPid(voltage,
                "7E90441422AAD 7E80441422CE9"), 1e-6);
        assertNull(ServiceMode04Gate.engineOnlyPid(rpm, "7E904410C0000"));
        assertNull(ServiceMode04Gate.engineOnlyPid(rpm, "410C0000"));
        assertNull(ServiceMode04Gate.engineOnlyPid(rpm, "7E803410D00"));
        assertNull(ServiceMode04Gate.engineOnlyPid(rpm, "7E804410C0E"));
    }

    @Test public void mainSafetyGateStaysReadOnly() {
        for (String s : new String[] {"04", "08", "3101", "2E0101", "2701", "14FFFFFF"}) {
            assertFalse("Der normale Adapterpfad muss weiter sperren: " + s, CommandSafety.isAllowed(s));
            assertFalse("Terminal darf kein Mode 04 senden: " + s, CommandSafety.isAllowedInTerminal(s));
        }
    }
}
