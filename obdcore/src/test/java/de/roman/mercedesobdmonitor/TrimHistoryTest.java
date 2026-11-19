package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import org.junit.Test;

public class TrimHistoryTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long NOV_07 = 1794064653000L;   // 07.11.2026 16:17:33 MEZ
    private static final long MIN = 60_000L;
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

    /** Fahrt von 15 min ab {@code start}. */
    private static TrimHistory.Entry trip(long start, double idle1, double idle2, double part1, double part2) {
        return TrimHistory.snapshot(start, start + 15 * MIN, map(idle1, idle2, part1, part2));
    }

    @Test
    public void snapshotNeedsData() {
        assertNull(TrimHistory.snapshot(NOV_07, NOV_07, new EngineAnalysis.TrimMap()));
        TrimHistory.Entry e = trip(NOV_07, 11.7, 4.3, 10.2, 3.1);
        assertEquals(11.7, e.mean(1, EngineAnalysis.TrimMap.Band.IDLE), 1e-9);
        assertNull(e.mean(1, EngineAnalysis.TrimMap.Band.HIGH));
        assertEquals(7.25, e.bankDifference(), 1e-9);
        assertFalse(e.isImported());
    }

    @Test
    public void upsertReplacesOnlyExactSameStart() {
        TrimHistory h = new TrimHistory();
        assertEquals(TrimHistory.Outcome.ADDED, h.upsert(trip(NOV_07, 1, 1, 1, 1)));
        assertEquals(TrimHistory.Outcome.REPLACED, h.upsert(trip(NOV_07, 9, 2, 9, 2)));   // gleiche Verbindung, mehr Proben
        assertEquals(1, h.entries().size());
        assertEquals(7.0, h.entries().get(0).bankDifference(), 1e-9);
        // Neuverbinden 90 s später (Review: früher per 2-min-Toleranz überschrieben) → eigener Eintrag
        assertEquals(TrimHistory.Outcome.ADDED, h.upsert(trip(NOV_07 + 90_000, 3, 3, 3, 3)));
        assertEquals(2, h.entries().size());
        assertEquals(7.0, h.entries().get(0).bankDifference(), 1e-9);
        assertEquals(TrimHistory.Outcome.NONE, h.upsert(null));
    }

    @Test
    public void keepsNewestAndReportsTooOld() {
        TrimHistory h = new TrimHistory();
        for (int i = 1; i <= TrimHistory.MAX_ENTRIES + 3; i++) h.upsert(trip(NOV_07 + i * DAY, 9, 2, 9, 2));
        assertEquals(TrimHistory.MAX_ENTRIES, h.entries().size());
        assertEquals(NOV_07 + 4 * DAY, h.entries().get(0).startedAtMs);
        // älter als alles Gespeicherte: nicht aufgenommen und so gemeldet (Review: Toast meldete „übernommen“)
        assertEquals(TrimHistory.Outcome.TOO_OLD, h.addImported(trip(NOV_07, 9, 2, 9, 2)));
        assertEquals(NOV_07 + 4 * DAY, h.entries().get(0).startedAtMs);
    }

    @Test
    public void importNeverDuplicatesAConnection() {
        TrimHistory h = new TrimHistory();
        h.upsert(TrimHistory.snapshot(NOV_07, NOV_07 + 30 * MIN, map(9, 2, 9, 2)));        // live, 30 min
        // Log begann 3 min nach dem Verbinden bzw. Teilstück nach einer Lücke → schon gespeichert
        assertEquals(TrimHistory.Outcome.ALREADY_PRESENT, h.addImported(trip(NOV_07 + 3 * MIN, 8, 2, 8, 2)));
        assertEquals(TrimHistory.Outcome.ALREADY_PRESENT, h.addImported(trip(NOV_07 + 20 * MIN, 8, 2, 8, 2)));
        assertEquals(1, h.entries().size());
        assertFalse(h.entries().get(0).isImported());
        // andere Fahrt: übernommen; erneuter Import ersetzt sie
        long other = NOV_07 + DAY;
        assertEquals(TrimHistory.Outcome.ADDED, h.addImported(trip(other, 8, 2, 8, 2)));
        assertEquals(TrimHistory.Outcome.REPLACED, h.addImported(trip(other, 8, 2, 8, 2)));
        assertEquals(2, h.entries().size());
        assertTrue(h.entries().get(1).isImported());
        // eine spätere Live-Aufzeichnung derselben Zeit ersetzt den Import (live gilt mehr)
        assertEquals(TrimHistory.Outcome.ADDED, h.upsert(TrimHistory.snapshot(other - MIN, other + 20 * MIN, map(9, 2, 9, 2))));
        assertEquals(2, h.entries().size());
        assertFalse(h.entries().get(1).isImported());
    }

    @Test
    public void serializeRoundTripTolerantAndNoMerging() {
        TrimHistory h = new TrimHistory();
        h.upsert(trip(NOV_07, 9.3, 2.5, 12.0, 3.8));
        h.addImported(trip(NOV_07 + 12 * DAY, 11.7, 4.3, 10.2, 3.1));
        String text = h.serialize();
        TrimHistory back = TrimHistory.parse(text + "kaputt\n1;2;3\n" + "5;6;2;;;;;;;1;1;1\n");
        assertEquals(text, back.serialize());
        assertTrue(back.entries().get(1).isImported());
        assertEquals(NOV_07 + 15 * MIN, back.entries().get(0).endedAtMs);
        assertEquals(0, TrimHistory.parse(null).entries().size());
        assertEquals(0, TrimHistory.parse("").entries().size());
        // Format 0.5.0 (ohne Ende/Herkunft), zwei Einträge 70 s auseinander: beide bleiben (Review: verschmolzen)
        TrimHistory old = TrimHistory.parse("1795108147305;12.0;10.0;;4.0;3.0;;20;20;0\n"
                + "1795108217305;11.0;10.0;;4.0;3.0;;20;20;0\n");
        assertEquals(2, old.entries().size());
        assertEquals(old.entries().get(0).startedAtMs, old.entries().get(0).endedAtMs);
        assertFalse(old.entries().get(0).isImported());
    }

    @Test
    public void tableNewestFirst() {
        TrimHistory h = new TrimHistory();
        assertTrue(h.table(BERLIN, 5).startsWith("noch keine"));
        h.upsert(trip(NOV_07, 9.3, 2.5, 12.0, 3.8));
        h.upsert(trip(NOV_07 + 12 * DAY, 11.7, 4.3, 10.2, 3.1));
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
        h.upsert(trip(NOV_07, 9.3, 2.5, 12.0, 3.8));
        assertTrue(h.verdict(), h.verdict().startsWith("erst eine Verbindung"));
        h.upsert(trip(NOV_07 + 12 * DAY, 11.7, 4.3, 10.2, 3.1));
        assertTrue(h.verdict(), h.verdict().startsWith("Bank 1 in allen 2 Verbindungen stärker angefettet"));
        assertTrue(h.verdict(), h.verdict().contains("höchste Korrektur zuletzt 11,7 %"));
        h.upsert(trip(NOV_07 + 13 * DAY, 13, 4, 12, 3));
        assertTrue(h.verdict(), h.verdict().contains("Unterschied stabil"));
        h.upsert(trip(NOV_07 + 20 * DAY, 17, 4, 16, 3));
        h.upsert(trip(NOV_07 + 21 * DAY, 18, 4, 17, 3));
        h.upsert(trip(NOV_07 + 22 * DAY, 18, 4, 17, 3));
        assertTrue(h.verdict(), h.verdict().contains("Unterschied wächst"));
    }

    @Test
    public void verdictBanksTogether() {
        TrimHistory h = new TrimHistory();
        h.upsert(trip(NOV_07, 2, 1, 3, 2));
        h.upsert(trip(NOV_07 + DAY, 9, 2, 9, 2));
        assertTrue(h.verdict(), h.verdict().startsWith("Bankunterschied in 1 von 2 Verbindungen"));
        TrimHistory same = new TrimHistory();
        same.upsert(trip(NOV_07, 2, 1, 3, 2));
        same.upsert(trip(NOV_07 + DAY, 1, 2, 2, 3));
        assertTrue(same.verdict(), same.verdict().startsWith("Bänke in allen Verbindungen gleichlaufend"));
    }
}
