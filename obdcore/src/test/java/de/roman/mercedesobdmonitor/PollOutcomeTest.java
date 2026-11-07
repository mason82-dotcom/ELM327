package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Nur eine echte Antwort mit auswertbarem Wert darf angezeigt werden; alles andere ist „–“. */
public class PollOutcomeTest {
    private static ObdPid rpm() {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == 0x0C) return p;
        throw new AssertionError("PID 0C missing");
    }

    private static PollOutcome poll(String raw) {
        boolean noValue = ObdParser.isLinkError(raw) || ObdParser.isNoData(raw);
        Double parsed = noValue ? null : rpm().parse(raw);
        return PollOutcome.of(raw, parsed);
    }

    @Test
    public void onlyAParsedAnswerIsAValue() {
        assertEquals(PollOutcome.VALUE, poll("41 0C 1A F8"));
        assertTrue(poll("41 0C 1A F8").isValue());
    }

    @Test
    public void everyFailureIsNotAValue() {
        assertEquals(PollOutcome.NO_DATA, poll("NO DATA"));
        assertEquals(PollOutcome.LINK_ERROR, poll("CAN ERROR"));
        assertEquals(PollOutcome.LINK_ERROR, poll("BUS INIT: ...ERROR"));
        assertEquals(PollOutcome.PARSE_ERROR, poll("41 0D 32"));      // answer for another PID
        assertEquals(PollOutcome.PARSE_ERROR, poll("?"));
        assertEquals(PollOutcome.PARSE_ERROR, poll(""));
        for (PollOutcome o : PollOutcome.values()) {
            if (o != PollOutcome.VALUE) assertFalse(o + " must not count as a value", o.isValue());
        }
    }

    @Test
    public void nonFiniteNumbersAreNotValues() {
        assertEquals(PollOutcome.PARSE_ERROR, PollOutcome.of("41 0C 00 00", Double.NaN));
        assertEquals(PollOutcome.PARSE_ERROR, PollOutcome.of("41 0C 00 00", Double.POSITIVE_INFINITY));
    }
}
