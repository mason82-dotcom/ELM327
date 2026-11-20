package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;
import org.junit.Test;

public class RoofLabTest {
    @Test public void localStateTransitionsNeverEnableVehicleActuators() {
        RoofLab roof = new RoofLab();
        assertFalse(roof.supportsVehicleActuation());
        assertEquals(RoofLab.Position.GESCHLOSSEN, roof.position());
        assertTrue(roof.execute(RoofLab.Action.OEFFNEN).contains("SIMULATION"));
        assertEquals(RoofLab.Position.OFFEN, roof.position());
        roof.setObstacle(true);
        assertTrue(roof.execute(RoofLab.Action.SCHLIESSEN).contains("verweigert"));
        assertEquals(RoofLab.Position.OFFEN, roof.position());
        roof.setObstacle(false);
        assertTrue(roof.execute(RoofLab.Action.SCHLIESSEN).contains("SIMULATION"));
        assertEquals(RoofLab.Position.GESCHLOSSEN, roof.position());
    }

    @Test public void stopAndNullHaveNoVehicleSideEffects() {
        RoofLab roof = new RoofLab();
        assertTrue(roof.execute(RoofLab.Action.STOPP).contains("keine Fahrzeuganforderung"));
        assertTrue(roof.execute(null).contains("SIMULATION"));
        assertFalse(roof.supportsVehicleActuation());
    }
}
