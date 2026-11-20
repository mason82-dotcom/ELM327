package de.roman.mercedesobdmonitor;

import java.util.Locale;

/**
 * Einmalige, explizite Service-Freigabe für standardisiertes OBD Mode 04.
 * Keine generische Write-Whitelist. Kein Retry. Auch der Socket-Client
 * akzeptiert diesen Ticket-Typ ausschließlich über seine Spezialmethode.
 */
public final class ServiceMode04Gate {
    /** Kein Senden ohne stabile, frische Motor-aus-/Stillstand-Daten. */
    public static final long MAX_SENSOR_AGE_MS = 8_000;
    public static final long MAX_DTC_READ_AGE_MS = 5 * 60_000;
    public static final double MIN_ECU_VOLTAGE = 11.8;
    public static final double MAX_ECU_VOLTAGE = 15.5;

    private ServiceMode04Gate() { }

    public static final class Preflight {
        public final boolean active, connected, explicitlyArmed, userConfirmed, dtcSnapshotValid;
        public final Double rpm, kmh, ecuVoltage;
        public final Long rpmAt, speedAt, voltageAt;
        public final long nowMs;

        public Preflight(boolean active, boolean connected, boolean explicitlyArmed,
                         boolean userConfirmed, boolean dtcSnapshotValid,
                         Double rpm, Long rpmAt, Double kmh, Long speedAt,
                         Double ecuVoltage, Long voltageAt, long nowMs) {
            this.active = active;
            this.connected = connected;
            this.explicitlyArmed = explicitlyArmed;
            this.userConfirmed = userConfirmed;
            this.dtcSnapshotValid = dtcSnapshotValid;
            this.rpm = rpm;
            this.rpmAt = rpmAt;
            this.kmh = kmh;
            this.speedAt = speedAt;
            this.ecuVoltage = ecuVoltage;
            this.voltageAt = voltageAt;
            this.nowMs = nowMs;
        }
    }

    /** Grund für Sperre oder null. Alle Bedingungen müssen am Sendezeitpunkt erfüllt sein. */
    public static String blockedReason(Preflight p) {
        if (p == null) return "Service-Freigabe fehlt";
        if (!p.active || !p.connected) return "ELM327-Verbindung nicht aktiv";
        if (!p.explicitlyArmed || !p.userConfirmed) return "Service-Labor nicht ausdrücklich freigegeben";
        if (!p.dtcSnapshotValid) return "Vorher Mode 03/07/0A erfolgreich lesen und Ergebnis sichern";
        if (!fresh(p.rpm, p.rpmAt, p.nowMs) || p.rpm < 0 || p.rpm > 50)
            return "Motor aus (frische Drehzahl 0 U/min) nicht belegt";
        if (!fresh(p.kmh, p.speedAt, p.nowMs) || p.kmh != 0)
            return "Stillstand (frische Geschwindigkeit 0 km/h) nicht belegt";
        if (!fresh(p.ecuVoltage, p.voltageAt, p.nowMs)
                || p.ecuVoltage < MIN_ECU_VOLTAGE || p.ecuVoltage > MAX_ECU_VOLTAGE)
            return String.format(Locale.GERMANY,
                    "ECU-Versorgung muss frisch und zwischen %.1f und %.1f V liegen",
                    MIN_ECU_VOLTAGE, MAX_ECU_VOLTAGE);
        return null;
    }

    private static boolean fresh(Double value, Long at, long now) {
        return value != null && Double.isFinite(value) && at != null && at >= 0
                && now >= at && now - at <= MAX_SENSOR_AGE_MS;
    }

    /** Wird ausschließlich nach vollständigem Preflight ausgegeben und ist nur einmal nutzbar. */
    public static Ticket issue(Preflight p) {
        String reason = blockedReason(p);
        if (reason != null) throw new IllegalStateException(reason);
        return new Ticket();
    }

    public static final class Ticket {
        private boolean consumed;
        private Ticket() { }

        /** Nur Elm327Client, niemals UI/Terminal oder CSV-Import. */
        synchronized boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    /** Mode-04-Erfolg ist nur mit eindeutiger ECU-Antwort 44 belegt. */
    public static boolean positiveAck(String raw) {
        if (raw == null) return false;
        String lines = raw.toUpperCase(Locale.US).replace('>', ' ').replace('\r', '\n');
        for (String line : lines.split("\\n+")) {
            String cleaned = line.replace(" ", "").replace("\t", "");
            if (cleaned.matches("44(?:00)*")) return true;
        }
        return false;
    }
}
