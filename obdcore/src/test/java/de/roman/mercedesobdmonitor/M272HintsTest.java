package de.roman.mercedesobdmonitor;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class M272HintsTest {
    @Test public void knownCodesHaveHints() {
        assertTrue(M272Hints.hint("P0016").contains("Steuertrieb"));
        // MBUSA-DTB S-B-03.30/08i: Ausgleichswelle nicht für Baureihe 204, Magnete schon
        assertTrue(M272Hints.hint("P0016").contains("Baureihe 204 (W204/S204) nimmt das Bulletin ausdrücklich aus"));
        assertTrue(M272Hints.hint("P0011").contains("Baureihe 204 eingeschlossen"));
        assertTrue(M272Hints.hint("P0171").contains("Y-Stück"));
        assertTrue(M272Hints.hint("P2004").contains("Saugrohr"));
        assertTrue(M272Hints.hint("P0304").contains("Bank 2"));
        assertTrue(M272Hints.hint("P0301").contains("Bank 1"));
        assertTrue(M272Hints.hint("P0128").contains("Thermostat"));
    }

    @Test public void unknownCodeHasNoHint() {
        assertNull(M272Hints.hint("P1234"));
        assertNull(M272Hints.hint(null));
    }

    @Test public void bothBanksLeanPattern() {
        assertFalse(M272Hints.patterns(Arrays.asList("P0171", "P0174")).isEmpty());
        assertTrue(M272Hints.patterns(Arrays.asList("P0171")).isEmpty());
    }
}
