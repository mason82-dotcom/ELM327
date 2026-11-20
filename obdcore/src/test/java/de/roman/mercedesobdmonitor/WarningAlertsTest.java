package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WarningAlertsTest {
    private static final LiveWarnings.Warning HOT =
            new LiveWarnings.Warning("coolant", LiveWarnings.Level.ALARM, "Kühlmittel 117 °C");
    private static final LiveWarnings.Warning WARM =
            new LiveWarnings.Warning("coolant", LiveWarnings.Level.WARN, "Kühlmittel 109 °C");
    private static final LiveWarnings.Warning VOLT =
            new LiveWarnings.Warning("charge_low", LiveWarnings.Level.ALARM, "Bordnetz 11,9 V");

    private final WarningAlerts a = new WarningAlerts();

    @Test
    public void alarmBeepsOnceThenEveryMinute() {
        assertTrue(a.update(HOT, 0));
        assertFalse(a.update(HOT, 1_000));
        assertFalse(a.update(HOT, 59_000));
        assertTrue(a.update(HOT, 60_000));
    }

    @Test
    public void warningNeverBeeps() {
        for (long t = 0; t < 200_000; t += 1000) assertFalse(a.update(WARM, t));
        assertFalse(a.canMute(WARM));
    }

    @Test
    public void muteHoldsAcrossShortDataGap() {
        assertTrue(a.update(HOT, 0));
        assertTrue(a.canMute(HOT));
        a.silence();
        assertFalse(a.canMute(HOT));
        // eine Abfrage ohne Antwort: Meldung kurz weg, dann wieder da – weder Ton noch „Ton aus“ zurück
        assertFalse(a.update(null, 5_000));
        for (long t = 10_000; t <= 180_000; t += 1000) assertFalse("t=" + t, a.update(HOT, t));
        assertFalse(a.canMute(HOT));
    }

    @Test
    public void newCauseOrLevelOrLongAbsenceRearms() {
        a.update(HOT, 0);
        a.silence();
        assertTrue("andere Ursache", a.update(VOLT, 1_000));
        a.silence();
        a.update(null, 2_000);
        assertTrue("nach über einer Minute ohne Meldung", a.update(VOLT, 70_000));
        a.update(WARM, 71_000);
        assertTrue("Wechsel Warnung → Alarm", a.update(HOT, 72_000));
    }
}
