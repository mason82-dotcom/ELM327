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

    @Test public void mainSafetyGateStaysReadOnly() {
        for (String s : new String[] {"04", "08", "3101", "2E0101", "2701", "14FFFFFF"}) {
            assertFalse("Der normale Adapterpfad muss weiter sperren: " + s, CommandSafety.isAllowed(s));
            assertFalse("Terminal darf kein Mode 04 senden: " + s, CommandSafety.isAllowedInTerminal(s));
        }
    }
}
