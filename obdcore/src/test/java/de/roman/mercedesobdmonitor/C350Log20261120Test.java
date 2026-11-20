package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unveränderte, gekürzte Rohantwort-Beispiele aus dem C350-W204-Log vom 20.11.2026
 * (4 154 erfolgreich protokollierte Messwerte). Ohne VIN, Position, Timestamps
 * oder komplette private Messreihe im Repository.
 *
 * Die Werte stammen aus dem tatsächlichen Live-CSV und sind KEINE Diagnoseschlussfolgerung.
 */
public class C350Log20261120Test {
    private static ObdPid pid(int id) {
        for (ObdPid p : ObdPid.defaultPids()) if (p.pid == id) return p;
        throw new AssertionError("Unknown PID: " + id);
    }

    /** Auch wenn 7E9 zuerst eintrifft, muss bei abweichenden Werten 7E8 gelten. */
    @Test public void liveCsvEngineResponseWinsInEitherOrder() {
        verify(0x42, 11.497, "7E90441422AAD 7E80441422CE9");
        verify(0x42, 11.591, "7E90441422AAD 7E80441422D47");
        verify(0x05, 16.0, "7E803410538 7E903410537");
        verify(0x49, 6.666667, "7E903414910 7E803414911");
        verify(0x0C, 1072.75, "7E904410C1090 7E804410C10C3");
        verify(0x0C, 678.50, "7E804410C0A9A 7E904410C0A98");
        // Ein echter 7E9-first-Frame wird nicht automatisch als Motorwert übernommen.
        assertEquals(10.925, pid(0x42).parse("7E90441422AAD"), 1e-6);
        assertEquals(11.497, pid(0x42).parse("7E90441422AAD 7E80441422CE9"), 1e-6);
        assertEquals(1072.75, pid(0x0C).parse("7E904410C1090 7E804410C10C3"), 1e-6);
        assertFalse(ObdParser.payloadLines("7E90441422AAD 7E80441422CE9").get(0).equals("41422AAD"));
    }

    /** Ein Antwortwert aus dem zweiten Steuergerät darf nicht zwingend einen falschen Parserfehler auslösen. */
    @Test public void singleEcuSuccessFramesFromLongLatencySamplesParse() {
        verify(0x08, 2.343750, "7E803410883");
        verify(0x06, 0.0, "7E803410680");
    }

    /**
     * Vier erfolgreiche Antworten brauchten 1322/1367/1414/1528 ms, sind daher kein Beleg für einen
     * Socket-Timeout. Die konfigurierte Deadline muss sie annehmen, ohne blind erhöht zu werden.
     */
    @Test public void observedSlowSuccessfulRepliesFitCurrentPollingDeadline() {
        int[] latencyMs = {1367, 1322, 1414, 1528};
        int max = 0;
        for (int ms : latencyMs) {
            max = Math.max(max, ms);
            assertTrue("Erfolgreiche Messung darf nicht durch engere Deadline verlorengehen: " + ms,
                    ms < LinkPolicy.POLL_TIMEOUT_MS);
        }
        assertEquals(1528, max);
        assertEquals(1800, LinkPolicy.POLL_TIMEOUT_MS);
        assertTrue(LinkPolicy.FIRST_REQUEST_TIMEOUT_MS >= LinkPolicy.POLL_TIMEOUT_MS);
    }

    private static void verify(int pidId, double csvValue, String raw) {
        ObdPid p = pid(pidId);
        Double replay = p.parse(raw);
        assertNotNull("PID " + p.command() + " / " + raw, replay);
        assertEquals(raw, csvValue, replay, 1e-6);
        PollOutcome.Result classified = PollOutcome.classify(p, raw);
        assertEquals(PollOutcome.VALUE, classified.outcome);
        assertEquals(raw, csvValue, classified.value, 1e-6);
    }
}
