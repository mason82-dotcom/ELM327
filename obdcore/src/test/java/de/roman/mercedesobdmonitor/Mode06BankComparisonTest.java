package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Bankvergleich in der Mode-06-Übersicht (Anlass: Bank-1-Muster der Trim-Karte am C350). */
public class Mode06BankComparisonTest {
    private static Mode06Overview.Test eval(int mid, int tid, int uasid, int value, int min, int max) {
        return Mode06Overview.evaluate(new Mode06.TestResult(mid, tid, uasid, value, min, max));
    }

    @Test
    public void bank1PreCatSensorCloserToLimitIsFlaggedWithHint() {
        List<Mode06Overview.Test> tests = List.of(
                eval(0x01, 0x05, 0x10, 160, 0, 200),   // B1S1 Schaltzeit 160 ms ≤ 200 → Reserve 20 %
                eval(0x05, 0x05, 0x10, 80, 0, 200),    // B2S1 80 ms → Reserve 60 %
                eval(0x21, 0x81, 0x04, 300, 0, 1000),  // Kat B1 Reserve 70 %
                eval(0x22, 0x81, 0x04, 320, 0, 1000),  // Kat B2 Reserve 68 %
                eval(0x02, 0x07, 0x0B, 100, 0, 0xFFFF)); // nur Bank 1, ohne Partner
        List<String> lines = Mode06Overview.bankComparison(tests);
        assertEquals("  Deutlich schwächer: Bank 1 in 1, Bank 2 in 0 von 2 Tests", lines.get(0));
        assertTrue(lines.toString(), lines.get(1).startsWith("  ⚠ Lambdasonde Sonde 1 · Schaltzeit fett→mager: B1 160 ms (20 %) · B2 80 ms (60 %) → Bank 1 näher"));
        assertTrue(lines.get(2), lines.get(2).startsWith("  · Katalysator · "));
        assertTrue(lines.get(3), lines.get(3).contains("Sonde B1S1"));
        String report = Mode06Overview.report(tests);
        assertTrue(report, report.contains("Bankvergleich (M272"));
    }

    @Test
    public void similarBanksAndMissingPairs() {
        List<String> same = Mode06Overview.bankComparison(List.of(
                eval(0x01, 0x05, 0x10, 100, 0, 200), eval(0x05, 0x05, 0x10, 110, 0, 200)));
        assertEquals("  Bänke in allen 1 vergleichbaren Tests ähnlich (Unterschied < 25 Punkte)", same.get(0));
        assertEquals(2, same.size());
        // Bank 2 schwächer: markiert, aber ohne B1S1-Hinweis
        List<String> b2 = Mode06Overview.bankComparison(List.of(
                eval(0x01, 0x05, 0x10, 50, 0, 200), eval(0x05, 0x05, 0x10, 190, 0, 200)));
        assertTrue(b2.get(1), b2.get(1).endsWith("Bank 2 näher an der Grenze"));
        assertFalse(b2.toString(), b2.toString().contains("Sonde B1S1"));
        // unterschiedliche Skalierung, nicht gelaufen oder ohne Partner: kein Vergleich
        assertTrue(Mode06Overview.bankComparison(List.of(
                eval(0x01, 0x05, 0x10, 100, 0, 200), eval(0x05, 0x05, 0x11, 1, 0, 2))).isEmpty());
        assertTrue(Mode06Overview.bankComparison(List.of(
                eval(0x01, 0x05, 0x10, 0, 0, 0), eval(0x05, 0x05, 0x10, 100, 0, 200))).isEmpty());
        assertFalse(Mode06Overview.report(List.of(eval(0x01, 0x05, 0x10, 100, 0, 200))).contains("Bankvergleich"));
    }
}
