package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** DTC-Dekodierung für Mode 03/07/0A mit ATH0/ATS0 (wie in der App). */
public class ObdParserDtcTest {

    private static List<String> l(String... codes) {
        return Arrays.asList(codes);
    }

    // ---------- CAN (ISO 15765-4), explizit ----------

    @Test public void canSingleDtc() {
        assertEquals(l("P0171"), ObdParser.dtcs("43010171", 0x43, true));
    }

    @Test public void canTwoDtcs() {
        assertEquals(l("P0171", "P0174"), ObdParser.dtcs("4302017101 74", 0x43, true));
    }

    @Test public void canDtcContainingMarkerBytes() {
        // P0043 enthält "43" im Datenbereich – darf nicht als Kennung gelten.
        assertEquals(l("P0043"), ObdParser.dtcs("43010043", 0x43, true));
    }

    @Test public void canNoDtcs() {
        assertEquals(Collections.emptyList(), ObdParser.dtcs("4300", 0x43, true));
    }

    @Test public void canPaddingAfterCountIgnored() {
        // Manche Adapter liefern Padding-Bytes hinter den Nutzdaten.
        assertEquals(l("P0171"), ObdParser.dtcs("43010171AAAAAA", 0x43, true));
    }

    @Test public void canIsoTpMultiFrame() {
        String raw = "00A\r0:43040171017403\r1:00C1230000CCCC\r\r>";
        assertEquals(l("P0171", "P0174", "P0300", "U0123"),
                ObdParser.dtcs(raw, 0x43, true));
    }

    @Test public void canMultipleEcusDeduplicated() {
        String raw = "43010171\r4302017101A0\r";
        assertEquals(l("P0171", "P01A0"), ObdParser.dtcs(raw, 0x43, true));
    }

    @Test public void canPendingAndPermanentModes() {
        assertEquals(l("P0420"), ObdParser.dtcs("47010420", 0x47, true));
        assertEquals(l("P0420"), ObdParser.dtcs("4A010420", 0x4A, true));
    }

    @Test public void canWithSearchingPrefix() {
        assertEquals(l("P0171"), ObdParser.dtcs("SEARCHING...\r43 01 01 71\r", 0x43, true));
    }

    @Test public void canFamilies() {
        // C1234 = 52 34, B0001 = 80 01, U0100 = C1 00 (als ISO-TP-Multiframe)
        assertEquals(l("C1234", "B0001", "U0100"),
                ObdParser.dtcs("008\r0:430352348001\r1:C100CCCCCCCCCC", 0x43, true));
    }

    // ---------- Legacy (J1850 / ISO 9141 / KWP), explizit ----------

    @Test public void legacySingleFrame() {
        assertEquals(l("P0171"), ObdParser.dtcs("43017100000000", 0x43, false));
    }

    @Test public void legacyThreeDtcs() {
        assertEquals(l("P0171", "P0174", "P0300"),
                ObdParser.dtcs("43 01 71 01 74 03 00", 0x43, false));
    }

    @Test public void legacyMultipleFrames() {
        String raw = "43 01 71 01 74 03 00\r43 04 20 00 00 00 00\r";
        assertEquals(l("P0171", "P0174", "P0300", "P0420"), ObdParser.dtcs(raw, 0x43, false));
    }

    @Test public void legacyMarkerInsideDataNotSplit() {
        // P0043 als zweiter Code – "43" im Datenbereich.
        assertEquals(l("P0171", "P0043"),
                ObdParser.dtcs("43 01 71 00 43 00 00", 0x43, false));
    }

    // ---------- Automatische Erkennung (Protokoll unbekannt) ----------

    @Test public void autoDetectCanSingleFrame() {
        assertEquals(l("P0171"), ObdParser.dtcs("43010171", 0x43));
        assertEquals(l("P0171", "P0174"), ObdParser.dtcs("430201710174", 0x43));
    }

    @Test public void autoDetectCanMultiFrame() {
        assertEquals(l("P0171", "P0174", "P0300"),
                ObdParser.dtcs("008\r0:43030171017403\r1:00", 0x43));
    }

    @Test public void autoDetectLegacy() {
        assertEquals(l("P0171"), ObdParser.dtcs("43017100000000", 0x43));
    }

    @Test public void protocolDetection() {
        assertEquals(Boolean.TRUE, ObdParser.isCanProtocol("6"));
        assertEquals(Boolean.TRUE, ObdParser.isCanProtocol("A6"));
        assertEquals(Boolean.TRUE, ObdParser.isCanProtocol(" 9 "));
        assertEquals(Boolean.TRUE, ObdParser.isCanProtocol("A"));  // benutzerdef. CAN
        assertEquals(Boolean.FALSE, ObdParser.isCanProtocol("A3"));
        assertEquals(Boolean.FALSE, ObdParser.isCanProtocol("5"));
        assertEquals(null, ObdParser.isCanProtocol("0"));
        assertEquals(null, ObdParser.isCanProtocol("?"));
        assertEquals(null, ObdParser.isCanProtocol(null));
    }

    @Test public void nullAndNoData() {
        assertEquals(Collections.emptyList(), ObdParser.dtcs(null, 0x43, true));
        assertEquals(Collections.emptyList(), ObdParser.dtcs("NO DATA", 0x43, true));
        assertEquals(Collections.emptyList(), ObdParser.dtcs("NO DATA", 0x43));
    }
}
