package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
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
        assertFalse(CommandSafety.isAllowed("ATSH7E2"));
        assertFalse(CommandSafety.isAllowed("ATCRA7E8"));
        assertFalse(CommandSafety.isAllowed("ATCEA18"));
        assertFalse(CommandSafety.isAllowed("010C\r04"));
        assertFalse(CommandSafety.isAllowed("010C;04"));
    }

    @Test public void allowsOnlyStandardProtocolSelection() {
        assertTrue(CommandSafety.isAllowed("ATSP6"));
        assertTrue(CommandSafety.isAllowed("ATSP1"));
        assertFalse(CommandSafety.isAllowed("ATSPA"));
        assertFalse(CommandSafety.isAllowed("ATSPB"));
        assertFalse(CommandSafety.isAllowed("ATSP66"));
        assertFalse(CommandSafety.isAllowed("ATPB0101"));
    }

    /** ADR-002: ATMA läuft nie über die normale Befehlsschnittstelle, Formatwechsel nicht im Terminal. */
    @Test public void canMonitorStaysOutsideNormalWhitelist() {
        for (String cmd : new String[] {"ATMA", "AT MA", "ATMR30D", "ATMT30", "ATMA;0100", "ATCRA7E8", "ATCRA7DF",
                "ATCRA30", "ATCRA30DX", "ATCRAXXX", "ATCRA 1FF", "ATCF30D", "ATCM7FF", "ATCAF", "ATCAF2", "ATCSM0"}) {
            assertFalse(cmd, CommandSafety.isAllowed(cmd));
            assertFalse(cmd, CommandSafety.isAllowedInTerminal(cmd));
        }
        assertTrue(CommandSafety.isAllowed(CommandSafety.RAW_FORMAT));
        assertTrue(CommandSafety.isAllowed(CommandSafety.AUTO_FORMAT));
        assertFalse(CommandSafety.isAllowedInTerminal("ATCAF0"));
        assertFalse(CommandSafety.isAllowedInTerminal("ATCAF1"));
        assertFalse(CommandSafety.changesHeader("ATCAF0"));
        assertTrue(CommandSafety.blockedReason("ATMA").contains("ADR-002"));
    }

    /** ADR-002 Option C: genau die Filter der bekannten W204-Botschaften und ihr Rücksetzen, nie im Terminal. */
    @Test public void receiveFiltersOnlyForKnownFramesAndNeverInTerminal() {
        for (String cmd : new String[] {"ATCRA30D", "ATCRA105", "ATCRA2F1", "ATCRA0F3", "ATCRA19F", "ATCRA", "at cra 30d"}) {
            assertTrue(cmd, CommandSafety.isAllowed(cmd));
            assertTrue(cmd, CommandSafety.isAllowed(cmd, CommandSafety.DEFAULT_HEADER));
            assertFalse(cmd, CommandSafety.isAllowedInTerminal(cmd));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, "7E1"));      // nie auf dem Getriebe-Header
            assertTrue(cmd, CommandSafety.terminalBlockedReason(cmd).contains("ADR-002"));
        }
        // Eine neue Kennung in CanMonitor erweitert die Freigabe: absichtlich nur mit Anpassung dieses Tests
        assertEquals(java.util.Set.of(0x30D, 0x105, 0x2F1, 0x0F3, 0x19F), CanMonitor.W204_POWERTRAIN.keySet());
        assertTrue(LinkPolicy.reconnectInit("A6").contains(CommandSafety.RECEIVE_FILTER_RESET));
    }

    /** Das Terminal nennt den passenden Grund: ADR-002 für die Formatwechsel, ADR-001 für die ATF-Sequenz. */
    @Test public void terminalNamesTheMatchingReason() {
        assertTrue(CommandSafety.terminalBlockedReason("ATCAF0").contains("ADR-002"));
        assertTrue(CommandSafety.terminalBlockedReason("at caf1").contains("ADR-002"));
        assertTrue(CommandSafety.terminalBlockedReason("ATSH7E1").contains("ADR-001"));
        assertTrue(CommandSafety.terminalBlockedReason("ATMA").contains("ADR-002"));
    }

    /** ADR-004: nur Getriebe (7E1), exakte Tupel, nie im Terminal; Motor-Teil (7E0) seit 21.11.2026 verworfen. */
    @Test public void adr004OnlyExactReadsOnTheTransmission() {
        String obd = CommandSafety.DEFAULT_HEADER;
        assertFalse("7E0 nicht mehr freigegeben", CommandSafety.isAllowed("ATSH7E0", obd));
        assertFalse(CommandSafety.isAllowed("ATSH7E0"));
        assertFalse(CommandSafety.changesHeader("ATSH7E0"));
        assertFalse(CommandSafety.isAllowed("1A86", obd));                 // nie funktional an alle
        assertFalse(CommandSafety.isAllowed("1802FF00", obd));
        assertFalse(CommandSafety.isAllowed("1A86", "7E0"));
        String tcu = CommandSafety.headerAfter("ATSH7E1", obd);
        assertTrue(CommandSafety.isAllowed("1A86", tcu));
        assertTrue(CommandSafety.isAllowed("1802FF00", tcu));
        for (String cmd : new String[] {"1A86", "1802FF00"}) {
            assertFalse(cmd, CommandSafety.isAllowedInTerminal(cmd));
            assertTrue(cmd, CommandSafety.terminalBlockedReason(cmd).contains("ADR-004"));
        }
        // keine Nachbarn: andere Identifier, Statusvarianten, Löschen, Sitzungen
        for (String cmd : new String[] {"1A87", "1A90", "1A", "1800FF00", "1803FF00", "1802FF", "14FF00", "1092",
                "1081", "3E01", "2701", "3101", "3B90", "ATSH7E2", "ATSH7E8", "ATSH6E0"}) {
            assertFalse(cmd, CommandSafety.isAllowed(cmd));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, tcu));
        }
    }

    /** UDS ReadDataByIdentifier (0x22) ist nicht freigegeben: der C350 spricht KWP2000, Gegenstück ist 0x21. */
    @Test public void udsReadDataByIdentifierStaysBlocked() {
        for (String cmd : new String[] {"22F190", "22F186", "220100", "22"}) {
            assertFalse(cmd, CommandSafety.isAllowed(cmd));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, "7E1"));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, "7E0"));
        }
    }

    /** ADR-007 verworfen (21.11.2026): keine lokale Kennung an 7E0/7DF, an 7E1 nur 21 30 (ADR-001). */
    @Test public void adr007LocalIdsAreGone() {
        for (int id = 0x01; id <= 0xFE; id++) {
            String cmd = String.format(java.util.Locale.US, "21%02X", id);
            assertFalse(cmd, CommandSafety.isAllowed(cmd, "7E0"));
            assertFalse(cmd, CommandSafety.isAllowed(cmd, CommandSafety.DEFAULT_HEADER));
            assertEquals(cmd, id == 0x30, CommandSafety.isAllowed(cmd, "7E1"));
            assertFalse(cmd, CommandSafety.isAllowedInTerminal(cmd));
        }
    }
}
