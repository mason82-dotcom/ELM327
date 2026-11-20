package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;
import org.junit.Test;

public class Me97CodesTest {
    @Test
    public void tableIsCompleteAndGerman() {
        assertTrue(Me97Codes.size() >= 226);
        Pattern english = Pattern.compile("\\b(the|has|is|of|and|component|valve|sensor|circuit|wiring|signal)\\b");
        for (java.util.Map.Entry<String, String> e : Me97Codes.entries()) {
            assertTrue(e.getKey(), e.getKey().matches("P[0-9A-F]{4}"));
            assertFalse(e.getKey() + ": " + e.getValue(), english.matcher(e.getValue()).find());
        }
    }

    @Test
    public void sidesAndSubFaultsMatchTheManual() {
        assertTrue(Me97Codes.describe("P0171").contains("rechts Teillast: Anreicherung über Grenze (lief mager)"));
        assertTrue(Me97Codes.describe("P0171").contains("rechts Leerlauf"));
        assertTrue(Me97Codes.describe("P0174").startsWith("Gemischadaption links"));
        assertEquals("Heizung Lambdasonde rechts vor Kat (G3/4): Unterbrechung", Me97Codes.describe("P0030"));
        assertTrue(Me97Codes.describe("P0016").startsWith("Einlassnockenwelle rechts"));
        assertTrue(Me97Codes.describe("P0422").contains("Katalysator rechts"));
        assertNull("ME9.7 meldet P0420 nicht", Me97Codes.describe("P0420"));
        assertNull(Me97Codes.describe(null));
        // Review: Anschlag-Richtung links wie rechts (mager → Fettanschlag)
        assertTrue(Me97Codes.describe("P0174").endsWith("Lambdaregelung links: am Fettanschlag"));
        assertTrue(Me97Codes.describe("P0175").endsWith("am Mageranschlag"));
        assertTrue(Me97Codes.describe("P0023").contains("links"));
        assertTrue(Me97Codes.hasAlternatives(Me97Codes.describe("P2004")));
        assertFalse(Me97Codes.hasAlternatives(Me97Codes.describe("P0030")));
    }

    @Test
    public void groupHintsCoverNewCodes() {
        assertTrue(M272Hints.hint("P2316").startsWith("Zündspule"));
        assertTrue(M272Hints.hint("P2300").startsWith("Zündspule"));
        assertTrue(M272Hints.hint("P0035") == null);
        assertTrue(M272Hints.hint("P0422").contains("nicht P0420/P0430"));
        assertTrue(M272Hints.hint("P0130").contains("Abgasleck vor der Sonde"));
        assertTrue(M272Hints.hint("P0598").contains("Y110"));
    }
}
