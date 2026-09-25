package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PollScheduleTest {
    @Test public void ltftIsSlowInNormalMode() {
        int polls = 0;
        for (int cycle = 1; cycle <= 10; cycle++) {
            if (PollSchedule.shouldPoll(0x07, cycle, false)) polls++;
        }
        assertTrue(polls == 2);
    }

    @Test public void fuelTrimFocusPollsAllTrimInputsEveryCycle() {
        int[] needed = {0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x10, 0x0B};
        for (int cycle = 1; cycle <= 10; cycle++) {
            for (int pid : needed) {
                assertTrue("PID " + pid + " Zyklus " + cycle, PollSchedule.shouldPoll(pid, cycle, true));
            }
        }
    }

    @Test public void fuelTrimFocusThrottlesUnrelatedPids() {
        assertFalse(PollSchedule.shouldPoll(0x05, 1, true));
        assertFalse(PollSchedule.shouldPoll(0x44, 3, true));
        assertTrue(PollSchedule.shouldPoll(0x05, 10, true));
    }
}
