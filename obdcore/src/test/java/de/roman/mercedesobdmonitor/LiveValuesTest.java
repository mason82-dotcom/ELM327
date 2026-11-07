package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LiveValuesTest {
    @Test
    public void invalidPidReplacesTheOldValue() {
        Map<Integer, String> texts = new HashMap<>();
        texts.put(0x0C, "1234 U/min");
        assertTrue(LiveValues.markInvalid(texts, 0x0C));
        assertEquals(LiveValues.INVALID, texts.get(0x0C));
        assertFalse("second invalidation changes nothing", LiveValues.markInvalid(texts, 0x0C));
    }

    @Test
    public void neverShownPidIsMarkedInvalidToo() {
        Map<Integer, String> texts = new HashMap<>();
        assertTrue(LiveValues.markInvalid(texts, 0x05));
        assertFalse(LiveValues.isValid(texts.get(0x05)));
    }

    @Test
    public void connectionLossInvalidatesEveryShownValueOnce() {
        Map<Integer, String> texts = new HashMap<>();
        texts.put(0x0C, "1234 U/min");
        texts.put(0x05, "90 °C");
        texts.put(0x0D, LiveValues.INVALID);
        List<Integer> changed = LiveValues.markAllInvalid(texts);
        assertEquals(2, changed.size());
        assertTrue(changed.contains(0x0C) && changed.contains(0x05));
        for (String t : texts.values()) assertEquals(LiveValues.INVALID, t);
        assertTrue(LiveValues.markAllInvalid(texts).isEmpty());
    }

    @Test
    public void validity() {
        assertTrue(LiveValues.isValid("0 km/h"));
        assertFalse(LiveValues.isValid(null));
        assertFalse(LiveValues.isValid(LiveValues.INVALID));
    }

    @Test
    public void valuesOlderThanTheAgeLimitExpire() {
        java.util.Map<Integer, Long> at = new java.util.HashMap<>();
        at.put(0x0C, 100_000L);                       // fresh
        at.put(0x05, 100_000L - LiveValues.MAX_AGE_MS); // exactly at the limit: still valid
        at.put(0x42, 100_000L - LiveValues.MAX_AGE_MS - 1); // older: expired
        java.util.List<Integer> old = LiveValues.expired(at, 100_000L, LiveValues.MAX_AGE_MS);
        org.junit.Assert.assertEquals(java.util.Collections.singletonList(0x42), old);
        // Slow PIDs (every 10th cycle at about 1 s) and short diagnoses stay below the limit.
        org.junit.Assert.assertTrue(LiveValues.MAX_AGE_MS >= 10 * 1000);
    }
}
