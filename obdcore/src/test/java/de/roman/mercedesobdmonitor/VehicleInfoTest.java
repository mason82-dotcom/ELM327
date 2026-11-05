package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Mode 09 (FIN, CALID, CVN, IUPR), PID 01 41 und deren Einbindung in den HU/AU-Check. */
public class VehicleInfoTest {
    private static final String VIN = "WDD2040561A345678";

    private static String hex(String ascii) {
        StringBuilder sb = new StringBuilder();
        for (byte b : ascii.getBytes(StandardCharsets.US_ASCII)) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** 49 02 01 + 17 Byte als ISO-TP-Multiframe (20 Byte = 0x014). */
    private static final String VIN_CAN = "014\r0:490201" + hex("WDD") + "\r1:" + hex("2040561")
            + "\r2:" + hex("A345678") + "\r";

    @Test public void vinCanMultiFrame() {
        assertEquals(VIN, VehicleInfo.vin(VIN_CAN, true));
        assertEquals(VIN, VehicleInfo.vin(VIN_CAN, null));
    }

    @Test public void vinLegacyFrames() {
        // 3 Füllbytes + 17 Zeichen in 5 Frames à 4 Byte, absichtlich ungeordnet.
        String padded = "\0\0\0" + VIN;
        StringBuilder raw = new StringBuilder();
        int[] order = {2, 1, 3, 5, 4};
        for (int n : order) {
            raw.append(String.format("49 02 %02X ", n)).append(hex(padded.substring((n - 1) * 4, n * 4))).append('\r');
        }
        assertEquals(VIN, VehicleInfo.vin(raw.toString(), false));
        assertEquals(VIN, VehicleInfo.vin(raw.toString(), null));
    }

    @Test public void vinMissing() {
        assertNull(VehicleInfo.vin("NO DATA", true));
        assertNull(VehicleInfo.vin(null, true));
    }

    @Test public void calibrationIdsAndCvns() {
        // 49 04 01 + 16 Byte (ID mit 0x00 aufgefüllt) = 19 Byte.
        String cal = "013\r0:490401323732\r1:39303330373031\r2:000000000000CC\r";
        assertEquals(List.of("2729030701"), VehicleInfo.calibrationIds(cal, true));
        assertEquals(List.of("AABBCCDD", "11223344"), VehicleInfo.cvns("00B\r0:490602AABBCC\r1:DD11223344\r", true));
    }

    /** 49 08 10 + 16 Wörter: OBDCOND, IGNCNTR, dann 7 Paare. */
    private static final String IUPR = "023\r0:490810006400\r1:C8002800500002\r2:00500030005000\r"
            + "3:30005000000000\r4:00000000001400\r5:20CCCCCCCCCCCC\r";

    @Test public void iuprSparkIgnition() {
        VehicleInfo.IuprData d = VehicleInfo.iupr(IUPR, true);
        assertNotNull(d);
        assertEquals(100, d.generalDenominator);
        assertEquals(200, d.ignitionCycles);
        assertEquals("Katalysator Bank 1", d.monitors.get(0).name);
        assertEquals(40, d.monitors.get(0).completions);
        assertEquals(80, d.monitors.get(0).conditions);
        assertEquals(0.5, d.monitors.get(0).ratio(), 1e-9);
        assertEquals(0.025, d.monitors.get(1).ratio(), 1e-9);
        assertEquals("Tankentlüftung (EVAP)", d.monitors.get(6).name);
        assertEquals(20, d.monitors.get(6).completions);
        assertEquals(7, d.monitors.size());
        assertNull(VehicleInfo.iupr("NO DATA", true));
    }

    @Test public void driveCycleIgnoresStatusByte() {
        // A = 0x83 ist in 01 41 reserviert; Kat aktiviert und offen, Lambdasonden aktiviert und fertig.
        Readiness r = Readiness.parseDriveCycle("41 41 83 07 21 01");
        assertNotNull(r);
        assertEquals(false, r.milOn);
        assertEquals(0, r.dtcCount);
        assertEquals("4/5 fertig · offen: Katalysator", r.summary());
        assertNull(Readiness.parseDriveCycle("41 01 00 07 65 00"));
    }

    private static final class Fake implements InspectionReader.Transport {
        final Map<String, String> answers = new HashMap<>();

        @Override public String send(String cmd, int timeoutMs) throws IOException {
            String a = answers.getOrDefault(cmd, "NO DATA");
            if ("TIMEOUT".equals(a)) throw new java.net.SocketTimeoutException(cmd);
            return a;
        }

        @Override public boolean resync() { return true; }
    }

    private static Fake car() {
        Fake f = new Fake();
        f.answers.put("0101", "41 01 00 07 65 00");
        f.answers.put("03", "43 00");
        f.answers.put("07", "47 00");
        f.answers.put("0A", "4A 00");
        f.answers.put("0121", "41 21 00 00");
        f.answers.put("0130", "41 30 FF");
        f.answers.put("0131", "41 31 13 88");
        f.answers.put("0141", "41 41 00 07 21 01");
        f.answers.put("0902", VIN_CAN);
        f.answers.put("0906", "49 06 01 AA BB CC DD");
        f.answers.put("0908", IUPR);
        return f;
    }

    @Test public void inspectionReportShowsExtras() throws IOException {
        InspectionCheck.Input in = new InspectionReader(car(), true).read(p -> false);
        assertEquals(VIN, in.vin);
        assertNotNull(in.driveCycle);
        assertEquals(List.of("AABBCCDD"), in.cvns);
        assertTrue(in.calibrationIds.isEmpty());       // 09 04 = NO DATA → nicht unterstützt, still
        assertTrue(in.unavailable.toString(), in.unavailable.isEmpty());
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in)); // Zusatzdaten ändern das Urteil nicht

        String report = InspectionCheck.report(in);
        assertTrue(report, report.contains("FIN: " + VIN));
        assertTrue(report, report.contains("In dieser Fahrt (01 41): 4/5 fertig · offen: Katalysator"));
        assertTrue(report, report.contains("Katalysator Bank 1: 40 von 80 (0,50)"));
        assertTrue(report, report.contains("CVN: AABBCCDD"));
        assertTrue(report, report.contains("Katalysator Bank 2 läuft im Alltag selten (2 von 80"));
    }

    @Test public void extraQueryTimeoutIsListedButHarmless() throws IOException {
        Fake f = car();
        f.answers.put("0902", "TIMEOUT");
        InspectionCheck.Input in = new InspectionReader(f, true).read(p -> false);
        assertNull(in.vin);
        assertTrue(in.unavailable.toString(), in.unavailable.contains("FIN (09 02)"));
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in));
    }
}
