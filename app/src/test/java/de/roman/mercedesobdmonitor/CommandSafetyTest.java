package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CommandSafetyTest {
    @Test public void allowsReadOnlyObdServices() {
        assertTrue(CommandSafety.isAllowed("010C"));
        assertTrue(CommandSafety.isAllowed("020C00"));
        assertTrue(CommandSafety.isAllowed("03"));
        assertTrue(CommandSafety.isAllowed("0600"));
        assertTrue(CommandSafety.isAllowed("07"));
        assertTrue(CommandSafety.isAllowed("0902"));
        assertTrue(CommandSafety.isAllowed("0A"));
    }

    @Test public void allowsOnlySafeAdapterSetup() {
        assertTrue(CommandSafety.isAllowed("ATE0"));
        assertTrue(CommandSafety.isAllowed("ATH0"));
        assertTrue(CommandSafety.isAllowed("ATSP0"));
        assertTrue(CommandSafety.isAllowed("ATST64"));
        assertTrue(CommandSafety.isAllowed("ATDPN"));
        assertTrue(CommandSafety.isAllowed("ATRV"));
    }

    @Test public void blocksVehicleWriteAndControlCommands() {
        assertFalse(CommandSafety.isAllowed("04"));
        assertFalse(CommandSafety.isAllowed("08"));
        assertFalse(CommandSafety.isAllowed("2E123400"));
        assertFalse(CommandSafety.isAllowed("2711"));
        assertFalse(CommandSafety.isAllowed("3101"));
        assertFalse(CommandSafety.isAllowed("3400"));
        assertFalse(CommandSafety.isAllowed("3E00"));
    }

    @Test public void blocksRawAddressingAndCommandStacking() {
        assertFalse(CommandSafety.isAllowed("ATSH7E0"));
        assertFalse(CommandSafety.isAllowed("ATCRA7E8"));
        assertFalse(CommandSafety.isAllowed("ATCEA18"));
        assertFalse(CommandSafety.isAllowed("010C\r04"));
        assertFalse(CommandSafety.isAllowed("010C;04"));
    }
}
