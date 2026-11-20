package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Schreibt keine Daten und öffnet keine Verbindung zum Fahrzeug. */
public class ServiceActionLabTest {
    @Test public void allProposalsRemainNonExecutable() {
        assertEquals(ServiceActionLab.Action.values().length, ServiceActionLab.catalog().size());
        for (ServiceActionLab.Action action : ServiceActionLab.Action.values()) {
            ServiceActionLab.Proposal p = ServiceActionLab.describe(action);
            assertEquals(action, p.action);
            assertFalse(p.label.isEmpty());
            assertFalse(p.prerequisites.isEmpty());
            assertFalse(p.limitation.isEmpty());
            assertFalse(action.name(), ServiceActionLab.canExecuteOnVehicle(action));
        }
    }

    @Test public void destructiveAndActuatorCommandsRemainBlockedAtRealTransportGate() {
        for (String command : new String[] {
                "04", "14FFFFFF", "2E000100", "2F000100", "31010000",
                "2701", "2702", "340000", "3600", "3D000100", "08",
                "ATSH7E2", "ATSH000", "ATCRA7EA"
        }) {
            assertFalse(command, CommandSafety.isAllowed(command));
            assertFalse(command, CommandSafety.isAllowed(command, CommandSafety.DEFAULT_HEADER));
            assertFalse(command, CommandSafety.isAllowedInTerminal(command));
        }
        assertTrue(CommandSafety.isAllowed("03"));
        assertTrue(CommandSafety.isAllowed("07"));
        assertTrue(CommandSafety.isAllowed("0A"));
    }

    @Test public void service04IsNotEquivalentToAllManufacturerDtcMemory() {
        ServiceActionLab.Proposal p = ServiceActionLab.describe(ServiceActionLab.Action.CLEAR_EMISSIONS_DTC);
        assertEquals(ServiceActionLab.Readiness.REQUIRES_VEHICLE_VERIFICATION, p.readiness);
        assertTrue(p.limitation.contains("Mode-04"));
        assertTrue(p.prerequisites.stream().anyMatch(s -> s.contains("Freeze-Frame")));
        assertTrue(p.prerequisites.stream().anyMatch(s -> s.contains("Readiness")));
    }

    @Test public void roofActionsRequireSeparateHardwareVerification() {
        for (ServiceActionLab.Action action : new ServiceActionLab.Action[] {
                ServiceActionLab.Action.SUNROOF_OPEN, ServiceActionLab.Action.SUNROOF_CLOSE
        }) {
            ServiceActionLab.Proposal p = ServiceActionLab.describe(action);
            assertEquals(ServiceActionLab.Readiness.RESEARCH_ONLY, p.readiness);
            assertTrue(p.prerequisites.stream().anyMatch(s -> s.contains("Einklemmschutz")));
        }
    }

    @Test public void normalGlassRoofUsesN70RatherThanPanoramaControlUnit() {
        ServiceActionLab.RoofConfiguration roof = ServiceActionLab.currentVehicleRoof();
        assertTrue(roof.type.contains("Schiebe-/Hebedach"));
        assertEquals("414", roof.expectedSaCode);
        assertEquals("N70", roof.expectedController);
        assertEquals("M12m1", roof.expectedMotor);
        assertFalse("SA-Code noch nicht gegen konkrete Datenkarte bestätigt",
                roof.confirmedFromVehicleDataCard);
        assertFalse(ServiceActionLab.canExecuteOnVehicle(ServiceActionLab.Action.SUNROOF_OPEN));
        assertFalse(ServiceActionLab.canExecuteOnVehicle(ServiceActionLab.Action.SUNROOF_CLOSE));
        for (ServiceActionLab.Action action : new ServiceActionLab.Action[]{
                ServiceActionLab.Action.SUNROOF_OPEN, ServiceActionLab.Action.SUNROOF_CLOSE}) {
            ServiceActionLab.Proposal p = ServiceActionLab.describe(action);
            assertTrue(p.prerequisites.stream().anyMatch(t -> t.contains("N70")));
            assertTrue(p.prerequisites.stream().anyMatch(t -> t.contains("414")));
            assertTrue(p.limitation.contains("A98 nicht relevant"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingAction() {
        ServiceActionLab.describe(null);
    }
}
