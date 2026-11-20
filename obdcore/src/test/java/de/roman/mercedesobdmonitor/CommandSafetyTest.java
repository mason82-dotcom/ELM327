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
        assertFalse(CommandSafety.isAllowed("ATSH7E2"));   // ATSH7E0 ist seit ADR-004 als exaktes Tupel frei
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
        for (String cmd : new String[] {"ATMA", "AT MA", "ATMR30D", "ATMT30", "ATMA;0100", "ATCRA30D", "ATCF30D",
                "ATCM7FF", "ATCAF", "ATCAF2", "ATCSM0"}) {
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

    /** Das Terminal nennt den passenden Grund: ADR-002 für die Formatwechsel, ADR-001 für die ATF-Sequenz. */
    @Test public void terminalNamesTheMatchingReason() {
        assertTrue(CommandSafety.terminalBlockedReason("ATCAF0").contains("ADR-002"));
        assertTrue(CommandSafety.terminalBlockedReason("at caf1").contains("ADR-002"));
        assertTrue(CommandSafety.terminalBlockedReason("ATSH7E1").contains("ADR-001"));
        assertTrue(CommandSafety.terminalBlockedReason("ATMA").contains("ADR-002"));
    }

    /** ADR-004: exakte Tupel, headerabhängig, nie im Terminal; Rückweg nur über 7DF. */
    @Test public void adr004OnlyExactReadsOnTheRightHeader() {
        String obd = CommandSafety.DEFAULT_HEADER;
        assertTrue(CommandSafety.isAllowed("ATSH7E0", obd));
        assertFalse(CommandSafety.isAllowed("1A86", obd));                 // nie funktional an alle
        assertFalse(CommandSafety.isAllowed("1802FF00", obd));
        String engine = CommandSafety.headerAfter("ATSH7E0", obd);
        assertEquals("7E0", engine);
        assertTrue(CommandSafety.isAllowed("1A86", engine));
        assertFalse(CommandSafety.isAllowed("1802FF00", engine));          // Fehlerspeicher nur Getriebe
        assertFalse(CommandSafety.isAllowed("2130", engine));
        assertFalse(CommandSafety.isAllowed("ATSH7E1", engine));           // erst zurück auf 7DF
        assertFalse(CommandSafety.isAllowed("010C", engine));
        assertTrue(CommandSafety.isAllowed("ATSH7DF", engine));
        String tcu = CommandSafety.headerAfter("ATSH7E1", obd);
        assertTrue(CommandSafety.isAllowed("1A86", tcu));
        assertTrue(CommandSafety.isAllowed("1802FF00", tcu));
        assertTrue(CommandSafety.changesHeader("ATSH7E0"));
        for (String cmd : new String[] {"ATSH7E0", "1A86", "1802FF00"}) {
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
}
