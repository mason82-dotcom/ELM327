package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import org.junit.Test;

public class TrimHistoryTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long NOV_07 = 1794064653000L;   // 07.11.2026 16:17:33 MEZ
    private static final long DAY = 86_400_000L;

    /** Trim-Karte mit Leerlauf- und Teillastproben (Gesamtkorrektur je Bank). */
    private static EngineAnalysis.TrimMap map(double idle1, double idle2, double part1, double part2) {
        EngineAnalysis.TrimMap m = new EngineAnalysis.TrimMap();
        for (int i = 0; i < EngineAnalysis.TrimMap.MIN_SAMPLES; i++) {
            m.add(0.0, idle1, 0.0, idle2, 2, 90.0, 680.0, 0.0, 25.0);
            m.add(0.0, part1, 0.0, part2, 2, 90.0, 1600.0, 50.0, 30.0);
        }
        return m;
    }

    @Test
    public void snapshotNeedsData() {
        assertNull(TrimHistory.snapshot(NOV_07, new EngineAnalysis.TrimMap()));
        TrimHistory.Entry e = TrimHistory.snapshot(NOV_07, map(11.7, 4.3, 10.2, 3.1));
        assertEquals(11.7, e.mean(1, EngineAnalysis.TrimMap.Band.IDLE), 1e-9);
        assertNull(e.mean(1, EngineAnalysis.TrimMap.Band.HIGH));
        assertEquals(7.25, e.bankDifference(), 1e-9);
    }

    @Test
    public void upsertReplacesSameConnectionAndKeepsNewest() {
        TrimHistory h = new TrimHistory();
        h.upsert(TrimHistory.snapshot(NOV_07, map(1, 1, 1, 1)));
        h.upsert(TrimHistory.snapshot(NOV_07, map(9, 2, 9, 2)));     // gleiche Verbindung, mehr Proben
        assertEquals(1, h.entries().size());
        assertEquals(7.0, h.entries().get(0).bankDifference(), 1e-9);
        for (int i = 1; i <= TrimHistory.MAX_ENTRIES + 3; i++) h.upsert(TrimHistory.snapshot(NOV_07 + i * DAY, map(9, 2, 9, 2)));
        assertEquals(TrimHistory.MAX_ENTRIES, h.entries().size());
        assertEquals(NOV_07 + 4 * DAY, h.entries().get(0).startedAtMs);
        h.upsert(null);
        assertEquals(TrimHistory.MAX_ENTRIES, h.entries().size());
    }

    @Test
    public void serializeRoundTripAndTolerantParse() {
        TrimHistory h = new TrimHistory();
        h.upsert(TrimHistory.snapshot(NOV_07, map(9.3, 2.5, 12.0, 3.8)));
        h.upsert(TrimHistory.snapshot(NOV_07 + 12 * DAY, map(11.7, 4.3, 10.2, 3.1)));
        String text = h.serialize();
        TrimHistory back = TrimHistory.parse(text + "kaputt\n1;2;3\n" + "5;x;;;;;;1;1;1\n");
        assertEquals(text, back.serialize());
        assertEquals(0, TrimHistory.parse(null).entries().size());
        assertEquals(0, TrimHistory.parse("").entries().size());
    }

    @Test
    public void tableNewestFirst() {
        TrimHistory h = new TrimHistory();
        assertTrue(h.table(BERLIN, 5).startsWith("noch keine"));
        h.upsert(TrimHistory.snapshot(NOV_07, map(9.3, 2.5, 12.0, 3.8)));
        h.upsert(TrimHistory.snapshot(NOV_07 + 12 * DAY, map(11.7, 4.3, 10.2, 3.1)));
        String[] rows = h.table(BERLIN, 5).split("\n");
        assertEquals(2, rows.length);
        assertEquals("19.11. 16:17  B1 +11,7/+10,2/– · B2 +4,3/+3,1/– · Δ +7,2", rows[0]);
        assertTrue(rows[1], rows[1].startsWith("07.11. 16:17  B1 +9,3/+12,0/–"));
        assertEquals(1, h.table(BERLIN, 1).split("\n").length);
    }

    @Test
    public void verdictPersistentBankAndTrend() {
        TrimHistory h = new TrimHistory();
        assertTrue(h.verdict().startsWith("sammelt"));
        h.upsert(TrimHistory.snapshot(NOV_07, map(9.3, 2.5, 12.0, 3.8)));
        assertTrue(h.verdict(), h.verdict().startsWith("erst eine Verbindung"));
        h.upsert(TrimHistory.snapshot(NOV_07 + 12 * DAY, map(11.7, 4.3, 10.2, 3.1)));
        assertTrue(h.verdict(), h.verdict().startsWith("Bank 1 in allen 2 Verbindungen stärker angefettet"));
        assertTrue(h.verdict(), h.verdict().contains("höchste Korrektur zuletzt 11,7 %"));
        h.upsert(TrimHistory.snapshot(NOV_07 + 13 * DAY, map(13, 4, 12, 3)));
        assertTrue(h.verdict(), h.verdict().contains("Unterschied stabil"));
        h.upsert(TrimHistory.snapshot(NOV_07 + 20 * DAY, map(17, 4, 16, 3)));
        h.upsert(TrimHistory.snapshot(NOV_07 + 21 * DAY, map(18, 4, 17, 3)));
        h.upsert(TrimHistory.snapshot(NOV_07 + 22 * DAY, map(18, 4, 17, 3)));
        assertTrue(h.verdict(), h.verdict().contains("Unterschied wächst"));
    }

    @Test
    public void verdictBanksTogether() {
        TrimHistory h = new TrimHistory();
        h.upsert(TrimHistory.snapshot(NOV_07, map(2, 1, 3, 2)));
        h.upsert(TrimHistory.snapshot(NOV_07 + DAY, map(9, 2, 9, 2)));
        assertTrue(h.verdict(), h.verdict().startsWith("Bankunterschied in 1 von 2 Verbindungen"));
        TrimHistory same = new TrimHistory();
        same.upsert(TrimHistory.snapshot(NOV_07, map(2, 1, 3, 2)));
        same.upsert(TrimHistory.snapshot(NOV_07 + DAY, map(1, 2, 2, 3)));
        assertTrue(same.verdict(), same.verdict().startsWith("Bänke in allen Verbindungen gleichlaufend"));
    }
}
