package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Regression für verzögert zugestellte Diagnoseergebnisse und UI-Status nach Trennen. */
public class SessionEpochTest {
    @Test public void sameActiveSessionAcceptsResult() {
        assertTrue(SessionEpoch.matches(7, 7, true, false));
    }

    @Test public void disconnectInvalidatesPendingResult() {
        assertFalse(SessionEpoch.matches(7, 8, false, true));
        // Auch wenn die Generation noch gleich wäre, darf ein abgemeldeter Auftrag nicht liefern.
        assertFalse(SessionEpoch.matches(7, 7, true, true));
    }

    @Test public void newConnectionCannotReceiveOldResult() {
        assertFalse(SessionEpoch.matches(7, 8, true, false));
    }

    @Test public void inactiveSessionCannotReceiveResult() {
        assertFalse(SessionEpoch.matches(7, 7, false, false));
    }

    @Test public void queuedUiCallbackRechecksGenerationWhenExecuted() {
        AtomicInteger current = new AtomicInteger(11);
        AtomicInteger deliveries = new AtomicInteger();
        Runnable oldCallback = () -> {
            if (SessionEpoch.matches(11, current.get(), true, false)) deliveries.incrementAndGet();
        };
        current.incrementAndGet(); // Trennen und neue Verbindung vor der UI-Zustellung
        oldCallback.run();
        assertTrue("Altes Ergebnis verworfen", deliveries.get() == 0);
        assertTrue(SessionEpoch.matches(12, current.get(), true, false));
    }
}
