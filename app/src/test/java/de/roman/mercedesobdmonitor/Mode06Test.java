package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class Mode06Test {
    @Test public void commandFormat() {
        assertEquals("06A2", Mode06.command(0xA2));
        assertTrue(CommandSafety.isAllowed(Mode06.command(0xA2)));
        assertTrue(CommandSafety.isAllowed(Mode06.command(0xA0)));
    }

    @Test public void supportedMidsBitmap() {
        // 46 A0 7E000000 → Bits für A2..A7 (Bitmaske 0111 1110)
        Set<Integer> mids = Mode06.supportedMids("46 A0 7E 00 00 00", 0xA0);
        assertEquals(6, mids.size());
        assertTrue(mids.contains(0xA2));
        assertTrue(mids.contains(0xA7));
        assertFalse(mids.contains(0xA1));
    }

    @Test public void parsesMultiframeMisfireRecord() {
        // 46 | A2 0B 24 0005 0000 FFFF | A2 0C 24 0012 0000 FFFF = 19 Byte → ISO-TP
        String raw = "013\r0:46A20B240005\r1:0000FFFFA20C24\r2:00120000FFFF";
        Mode06.Misfire m = Mode06.misfire(raw, 1);
        assertNotNull(m);
        assertEquals(Integer.valueOf(5), m.ewma);
        assertEquals(Integer.valueOf(0x12), m.currentCycle);
    }

    @Test public void wrongMidIsIgnored() {
        String raw = "013\r0:46A30B240005\r1:0000FFFFA30C24\r2:00120000FFFF";
        assertNull(Mode06.misfire(raw, 1));
        assertNotNull(Mode06.misfire(raw, 2));
    }

    @Test public void m272BankMapping() {
        assertEquals(1, Mode06.m272Bank(1));
        assertEquals(1, Mode06.m272Bank(3));
        assertEquals(2, Mode06.m272Bank(4));
        assertEquals(2, Mode06.m272Bank(6));
    }

    @Test public void reportFlagsWorstCylinderAndBank() {
        List<Mode06.Misfire> list = new ArrayList<>();
        list.add(Mode06.misfire("013\r0:46A20B240000\r1:0000FFFFA20C24\r2:00000000FFFF", 1));
        list.add(Mode06.misfire("013\r0:46A50B240030\r1:0000FFFFA50C24\r2:00400000FFFF", 4));
        String rep = Mode06.misfireReport(list);
        assertTrue(rep, rep.contains("Zyl. 4 (Bank 2): EWMA 48 · Zyklus 64"));
        assertTrue(rep, rep.contains("Bank 1: 0 · Bank 2: 64"));
        assertTrue(rep, rep.contains("auffälligster Zylinder 4"));
    }

    @Test public void emptyReport() {
        assertTrue(Mode06.misfireReport(new ArrayList<>()).contains("keine zylinderbezogenen"));
    }
}
