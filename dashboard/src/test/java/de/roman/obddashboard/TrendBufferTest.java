package de.roman.obddashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrendBufferTest {
    @Test
    public void keepsTheNewestValuesInOrder() {
        TrendBuffer b = new TrendBuffer(3);
        for (int i = 1; i <= 5; i++) b.add(i);
        assertEquals(3, b.size());
        assertEquals(3.0, b.get(0), 0);
        assertEquals(5.0, b.get(2), 0);
    }

    @Test
    public void gapsAreKeptAndCollapsed() {
        TrendBuffer b = new TrendBuffer(10);
        b.add(800);
        b.addGap();
        b.addGap();
        b.add(900);
        assertEquals(3, b.size());
        assertTrue(Double.isNaN(b.get(1)));
        assertEquals(900.0, b.latestValid(), 0);
    }

    @Test
    public void latestValidSkipsTrailingGap() {
        TrendBuffer b = new TrendBuffer(4);
        assertTrue(Double.isNaN(b.latestValid()));
        b.add(1200);
        b.addGap();
        assertEquals(1200.0, b.latestValid(), 0);
        b.clear();
        assertEquals(0, b.size());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void outOfRange() {
        new TrendBuffer(4).get(0);
    }
}
