package de.roman.mercedesobdmonitor;

/**
 * Warnungen aus Livewerten, die ohnehin gelesen werden (01 05 Kühlmittel, 01 42 Steuergerät-Spannung, 01 0C
 * Drehzahl, ATF aus Getriebe-live nach ADR-008) – keine zusätzlichen Befehle, nur lesen. Android-frei und mit eingespeister Uhr testbar.
 *
 * <p>Jede Regel ist eine kleine Zustandsmaschine: Eine Bedingung muss eine Mindestdauer anstehen (Entprellung),
 * bevor sie gilt, und endet erst jenseits einer Rückschaltschwelle (Hysterese) nach derselben Dauer. Ungültige
 * Werte oder fehlende Messungen setzen die Regel zurück: ohne frische Daten wird nichts behauptet.
 *
 * <p>Schwellen sind Orientierung für den C350 (M272), keine Herstellerangabe. 01 42 ist die Versorgung am
 * Motorsteuergerät, nicht die Batterieklemme (meist einige Zehntel Volt darunter).
 */
public final class LiveWarnings {
    public enum Level { WARN, ALARM }

    /** Eine aktive Meldung; {@code key} bleibt je Ursache gleich (für „Ton aus“ je Meldung). */
    public static final class Warning {
        public final String key;
        public final Level level;
        public final String text;

        Warning(String key, Level level, String text) {
            this.key = key;
            this.level = level;
            this.text = text;
        }

        @Override
        public String toString() {
            return level + " " + key + ": " + text;
        }
    }

    // Kühlmittel: dieselben Grenzen wie die Rundanzeige (gelb/rot), Rückschalten je 4 K darunter
    // (Alarm fällt bei 110 °C auf Warnung zurück, die Warnung endet unter 104 °C).
    public static final double COOLANT_WARN = 108;
    public static final double COOLANT_ALARM = 115;
    static final double COOLANT_ALARM_CLEAR = 111;
    static final double COOLANT_CLEAR = 104;

    // Motor läuft: Ladespannung (Obergrenze laut WIS bis 15 V). Gemessen am C350: 14,1–14,6 V. Das ME senkt sie bei voller Batterie im Leerlauf
    // deutlich ab (WIS GF07.61-P-3003CY) – erst unter der Ruhespannung einer vollen Batterie entlädt sie sich wirklich.
    static final double CHARGE_LOW_WARN = 12.6;
    static final double CHARGE_LOW_ALARM = 12.2;
    static final double CHARGE_LOW_CLEAR = 12.9;
    static final double CHARGE_HIGH_ALARM = EngineAnalysis.CHARGE_MAX_NORMAL;
    static final double CHARGE_HIGH_CLEAR = 14.8;

    // Zündung an, Motor aus: Ruhespannung. Log 20.11.2026: 11,5–11,6 V vor dem Start.
    static final double BATTERY_LOW_WARN = 11.8;
    static final double BATTERY_LOW_CLEAR = 12.0;

    // Getriebeöl (ADR-008, Richtwerte – das WIS nennt keine feste Grenze): Betrieb ~80 °C, Thermostat ab 90 °C,
    // mehrere Getriebefunktionen nur bis 110 °C, bei Übertemperatur nimmt das Getriebe Motormoment zurück.
    public static final double ATF_WARN = 120;
    public static final double ATF_ALARM = 130;
    static final double ATF_ALARM_CLEAR = 126;
    static final double ATF_CLEAR = 116;

    static final double RUNNING_RPM = 400;
    static final double STOPPED_RPM = 100;
    /** Nach dem Start erst laden lassen; Anlasser und Regler-Anlauf sind keine Bordnetzstörung. */
    static final long AFTER_START_MS = 20_000;
    static final long COOLANT_HOLD_MS = 5_000;
    static final long CHARGE_HOLD_MS = 15_000;
    static final long BATTERY_HOLD_MS = 10_000;
    static final long ALARM_HOLD_MS = 5_000;
    /** Älter als das gilt ein Wert nicht mehr – dieselbe Grenze wie die Anzeige ({@link LiveValues#MAX_AGE_MS}). */
    static final long STALE_MS = LiveValues.MAX_AGE_MS;

    private final Rule coolantWarn = new Rule(COOLANT_HOLD_MS);
    private final Rule coolantAlarm = new Rule(ALARM_HOLD_MS);
    private final Rule chargeLow = new Rule(CHARGE_HOLD_MS);
    private final Rule chargeLowAlarm = new Rule(ALARM_HOLD_MS);
    private final Rule chargeHigh = new Rule(ALARM_HOLD_MS);
    private final Rule batteryLow = new Rule(BATTERY_HOLD_MS);
    private final Rule atfWarn = new Rule(COOLANT_HOLD_MS);
    private final Rule atfAlarm = new Rule(ALARM_HOLD_MS);

    private double coolant = Double.NaN, voltage = Double.NaN, rpm = Double.NaN, atf = Double.NaN;
    private long coolantAt, voltageAt, rpmAt, atfAt;
    private long runningSince = -1;

    /** Neuer gültiger Messwert. Andere PIDs werden ignoriert. */
    public void onSample(int pid, double value, long nowMs) {
        switch (pid) {
            case 0x05 -> { coolant = value; coolantAt = nowMs; }
            case 0x42 -> { voltage = value; voltageAt = nowMs; }
            case TransmissionLive.KEY_ATF -> { atf = value; atfAt = nowMs; }
            case 0x0C -> {
                rpm = value;
                rpmAt = nowMs;
                if (value >= RUNNING_RPM) {
                    if (runningSince < 0) runningSince = nowMs;
                } else {
                    runningSince = -1;
                }
            }
            default -> { }
        }
    }

    /** Wert ungültig (NO DATA, Fehler, Verbindungsverlust). */
    public void onInvalid(int pid) {
        switch (pid) {
            case 0x05 -> coolant = Double.NaN;
            case 0x42 -> voltage = Double.NaN;
            case TransmissionLive.KEY_ATF -> atf = Double.NaN;
            case 0x0C -> { rpm = Double.NaN; runningSince = -1; }
            default -> { }
        }
    }

    /** Verbindung neu oder getrennt: alles vergessen. */
    public void reset() {
        coolant = voltage = rpm = atf = Double.NaN;
        runningSince = -1;
        for (Rule r : new Rule[] {coolantWarn, coolantAlarm, chargeLow, chargeLowAlarm, chargeHigh, batteryLow,
                atfWarn, atfAlarm}) {
            r.reset();
        }
    }

    /**
     * Wichtigste aktive Meldung (Alarm vor Warnung; Kühlmittel vor Getriebeöl vor Bordnetz) oder {@code null}.
     * Regelmäßig aufrufen, auch ohne neue Werte – sonst laufen Haltezeiten nicht ab.
     */
    public Warning evaluate(long nowMs) {
        double t = fresh(coolant, coolantAt, nowMs);
        double u = fresh(voltage, voltageAt, nowMs);
        double n = fresh(rpm, rpmAt, nowMs);
        double g = fresh(atf, atfAt, nowMs);
        // runningSince ist nur gesetzt, solange die letzte Drehzahl >= RUNNING_RPM war
        boolean running = !Double.isNaN(n) && runningSince >= 0 && nowMs - runningSince >= AFTER_START_MS;
        boolean stopped = !Double.isNaN(n) && n < STOPPED_RPM;

        // Haltezeiten laufen über die Zeitpunkte der Messungen, nicht über die Uhr: Ein einzelner Messwert, der
        // zwischen zwei Abfragen stehen bleibt, darf allein keine Meldung auslösen oder beenden.
        coolantAlarm.update(known(t), t >= COOLANT_ALARM, t < COOLANT_ALARM_CLEAR, coolantAt);
        coolantWarn.update(known(t), t >= COOLANT_WARN, t < COOLANT_CLEAR, coolantAt);
        chargeLowAlarm.update(known(u) && running, u < CHARGE_LOW_ALARM, u >= CHARGE_LOW_CLEAR, voltageAt);
        chargeLow.update(known(u) && running, u < CHARGE_LOW_WARN, u >= CHARGE_LOW_CLEAR, voltageAt);
        chargeHigh.update(known(u) && running, u > CHARGE_HIGH_ALARM, u <= CHARGE_HIGH_CLEAR, voltageAt);
        batteryLow.update(known(u) && stopped, u < BATTERY_LOW_WARN, u >= BATTERY_LOW_CLEAR, voltageAt);
        atfAlarm.update(known(g), g >= ATF_ALARM, g < ATF_ALARM_CLEAR, atfAt);
        atfWarn.update(known(g), g >= ATF_WARN, g < ATF_CLEAR, atfAt);

        if (coolantAlarm.active) {
            return new Warning("coolant", Level.ALARM, String.format(java.util.Locale.GERMANY,
                    "Kühlmittel %.0f °C – Motor überhitzt. Anhalten, Motor im Leerlauf abkühlen lassen.", t));
        }
        if (atfAlarm.active) {
            return new Warning("atf", Level.ALARM, String.format(java.util.Locale.GERMANY,
                    "Getriebeöl %.0f °C – überhitzt. Last sofort verringern, bald anhalten und in P abkühlen lassen.", g));
        }
        if (chargeHigh.active) {
            return new Warning("charge_high", Level.ALARM, String.format(java.util.Locale.GERMANY,
                    "Bordnetz %.1f V – Überspannung. Laderegler/Lichtmaschine prüfen lassen.", u));
        }
        if (chargeLowAlarm.active) {
            return new Warning("charge_low", Level.ALARM, String.format(java.util.Locale.GERMANY,
                    "Bordnetz %.1f V – Lichtmaschine lädt nicht. Verbraucher aus, Werkstatt ansteuern.", u));
        }
        if (coolantWarn.active) {
            return new Warning("coolant", Level.WARN, String.format(java.util.Locale.GERMANY,
                    "Kühlmittel %.0f °C – höher als üblich. Last reduzieren, Anzeige beobachten.", t));
        }
        if (atfWarn.active) {
            return new Warning("atf", Level.WARN, String.format(java.util.Locale.GERMANY,
                    "Getriebeöl %.0f °C – höher als üblich. Last verringern (Steigung, Anhänger), Anzeige beobachten.", g));
        }
        if (chargeLow.active) {
            return new Warning("charge_low", Level.WARN, String.format(java.util.Locale.GERMANY,
                    "Bordnetz %.1f V bei laufendem Motor – Ladung schwach. Lichtmaschine/Riemen prüfen lassen.", u));
        }
        if (batteryLow.active) {
            return new Warning("battery_low", Level.WARN, String.format(java.util.Locale.GERMANY,
                    "Batterie %.1f V bei Zündung an – schwach. Start kann scheitern; Starttest machen.", u));
        }
        return null;
    }

    private static double fresh(double value, long at, long now) {
        return !Double.isNaN(value) && now - at <= STALE_MS ? value : Double.NaN;
    }

    private static boolean known(double v) {
        return !Double.isNaN(v);
    }

    /** Ein Schalter mit Entprellung in beide Richtungen; ohne Daten sofort aus. */
    private static final class Rule {
        private final long holdMs;
        boolean active;
        private long since = -1;

        Rule(long holdMs) {
            this.holdMs = holdMs;
        }

        void update(boolean applicable, boolean trigger, boolean clear, long now) {
            if (!applicable) {
                reset();
                return;
            }
            boolean towards = active ? clear : trigger;
            if (!towards) {
                since = -1;
                return;
            }
            if (since < 0) since = now;
            if (now - since >= holdMs) {
                active = !active;
                since = -1;
            }
        }

        void reset() {
            active = false;
            since = -1;
        }
    }
}
