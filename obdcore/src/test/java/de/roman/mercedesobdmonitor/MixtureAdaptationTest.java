package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public class MixtureAdaptationTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** CAN-Antwort 46 81 … mit den Beispielwerten der ME9.7-Tabelle (Faktor 1,0506 / 1,0242, Prozent −0,52/−0,47). */
    private static List<Mode06Overview.Test> tests(int f1, int f2) {
        List<Mode06Overview.Test> out = new java.util.ArrayList<>();
        // UASID 0x05: 0,0000305 je Bit (Faktor), 0xAF: 0,01 % je Bit (vorzeichenbehaftet)
        out.add(test(0x81, 0x82, 0x05, f1, 22951, 42625));
        out.add(test(0x81, 0x80, 0xAF, -52, -450, 450));
        out.add(test(0x82, 0x82, 0x05, f2, 22951, 42625));
        out.add(test(0x82, 0x80, 0xAF, -47, -450, 450));
        return out;
    }

    private static Mode06Overview.Test test(int mid, int tid, int uasid, int value, int min, int max) {
        return Mode06Overview.evaluate(new Mode06.TestResult(mid, tid, uasid, value, min, max));
    }

    @Test
    public void readsBothBanksAndShowsDistanceToLimit() {
        MixtureAdaptation.Reading r = MixtureAdaptation.from(tests(34446, 33580), 1_795_200_000_000L, 88);
        assertEquals(1.0506, r.factor1, 0.0002);
        assertEquals(1.0242, r.factor2, 0.0002);
        assertEquals(-0.52, r.percent1, 1e-9);
        MixtureAdaptation m = new MixtureAdaptation();
        m.add(r);
        String s = m.report(r, BERLIN);
        assertTrue(s, s.contains("Bank 1 (rechts, Zyl. 1–3): Faktor 1,051 (17 % des Wegs zur Grenze 1,30) · -0,52 %"));
        assertTrue(s, s.contains("Differenz B1−B2: +0,026"));
        assertTrue(s, s.contains("erst ab zwei Lesungen"));
        assertFalse(s, s.contains("⚠"));
    }

    @Test
    public void historyShowsTrendAndWarnsNearLimit() {
        MixtureAdaptation m = new MixtureAdaptation();
        m.add(MixtureAdaptation.from(tests(34446, 33580), 1_000, 88, "WDD2040561A000001"));
        MixtureAdaptation.Reading late = MixtureAdaptation.from(tests(41000, 33580), 1_000 + 86_400_000L, 90,
                "WDD2040561A000001");   // B1 1,2505, einen Tag später
        m.add(late);
        String s = MixtureAdaptation.parse(m.serialize()).report(late, BERLIN);
        assertTrue(s, s.contains("Bankunterschied wächst"));
        assertTrue(s, s.contains("⚠ nahe der Grenze – P0171 (mager) droht"));
        assertEquals(2, MixtureAdaptation.parse(m.serialize()).readings().size());
    }

    /** Review: fett (Faktor < 1) nennt P0172/P0175, nicht die Mager-Codes. */
    @Test
    public void richBankNamesRichCode() {
        MixtureAdaptation.Reading r = MixtureAdaptation.from(tests(34446, 24262), 1, 88);   // B2 0,74
        String s = new MixtureAdaptation().report(r, BERLIN);
        assertTrue(s, s.contains("Bank 2 (links, Zyl. 4–6): Faktor 0,740 (87 % des Wegs zur Grenze 0,70) ⚠ nahe der Grenze – P0175 (fett) droht"));
    }

    /** Review: gleiche Werte nicht doppelt, binnen einer Stunde ersetzen, anderes Fahrzeug nicht im Verlauf. */
    @Test
    public void dedupeAndVehicleSeparation() {
        MixtureAdaptation m = new MixtureAdaptation();
        m.add(MixtureAdaptation.from(tests(34446, 33580), 0, 88, "A"));
        m.add(MixtureAdaptation.from(tests(34446, 33580), 10 * 3_600_000L, 88, "A"));    // gleiche Werte
        assertEquals(1, m.readings().size());
        m.add(MixtureAdaptation.from(tests(34500, 33580), 10 * 3_600_000L, 88, "A"));
        m.add(MixtureAdaptation.from(tests(34600, 33580), 10 * 3_600_000L + 60_000, 88, "A"));   // binnen 1 h
        assertEquals(2, m.readings().size());
        MixtureAdaptation.Reading other = MixtureAdaptation.from(tests(40000, 33580), 30 * 3_600_000L, 88, "B");
        m.add(other);
        String s = m.report(other, BERLIN);
        assertTrue(s, s.contains("erst ab zwei Lesungen"));            // nur Fahrzeug B zählt
    }

    @Test
    public void notRunOrMissingIsReported() {
        MixtureAdaptation.Reading r = MixtureAdaptation.from(List.of(), 1, Double.NaN);
        assertFalse(r.any());
        MixtureAdaptation m = new MixtureAdaptation();
        m.add(r);
        assertTrue(m.readings().isEmpty());
        assertTrue(m.report(r, BERLIN).contains("nicht geliefert"));
    }

    @Test public void trendComparesMagnitudes() {
        assertTrue(trend(-0.08, -0.02).contains("nimmt ab"));
        assertTrue(trend(-0.02, -0.08).contains("wächst"));
        assertTrue(trend(0.04, -0.04).contains("kehrt sich um"));
        assertTrue(trend(0.04, 0.05).contains("stabil"));
    }

    private static String trend(double first, double last) {
        MixtureAdaptation m = new MixtureAdaptation();
        m.add(new MixtureAdaptation.Reading(0, 90, 0, 1 + first, 0, 1));
        MixtureAdaptation.Reading now = new MixtureAdaptation.Reading(100_000_000L, 90, 0, 1 + last, 0, 1);
        m.add(now);
        return m.report(now, java.time.ZoneOffset.UTC);
    }
}
