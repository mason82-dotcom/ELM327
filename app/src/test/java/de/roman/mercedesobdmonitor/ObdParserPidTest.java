package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public class ObdParserPidTest {
    @Test public void parsesMode01Payload() {
        assertArrayEquals(new byte[] {0x1A, (byte) 0xF8},
                ObdParser.mode01Data("41 0C 1A F8", 0x0C, 2));
    }

    @Test public void unionsSupportedPidBitmapsFromMultipleEcus() {
        // ECU 1: PID 01; ECU 2: PID 02. Union must contain both.
        Set<Integer> pids = ObdParser.supportedPids(
                "410080000000\r410040000000\r", 0x00);
        assertTrue(pids.contains(0x01));
        assertTrue(pids.contains(0x02));
    }
}
