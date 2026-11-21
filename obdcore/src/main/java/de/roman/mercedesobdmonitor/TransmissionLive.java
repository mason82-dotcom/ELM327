package de.roman.mercedesobdmonitor;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * ADR-008: Gang und ATF-Temperatur live – dieselbe Sequenz wie ADR-001 ({@code ATSH7E1} → {@code 21 30} →
 * {@code ATSH7DF}), im Thread der Live-Schleife etwa alle 2 s. Keine neuen Befehle; der Header geht immer auf 7DF
 * zurück, sonst wird die Verbindung verworfen. Die Werte laufen unter eigenen Schlüsseln (kein OBD-PID) durch das
 * Alterslimit der Live-Werte.
 */
public final class TransmissionLive {
    /** Schlüssel im Live-Zwischenspeicher, außerhalb der OBD-PIDs 0x00–0xFF. */
    public static final int KEY_ATF = 0x1301;
    public static final int KEY_GEAR = 0x1302;
    /** Für CSV-Zeilen und Anzeige; Werte kommen aus {@link #poll}, nicht aus {@link ObdPid#parse}. */
    public static final ObdPid ATF_PID = new ObdPid(KEY_ATF, "ATF-Temperatur (Getriebe)", "°C", 0, ObdPid.Formula.TEMP);
    /** Wert = eingelegter Gang 1–7, 0 ohne Gang (P/R/N); der Text nennt Wählhebel und Gang. */
    public static final ObdPid GEAR_PID = new ObdPid(KEY_GEAR, "Gang (Getriebe)", "", 0, ObdPid.Formula.PERCENT);

    public static final long INTERVAL_MS = 2_000;
    /** So viele ungültige Antworten in Folge schalten Getriebe-live für die Sitzung ab. */
    public static final int MAX_FAILURES = 3;
    static final int HEADER_TIMEOUT_MS = 1_500;
    static final int REQUEST_TIMEOUT_MS = 1_800;

    private TransmissionLive() {
    }

    /** Ergebnis einer Abfrage: ATF und/oder Gang (null = kein gültiger Wert) samt Grund und Rohantwort. */
    public static final class Sample {
        public final Double celsius;
        public final String gear;
        public final int gearNumber;
        public final String problem;
        public final String raw;
        public final long elapsedMs;
        /** 21 30 kam zu spät (resynchronisiert): zählt wie ein PID-Timeout. */
        public final boolean timedOut;

        Sample(Double celsius, String gear, int gearNumber, String problem, String raw, long elapsedMs,
                boolean timedOut) {
            this.celsius = celsius;
            this.gear = gear;
            this.gearNumber = gearNumber;
            this.problem = problem;
            this.raw = raw;
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
        }

        public boolean valid() {
            return celsius != null || gear != null;
        }
    }

    /** Der Header ist nach der Sequenz nicht sicher 7DF, obwohl die Verbindung noch stand (Verbindung verworfen). */
    public static final class HeaderNotRestored extends IOException {
        HeaderNotRestored(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Eine Abfrage, unter der Sperre des Clients über alle drei Befehle: keine Diagnose und kein Terminalbefehl
     * kann zwischen {@code ATSH7E1} und {@code ATSH7DF} laufen. Danach ist der Header 7DF – oder {@code drop} wurde
     * aufgerufen (der Reconnect setzt den Header über {@link LinkPolicy#reconnectInit}). Eine Zeitüberschreitung bei
     * {@code 21 30} wird nach einer Resynchronisation mit Rückweg auf 7DF als ungültige Antwort gemeldet.
     *
     * @throws HeaderNotRestored wenn der Header-Wechsel bei stehender Verbindung nicht bestätigt wurde
     * @throws IOException wenn die Verbindung abriss (normaler Neuaufbau)
     */
    public static Sample poll(Elm327Client c, Runnable drop) throws IOException {
        synchronized (c) {
            boolean restored = false;
            try {
                c.sendCommand(CommandSafety.ATF_HEADER, HEADER_TIMEOUT_MS);
                Sample s;
                try {
                    Elm327Client.CommandResult r = c.sendCommand(CommandSafety.ATF_REQUEST, REQUEST_TIMEOUT_MS);
                    s = sample(AtfTemperature.parse(r.raw), r.elapsedMs, false);
                } catch (SocketTimeoutException e) {
                    if (!c.resync(1_000)) throw e;
                    s = new Sample(null, null, -1, "Zeitüberschreitung", "", REQUEST_TIMEOUT_MS, true);
                }
                c.sendCommand(CommandSafety.OBD_HEADER, HEADER_TIMEOUT_MS);
                restored = CommandSafety.DEFAULT_HEADER.equals(c.header());
                if (!restored) throw new HeaderNotRestored("Header nach ATSH7DF: " + c.header(), null);
                return s;
            } catch (SocketTimeoutException e) {
                // keine Antwort auf einen der Befehle bei stehender Verbindung: Header-Zustand unbekannt
                if (c.isConnected()) throw new HeaderNotRestored("keine Antwort (" + e.getMessage() + ")", e);
                throw e;
            } finally {
                if (!restored) drop.run();
            }
        }
    }

    static Sample sample(AtfTemperature.Reading reading, long elapsedMs, boolean timedOut) {
        String gear = reading.response.length > 12 ? gearText(reading.response) : null;
        int number = gear == null ? -1 : gearNumber(reading.response);
        return new Sample(reading.celsius, gear, number, reading.problem, reading.rawHex, elapsedMs, timedOut);
    }

    /**
     * Wählhebel [9] und Gang [12] (oberes Halbbyte = eingelegt, unteres = Ziel, ADR-003): „P“, „R“, „N“, „D 3“,
     * beim Schalten „D 3→2“. null bei unbekanntem Wählhebel (keine Vermutung).
     */
    static String gearText(byte[] r) {
        String sel = AtfTemperature.selector(r[9] & 0xFF);
        if (sel == null) return null;
        if (!"D".equals(sel)) return sel;
        int g = r[12] & 0xFF, now = g >> 4, target = g & 0x0F;
        if (now < 1 || now > 7) return "D";
        return target >= 1 && target <= 7 && target != now ? "D " + now + "→" + target : "D " + now;
    }

    static int gearNumber(byte[] r) {
        int now = (r[12] & 0xFF) >> 4;
        return "D".equals(AtfTemperature.selector(r[9] & 0xFF)) && now >= 1 && now <= 7 ? now : 0;
    }

    /** Takt und Selbstabschaltung je Sitzung (ein Exemplar je Live-Schleife). */
    public static final class Schedule {
        private long lastAt = Long.MIN_VALUE;
        private int failures;
        private String offReason;

        public boolean due(long nowMs) {
            return offReason == null && (lastAt == Long.MIN_VALUE || nowMs - lastAt >= INTERVAL_MS);
        }

        public void started(long nowMs) {
            lastAt = nowMs;
        }

        /** @return true, wenn diese Antwort Getriebe-live abgeschaltet hat */
        public boolean onResult(boolean valid, String problem) {
            if (valid) {
                failures = 0;
                return false;
            }
            if (++failures < MAX_FAILURES) return false;
            offReason = MAX_FAILURES + "× keine gültige Antwort (" + problem + ")";
            return true;
        }

        public void onDropped(String reason) {
            offReason = "Header-Rückweg gescheitert (" + reason + ")";
        }

        /** Grund der Abschaltung oder null, solange Getriebe-live läuft. */
        public String offReason() {
            return offReason;
        }
    }
}
