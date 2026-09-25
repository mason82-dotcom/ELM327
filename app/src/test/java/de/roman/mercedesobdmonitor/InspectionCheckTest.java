package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class InspectionCheckTest {
    private static InspectionCheck.Input base(String readiness) {
        InspectionCheck.Input in = new InspectionCheck.Input();
        in.readiness = Readiness.parse(readiness);
        in.permanentSupported = true;
        return in;
    }

    @Test public void readyWhenAllCompleteAndNoCodes() {
        InspectionCheck.Input in = base("41 01 00 07 65 00");
        in.kmSinceCleared = 4000;
        in.warmupsSinceCleared = 200;
        assertEquals(InspectionCheck.Verdict.READY, InspectionCheck.verdict(in));
        assertTrue(InspectionCheck.report(in).contains("bereit"));
    }

    @Test public void notReadyWithMil() {
        InspectionCheck.Input in = base("41 01 81 07 65 00");
        in.stored = Arrays.asList("P0171");
        assertEquals(InspectionCheck.Verdict.NOT_READY, InspectionCheck.verdict(in));
    }

    @Test public void notReadyWithPermanentOnly() {
        InspectionCheck.Input in = base("41 01 00 07 65 00");
        in.permanent = Arrays.asList("P0420");
        assertEquals(InspectionCheck.Verdict.NOT_READY, InspectionCheck.verdict(in));
    }

    @Test public void uncertainWithIncompleteMonitorsAndRecentClear() {
        InspectionCheck.Input in = base("41 01 00 07 65 01");
        in.kmSinceCleared = 12;
        in.warmupsSinceCleared = 2;
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
        assertTrue(InspectionCheck.recentlyCleared(in));
        String rep = InspectionCheck.report(in);
        assertTrue(rep, rep.contains("kürzlich gelöscht"));
        assertTrue(rep, rep.contains("Katalysator – nicht abgeschlossen"));
    }

    @Test public void uncertainWithoutReadiness() {
        InspectionCheck.Input in = new InspectionCheck.Input();
        assertEquals(InspectionCheck.Verdict.UNCERTAIN, InspectionCheck.verdict(in));
    }

    @Test public void countersAndFreezeFrame() {
        assertEquals(Integer.valueOf(0x0102), InspectionCheck.word(ObdParser.mode01Data("41 31 01 02", 0x31, 2)));
        assertEquals(Integer.valueOf(0x28), InspectionCheck.byteValue(ObdParser.mode01Data("41 30 28", 0x30, 1)));
        assertEquals("P0171", InspectionCheck.freezeFrameDtc("42 02 00 01 71"));
        assertNull(InspectionCheck.freezeFrameDtc("42 02 00 00 00"));
        assertNull(InspectionCheck.freezeFrameDtc("NO DATA"));
    }

    @Test public void freezeFrameValueUsesMode01Formula() {
        // 42 0C 00 1A F8 → 1726 U/min
        ObdPid rpm = null;
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == 0x0C) rpm = p;
        assertNotNull(rpm);
        assertEquals(1726.0, rpm.parseFreezeFrame("42 0C 00 1A F8"), 0.01);
        assertTrue(CommandSafety.isAllowed("020C00"));
        assertTrue(CommandSafety.isAllowed("020200"));
    }
}
