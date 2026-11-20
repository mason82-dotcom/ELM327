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

    @Test public void connectionStatusAndInvalidationAllowCurrentDisconnectedSession() {
        // Status „Getrennt“ darf bei inactive=false nicht versehentlich verschwinden.
        assertTrue(SessionEpoch.matchesGeneration(19, 19));
        assertFalse(SessionEpoch.matches(19, 19, false, true));
    }

    @Test public void stalePollInvalidationCannotClearNewSession() {
        AtomicInteger generation = new AtomicInteger(4);
        java.util.Map<Integer, Double> values = new java.util.HashMap<>();
        values.put(0x0C, 1650.0);
        Runnable queuedFromOldPoll = () -> {
            if (SessionEpoch.matchesGeneration(4, generation.get())) values.clear();
        };
        generation.incrementAndGet(); // Neuverbinden
        values.put(0x0C, 1950.0);
        queuedFromOldPoll.run();
        assertTrue("neuer Messwert darf nicht gelöscht werden", values.containsKey(0x0C));
        assertFalse(SessionEpoch.matchesGeneration(4, generation.get()));
    }

    @Test public void lateOldPidSampleCannotOverwriteNewSessionCache() {
        AtomicInteger generation = new AtomicInteger(32);
        java.util.Map<Integer, Double> cache = new java.util.concurrent.ConcurrentHashMap<>();
        // Die erste Verbindung sendet bereits 1600 U/min, der Callback wartet aber noch.
        Runnable oldSample = () -> {
            if (SessionEpoch.matchesGeneration(32, generation.get())) cache.put(0x0C, 1600.0);
        };
        generation.incrementAndGet();
        cache.put(0x0C, 2100.0); // neue Verbindung liefert frischen Messwert
        oldSample.run();
        org.junit.Assert.assertEquals(2100.0, cache.get(0x0C), 0.001);
    }

    @Test public void lateOldConnectionStatusCannotReplaceCurrentStatus() {
        AtomicInteger generation = new AtomicInteger(10);
        java.util.concurrent.atomic.AtomicReference<String> status =
                new java.util.concurrent.atomic.AtomicReference<>("Verbunden");
        Runnable oldReconnect = () -> {
            if (SessionEpoch.matchesGeneration(10, generation.get())) status.set("Reconnect …");
        };
        generation.incrementAndGet();
        status.set("Getrennt");
        oldReconnect.run();
        org.junit.Assert.assertEquals("Getrennt", status.get());
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
