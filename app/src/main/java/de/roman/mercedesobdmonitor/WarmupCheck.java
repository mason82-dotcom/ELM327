package de.roman.mercedesobdmonitor;

import java.util.Locale;
import java.util.Map;

/**
 * Passiver Warmlauf-/Thermostat-Check. Wertet nur bereits gepollte Mode-01-Werte aus
 * (Kühlmittel 05, Ansaugluft 0F, Geschwindigkeit 0D) und sendet selbst nichts.
 *
 * M272: Kennfeldthermostat; ein offen hängendes Thermostat (typisch P0128) zeigt sich
 * als langsamer Warmlauf und vor allem als Temperaturabfall bei zügiger Fahrt in kühler
 * Umgebung. Die Schwellen sind Heuristiken, keine Herstellerwerte. Thread-safe.
 */
public final class WarmupCheck {
    /** Bis zu dieser Kühlmitteltemperatur gilt der Start als Kaltstart. */
    static final double COLD_START_MAX = 50;
    static final double WARM = 80;
    /** Ab hier gilt der Motor als betriebswarm; erst danach zählt ein Abfall. */
    static final double OPERATING = 85;
    static final double CRUISE_SPEED = 60;
    static final double DRIVING_SPEED = 5;
    /** So lange muss die Temperatur bei zügiger Fahrt unter WARM liegen, um als Abfall zu gelten. */
    static final long DROP_MIN_MS = 2 * 60_000;
    /** Ohne 80 °C nach so viel Fahrzeit ab Kaltstart: langsamer Warmlauf. */
    static final long SLOW_WARMUP_MS = 20 * 60_000;
    /** Ohne Kaltstart braucht die Aussage so viel zügige Fahrt nach Erreichen der Betriebstemperatur. */
    static final long MIN_CRUISE_MS = 5 * 60_000;
    /** Größere Lücken (z. B. Reconnect) werden nicht als Fahr-/Abfallzeit gezählt. */
    static final long MAX_GAP_MS = 10_000;

    public enum Verdict { OK, SUSPICIOUS, INCONCLUSIVE }

    private boolean running;
    private long startedAt;
    private long lastTick;
    private Double startCoolant;
    private Double startIntake;
    private Long timeTo60;
    private Long timeTo80;
    private double maxCoolant = Double.NaN;
    private boolean reachedOperating;
    private double minAfterOperating = Double.NaN;
    private long drivingMs;
    private long cruiseMs;
    private long cruiseAfterOperatingMs;
    private long dropSince;
    private long longestDropMs;
    private double dropMin = Double.NaN;

    public synchronized void start(long now) {
        running = true;
        startedAt = now;
        lastTick = now;
        startCoolant = null;
        startIntake = null;
        timeTo60 = null;
        timeTo80 = null;
        maxCoolant = Double.NaN;
        reachedOperating = false;
        minAfterOperating = Double.NaN;
        drivingMs = 0;
        cruiseMs = 0;
        cruiseAfterOperatingMs = 0;
        dropSince = 0;
        longestDropMs = 0;
        dropMin = Double.NaN;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Ein Schritt mit den aktuellen Livewerten; liefert die Statuszeile (null, wenn nicht aktiv). */
    public synchronized String tick(Map<Integer, Double> values, long now) {
        if (!running) return null;
        long dt = now - lastTick;
        lastTick = now;
        if (dt < 0 || dt > MAX_GAP_MS) dt = 0;

        Double coolant = values.get(0x05);
        Double speed = values.get(0x0D);
        if (startCoolant == null && coolant != null) {
            startCoolant = coolant;
            startIntake = values.get(0x0F);
        }
        boolean driving = speed != null && speed >= DRIVING_SPEED;
        boolean cruising = speed != null && speed >= CRUISE_SPEED;
        if (driving) drivingMs += dt;
        if (cruising) cruiseMs += dt;

        if (coolant != null) {
            long elapsed = now - startedAt;
            if (timeTo60 == null && coolant >= 60) timeTo60 = elapsed;
            if (timeTo80 == null && coolant >= WARM) timeTo80 = elapsed;
            if (Double.isNaN(maxCoolant) || coolant > maxCoolant) maxCoolant = coolant;
            if (coolant >= OPERATING) reachedOperating = true;

            if (reachedOperating && cruising) {
                cruiseAfterOperatingMs += dt;
                if (Double.isNaN(minAfterOperating) || coolant < minAfterOperating) minAfterOperating = coolant;
                if (coolant < WARM) {
                    if (dropSince == 0) dropSince = now;
                    if (Double.isNaN(dropMin) || coolant < dropMin) dropMin = coolant;
                    longestDropMs = Math.max(longestDropMs, now - dropSince);
                } else {
                    dropSince = 0;
                }
            } else if (coolant >= WARM || !cruising) {
                dropSince = 0;
            }
        }
        return status(coolant, now);
    }

    private String status(Double coolant, long now) {
        String temp = coolant == null ? "– °C" : String.format(Locale.GERMANY, "%.0f °C", coolant);
        return "Warmlauf: " + temp + " · " + minutes(now - startedAt) + " seit Start · Fahrt "
                + minutes(drivingMs) + (longestDropMs >= DROP_MIN_MS ? " · Abfall bei Fahrt erkannt" : "");
    }

    /** Beendet den Check und liefert den Bericht. */
    public synchronized String finish(long now) {
        if (!running) return null;
        running = false;
        Verdict v = verdict();
        StringBuilder sb = new StringBuilder();
        sb.append(switch (v) {
            case OK -> "✅ Warmlauf unauffällig";
            case SUSPICIOUS -> "⚠️ Thermostat-Verdacht";
            case INCONCLUSIVE -> "ℹ️ Nicht aussagekräftig";
        }).append('\n').append(reason(v)).append("\n\n");

        sb.append("Dauer: ").append(minutes(now - startedAt)).append(" · davon Fahrt ").append(minutes(drivingMs))
                .append(" · über 60 km/h ").append(minutes(cruiseMs)).append('\n');
        sb.append("Kühlmittel beim Start: ").append(temp(startCoolant))
                .append(isColdStart() ? " (Kaltstart)" : " (kein Kaltstart)").append('\n');
        if (startIntake != null) sb.append("Ansaugluft beim Start: ").append(temp(startIntake)).append('\n');
        sb.append("60 °C erreicht nach: ").append(timeTo60 == null ? "–" : minutes(timeTo60)).append('\n');
        sb.append("80 °C erreicht nach: ").append(timeTo80 == null ? "–" : minutes(timeTo80)).append('\n');
        sb.append("Höchste Temperatur: ").append(Double.isNaN(maxCoolant) ? "–" : temp(maxCoolant)).append('\n');
        if (!Double.isNaN(minAfterOperating)) {
            sb.append("Niedrigste Temperatur bei Fahrt über 60 km/h nach Warmlauf: ").append(temp(minAfterOperating)).append('\n');
        }
        if (longestDropMs > 0) {
            sb.append("Längster Abfall unter 80 °C bei Fahrt: ").append(minutes(longestDropMs)).append('\n');
        }
        sb.append("\nM272 (Kennfeldthermostat): betriebswarm meist etwa 90–105 °C, unter Last auch darunter. ")
                .append("Ein offen hängendes Thermostat zeigt sich vor allem bei Landstraße oder Autobahn in ")
                .append("kühler Umgebung; typischer Fehlercode P0128. Die Schwellen dieses Checks sind Richtwerte, ")
                .append("keine Herstellerangaben.");
        return sb.toString();
    }

    public synchronized void cancel() {
        running = false;
    }

    synchronized Verdict verdict() {
        if (longestDropMs >= DROP_MIN_MS) return Verdict.SUSPICIOUS;
        if (isColdStart()) {
            if (timeTo80 == null) {
                return drivingMs >= SLOW_WARMUP_MS ? Verdict.SUSPICIOUS : Verdict.INCONCLUSIVE;
            }
            if (timeTo80 > SLOW_WARMUP_MS && drivingMs >= SLOW_WARMUP_MS / 2) return Verdict.SUSPICIOUS;
            return cruiseAfterOperatingMs >= MIN_CRUISE_MS || timeTo80 <= SLOW_WARMUP_MS
                    ? Verdict.OK : Verdict.INCONCLUSIVE;
        }
        return reachedOperating && cruiseAfterOperatingMs >= MIN_CRUISE_MS ? Verdict.OK : Verdict.INCONCLUSIVE;
    }

    private String reason(Verdict v) {
        if (v == Verdict.SUSPICIOUS) {
            if (longestDropMs >= DROP_MIN_MS) {
                return "Die Temperatur fiel nach dem Warmlauf bei Fahrt über 60 km/h für "
                        + minutes(longestDropMs) + " unter 80 °C (bis " + temp(dropMin)
                        + "). Das spricht für ein offen hängendes Thermostat.";
            }
            return timeTo80 == null
                    ? "Nach " + minutes(drivingMs) + " Fahrt ab Kaltstart wurden keine 80 °C erreicht."
                    : "Bis 80 °C hat es " + minutes(timeTo80) + " gedauert; bei Außentemperaturen über "
                    + "etwa 5 °C ist das zu langsam.";
        }
        if (v == Verdict.OK) {
            boolean cruiseChecked = cruiseAfterOperatingMs >= MIN_CRUISE_MS;
            if (isColdStart() && timeTo80 != null) {
                return "80 °C nach " + minutes(timeTo80) + " erreicht. " + (cruiseChecked
                        ? "Kein Abfall bei zügiger Fahrt."
                        : "Für die Prüfung auf Abfall bei zügiger Fahrt fehlten mindestens "
                        + minutes(MIN_CRUISE_MS) + " über 60 km/h.");
            }
            return "Betriebstemperatur bei zügiger Fahrt gehalten.";
        }
        if (isColdStart()) return "80 °C noch nicht erreicht – Fahrt zu kurz für eine Aussage.";
        return "Kein Kaltstart und zu wenig zügige Fahrt nach dem Warmlauf (mindestens "
                + minutes(MIN_CRUISE_MS) + " über 60 km/h).";
    }

    private boolean isColdStart() {
        return startCoolant != null && startCoolant <= COLD_START_MAX;
    }

    private static String temp(Double c) {
        return c == null ? "–" : String.format(Locale.GERMANY, "%.0f °C", c);
    }

    static String minutes(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.GERMANY, "%d:%02d min", s / 60, s % 60);
    }
}
