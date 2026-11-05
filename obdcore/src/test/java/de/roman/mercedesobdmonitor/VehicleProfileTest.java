package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VehicleProfileTest {
    private final VehicleProfile p = VehicleProfile.C350_S204_M272_2008;

    @Test
    public void c350S204M272Values() {
        assertEquals(VehicleProfile.ACTIVE, p);
        assertTrue(p.name.contains("S204") && p.name.contains("M272") && p.name.contains("2008"));
        assertEquals(6, p.cylinders);
        assertEquals(2, p.banks);
        assertTrue(p.expectCan);
        assertTrue(p.m272Hints);
    }

    @Test
    public void m272BankSplitMatchesMode06() {
        for (int cyl = 1; cyl <= 6; cyl++) assertEquals("Zylinder " + cyl, Mode06.m272Bank(cyl), p.bankOf(cyl));
    }

    @Test
    public void idleWindow() {
        assertTrue(p.idlePlausible(650));
        assertTrue(p.idlePlausible(550));
        assertFalse(p.idlePlausible(1200));
        assertTrue(p.idleText(650, 0).startsWith("Leerlauf plausibel"));
        assertTrue(p.idleText(1300, 0).startsWith("Leerlauf außerhalb"));
        assertEquals("driving is not idle", "", p.idleText(1300, 50));
        assertEquals("engine off is not idle", "", p.idleText(0, 0));
        assertEquals("", p.idleText(Double.NaN, 0));
    }

    @Test
    public void busCheck() {
        assertTrue(p.busCheck(true).contains("wie erwartet"));
        assertTrue(p.busCheck(false).contains("ungewöhnlich"));
        assertTrue(p.busCheck(null).contains("noch nicht"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMoreThanSixCylinders() {
        new VehicleProfile("x", "x", 8, 2, 4, 7000, 500, 1000, true, false);
    }
}
