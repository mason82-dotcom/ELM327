package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Einstufung einer empfangenen Antwort: nur ein auswertbarer Wert ist anzeigbar, alles andere ist „–“. */
public class PollOutcomeTest {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("PID missing: " + id);
    }

    @Test
    public void onlyAParsedAnswerIsAValue() {
        PollOutcome.Result r = PollOutcome.classify(pid(0x0C), "41 0C 1A F8");
        assertEquals(PollOutcome.VALUE, r.outcome);
        assertTrue(r.outcome.isValue());
        assertEquals(1726.0, r.value, 0.01);
    }

    @Test
    public void everyFailureIsNotAValueAndCarriesNoValue() {
        String[][] cases = {
                {"NO DATA", "NO_DATA"}, {"CAN ERROR", "LINK_ERROR"}, {"BUS INIT: ...ERROR", "LINK_ERROR"},
                {"UNABLE TO CONNECT", "LINK_ERROR"}, {"41 0D 32", "PARSE_ERROR"}, {"?", "PARSE_ERROR"}, {"", "PARSE_ERROR"}};
        for (String[] c : cases) {
            PollOutcome.Result r = PollOutcome.classify(pid(0x0C), c[0]);
            assertEquals(c[0], PollOutcome.valueOf(c[1]), r.outcome);
            assertFalse(c[0], r.outcome.isValue());
            assertNull(c[0], r.value);
        }
        for (PollOutcome o : PollOutcome.values()) {
            if (o != PollOutcome.VALUE) assertFalse(o + " must not count as a value", o.isValue());
        }
    }
}
