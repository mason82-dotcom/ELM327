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
}
