package de.roman.obddashboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GaugeScaleTest {
    @Test
    public void fractionIsClampedButZonesUseTheRealValue() {
        assertEquals(0.0, GaugeScale.RPM.fraction(-50), 1e-9);
        assertEquals(0.5, GaugeScale.RPM.fraction(3500), 1e-9);
        assertEquals(1.0, GaugeScale.RPM.fraction(9000), 1e-9);
        assertEquals(GaugeScale.Zone.ALARM, GaugeScale.RPM.zone(9000));
    }

    @Test
    public void zones() {
        assertEquals(GaugeScale.Zone.NORMAL, GaugeScale.RPM.zone(800));
        assertEquals(GaugeScale.Zone.WARN, GaugeScale.RPM.zone(5500));
        assertEquals(GaugeScale.Zone.ALARM, GaugeScale.RPM.zone(6300));
        assertEquals(GaugeScale.Zone.NORMAL, GaugeScale.COOLANT.zone(90));
        assertEquals(GaugeScale.Zone.WARN, GaugeScale.COOLANT.zone(110));
        assertEquals(GaugeScale.Zone.ALARM, GaugeScale.COOLANT.zone(118));
        assertEquals("speed has no warning zone", GaugeScale.Zone.NORMAL, GaugeScale.SPEED.zone(250));
    }

    @Test
    public void missingValueIsNeutral() {
        assertEquals(0.0, GaugeScale.COOLANT.fraction(Double.NaN), 1e-9);
        assertEquals(GaugeScale.Zone.NORMAL, GaugeScale.COOLANT.zone(Double.NaN));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidRange() {
        new GaugeScale(10, 10, Double.NaN, Double.NaN);
    }
}
