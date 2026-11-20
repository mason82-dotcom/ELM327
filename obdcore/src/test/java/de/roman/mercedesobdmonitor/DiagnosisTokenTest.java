package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Dieselbe Verbindung darf ein altes Diagnose-Ergebnis nicht einer neuen Abfrage zuordnen. */
public class DiagnosisTokenTest {
    @Test public void acceptsCurrentActiveRequest() {
        assertTrue(DiagnosisToken.matches(3, 3, 8, 8, true, false));
    }

    @Test public void newRequestInvalidatesQueuedOldResultWithinSameSession() {
        assertFalse(DiagnosisToken.matches(3, 3, 8, 9, true, false));
    }

    @Test public void cancellationInvalidatesAllQueuedCallbacks() {
        AtomicLong sequence = new AtomicLong(14);
        long startToken = sequence.get();
        sequence.incrementAndGet(); // cancelDtcScan: kein weiterer Diagnoseauftrag
        assertFalse(DiagnosisToken.matches(5, 5, startToken, sequence.get(), true, false));
        assertTrue(DiagnosisToken.matches(5, 5, sequence.get(), sequence.get(), true, false));
    }

    @Test public void disconnectAndNewSessionInvalidateEvenMatchingTicket() {
        assertFalse(DiagnosisToken.matches(5, 6, 10, 10, true, false));
        assertFalse(DiagnosisToken.matches(5, 5, 10, 10, false, true));
        assertFalse(DiagnosisToken.matches(5, 5, 0, 0, true, false));
    }
}
