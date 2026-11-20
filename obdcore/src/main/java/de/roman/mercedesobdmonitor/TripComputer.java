package de.roman.mercedesobdmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fahrtenbuch aus Messwerten (Android-frei, testbar): erkennt Fahrten am laufenden Motor und integriert Strecke
 * (Tempo 01 0D), Kraftstoff (Luftmasse, wie {@link EngineAnalysis#fuelRateLitersPerHour}), Motorlaufzeit und
 * Leerlaufanteil. Gespeist wird es aus den CSV-Logs ({@link LogReplay}) – die schreibt der Dienst durchgehend, auch
 * wenn keine Oberfläche sichtbar ist. Alle Größen sind Richtwerte aus Standardwerten, kein Tankbeleg.
 */
public final class TripComputer {
    /** Motor gilt als laufend ab dieser Drehzahl. */
    public static final double RUNNING_RPM = 300;
    /** So lange Motor aus beendet eine Fahrt (Ampel-Start-Stopp hat der C350 nicht, Abwürgen schon). */
    public static final long ENGINE_OFF_MS = 30_000;
    /** So lange muss die Drehzahl unten bleiben, damit ein Laufzeit-Rücksprung als Neustart zählt. */
    public static final long STOP_CONFIRM_MS = 3_000;
    /** Lücke im Log, ab der die Fahrt endet (Verbindung weg, Log angehalten). */
    public static final long GAP_MS = 120_000;
    /**
     * Längster Abstand zweier Werte, über den noch integriert wird. Im normalen Abfragetakt kommt das Tempo oft nur
     * alle 5–6 s (Log 19.11.2026: 95. Perzentil 5,7 s); eine Grenze von 5 s hätte bis zur Hälfte der Strecke verloren
     * (Validierung: 2,3 statt 4,6 km). 15 s erfasst 95–100 % der Fahrzeit und überbrückt keine echten Pausen.
     */
    public static final long MAX_STEP_MS = 15_000;
    /** Unter diesem Anteil der Motorlaufzeit mit Luftmasse gilt der Verbrauch als unbekannt (nicht 0). */
    public static final double MIN_FUEL_COVERAGE = 0.5;
    /** Kürzere Fahrten (Motorlaufzeit) werden verworfen, etwa ein kurzes Anlassen in der Garage. */
    public static final long MIN_RUN_MS = 60_000;

    /** Eine abgeschlossene Fahrt. */
    public static final class Trip {
        public final long startMs;
        public final long endMs;
        public final double km;
        /** Kraftstoff in l; NaN, wenn die Luftmasse fehlte (z. B. Log ohne PID 10). */
        public final double liters;
        public final long runMs;
        public final long idleMs;
        public final double maxKmh;
        /** Gesamtkorrektur B1−B2 dieser Fahrt (Trim-Karte, geregelt und warm); null ohne genug Werte. */
        public final Double trimDiff;
        /**
         * true: das Teilstück endete nur, weil das Log zu Ende war (Motor lief noch) – das nächste Log kann es
         * fortsetzen. false: Motor aus, Neustart oder Lücke – eine echte Fahrtgrenze, die nie verbunden wird.
         */
        public final boolean openEnd;
        /** Trim-Karte dieser Fahrt für den Trim-Verlauf (nur frisch berechnet, nicht gespeichert); null ohne Werte. */
        public transient TrimHistory.Entry trimEntry;

        public Trip(long startMs, long endMs, double km, double liters, long runMs, long idleMs, double maxKmh, Double trimDiff) {
            this(startMs, endMs, km, liters, runMs, idleMs, maxKmh, trimDiff, false);
        }

        public Trip(long startMs, long endMs, double km, double liters, long runMs, long idleMs, double maxKmh,
                Double trimDiff, boolean openEnd) {
            this.openEnd = openEnd;
            this.startMs = startMs;
            this.endMs = endMs;
            this.km = km;
            this.liters = liters;
            this.runMs = runMs;
            this.idleMs = idleMs;
            this.maxKmh = maxKmh;
            this.trimDiff = trimDiff;
        }

        /** l/100 km; erst ab 1 km sinnvoll, sonst null. */
        public Double litersPer100Km() {
            return km < 1.0 || Double.isNaN(liters) ? null : liters / km * 100.0;
        }

        public boolean fuelKnown() {
            return !Double.isNaN(liters);
        }

        /**
         * Zwei Teilstücke derselben Fahrt verbinden (Logwechsel mitten in der Fahrt: 200 MB oder „Neues Log“). Summen
         * addieren sich, die Trim-Differenz wird nach Laufzeit gewichtet; Verbrauch nur, wenn beide ihn kennen.
         */
        public static Trip join(Trip a, Trip b) {
            Double trim;
            if (a.trimDiff == null) trim = b.trimDiff;
            else if (b.trimDiff == null) trim = a.trimDiff;
            else trim = (a.trimDiff * a.runMs + b.trimDiff * b.runMs) / Math.max(1, a.runMs + b.runMs);
            return new Trip(Math.min(a.startMs, b.startMs), Math.max(a.endMs, b.endMs), a.km + b.km,
                    a.fuelKnown() && b.fuelKnown() ? a.liters + b.liters : Double.NaN, a.runMs + b.runMs,
                    a.idleMs + b.idleMs, Math.max(a.maxKmh, b.maxKmh), trim, a.endMs >= b.endMs ? a.openEnd : b.openEnd);
        }

        /** Anteil Leerlauf (Motor läuft, Stand) an der Motorlaufzeit, 0–1. */
        public double idleShare() {
            return runMs <= 0 ? 0 : (double) idleMs / runMs;
        }
    }

    private final List<Trip> finished = new ArrayList<>();
    private LiveAnalysis state;
    private long start, last, offSince = -1;
    private double km, liters, maxKmh;
    private long runMs, idleMs, fuelMs;
    private long lastSpeedT = -1, lastMafT = -1, lastRpmT = -1;
    private Double lastSpeed, lastRuntime;
    private boolean stoppedSinceRuntime;

    /** Einen Messwert mit Zeitstempel (ms) aufnehmen; Werte müssen zeitlich aufsteigend kommen. */
    public void onSample(long t, int pid, double value) {
        if (state != null && (t - last > GAP_MS || t < last)) close(last, false);
        // Neustart: Laufzeit springt zurück UND der Motor stand dazwischen. Ein einzelner gestörter Laufzeitwert
        // (der ELM327-Simulator liefert sogar Zufallswerte) zerteilt so keine Fahrt.
        if (state != null && pid == 0x1F && lastRuntime != null && value + 5 < lastRuntime && stoppedSinceRuntime) close(last, false);
        if (pid == 0x1F) {
            lastRuntime = value;
            // erst zurücksetzen, wenn der Motor wieder läuft: ein 1F-Wert während des Stillstands löscht es nicht
            Double rpm = state == null ? null : state.sample(0x0C);
            if (rpm != null && rpm > RUNNING_RPM) stoppedSinceRuntime = false;
        }
        if (state == null) {
            if (pid != 0x0C || value <= RUNNING_RPM) return;
            begin(t);
        }
        Double rpmBefore = state.sample(0x0C);
        state.onSample(pid, value);
        last = t;
        switch (pid) {
            case 0x0C -> {
                boolean running = value > RUNNING_RPM;
                if (lastRpmT >= 0 && t - lastRpmT <= MAX_STEP_MS && rpmBefore != null && rpmBefore > RUNNING_RPM) {
                    long dt = t - lastRpmT;
                    runMs += dt;
                    Double v = state.sample(0x0D);
                    // Leerlauf nur mit aktuellem Tempo (höchstens MAX_STEP_MS alt), sonst entscheidet ein alter Wert
                    if (v != null && v < 3 && lastSpeedT >= 0 && t - lastSpeedT <= MAX_STEP_MS) idleMs += dt;
                }
                lastRpmT = t;
                if (running) {
                    offSince = -1;
                } else {
                    if (offSince < 0) offSince = t;
                    // Stillstand erst nach 3 s: einzelne Ausreißer auf 0 (Simulator) sind kein Motorstopp
                    if (t - offSince >= STOP_CONFIRM_MS) stoppedSinceRuntime = true;
                    if (t - offSince >= ENGINE_OFF_MS) close(offSince, false);
                }
            }
            case 0x0D -> {
                if (lastSpeedT >= 0 && t - lastSpeedT <= MAX_STEP_MS && lastSpeed != null) {
                    km += (lastSpeed + value) / 2.0 * (t - lastSpeedT) / 3_600_000.0;
                }
                lastSpeed = value;
                lastSpeedT = t;
                maxKmh = Math.max(maxKmh, value);
            }
            case 0x10 -> {
                if (lastMafT >= 0 && t - lastMafT <= MAX_STEP_MS) {
                    Integer status = state.fuelStatus();
                    Double lph = EngineAnalysis.fuelRateLitersPerHour(value, state.sample(0x44), status,
                            state.sample(0x45), state.sample(0x0C));
                    if (lph != null) {
                        liters += lph * (t - lastMafT) / 3_600_000.0;
                        fuelMs += t - lastMafT;
                    }
                }
                lastMafT = t;
            }
            default -> { }
        }
    }

    /** Laufende Fahrt abschließen (Ende des Logs). Lief der Motor zuletzt noch, bleibt das Ende offen. */
    public List<Trip> finish() {
        if (state != null) close(last, offSince < 0);
        return new ArrayList<>(finished);
    }

    private void begin(long t) {
        state = new LiveAnalysis();
        start = t;
        last = t;
        offSince = -1;
        km = liters = maxKmh = 0;
        runMs = idleMs = fuelMs = 0;
        lastRuntime = null;   // Rücksprung der Laufzeit zählt nur innerhalb einer Fahrt
        stoppedSinceRuntime = false;
        lastSpeedT = lastMafT = lastRpmT = -1;
        lastSpeed = null;
    }

    private void close(long end, boolean openEnd) {
        if (runMs >= MIN_RUN_MS) {
            TrimHistory.Entry e = TrimHistory.snapshot(start, end, state.trimMap);
            double fuel = fuelMs >= MIN_FUEL_COVERAGE * runMs ? liters : Double.NaN;
            Trip trip = new Trip(start, end, km, fuel, runMs, idleMs, maxKmh, e == null ? null : e.bankDifference(), openEnd);
            trip.trimEntry = e;
            finished.add(trip);
        }
        state = null;
        lastRuntime = null;
        stoppedSinceRuntime = false;
        offSince = -1;
    }

    // ------------------------------------------------------------------ Speichern

    /** Eine Zeile je Teilstück: Start;Ende;km;l;Lauf-ms;Leerlauf-ms;max km/h;Trim-Δ (leer = unbekannt);offen (1/0). */
    public static String serialize(List<Trip> trips) {
        StringBuilder sb = new StringBuilder();
        for (Trip t : trips) {
            sb.append(t.startMs).append(';').append(t.endMs).append(';')
                    .append(String.format(Locale.US, "%.3f;", t.km))
                    .append(t.fuelKnown() ? String.format(Locale.US, "%.3f", t.liters) : "").append(';')
                    .append(t.runMs).append(';').append(t.idleMs).append(';')
                    .append(String.format(Locale.US, "%.0f", t.maxKmh)).append(';')
                    .append(t.trimDiff == null ? "" : String.format(Locale.US, "%.2f", t.trimDiff)).append(';')
                    .append(t.openEnd ? '1' : '0').append('\n');
        }
        return sb.toString();
    }

    /** Liest {@link #serialize} (auch ältere Zeilen ohne „offen“); beschädigte Zeilen werden übersprungen. */
    public static List<Trip> parse(String text) {
        List<Trip> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split(";", -1);
            if (f.length != 8 && f.length != 9) continue;
            try {
                out.add(new Trip(Long.parseLong(f[0]), Long.parseLong(f[1]), Double.parseDouble(f[2]),
                        f[3].isEmpty() ? Double.NaN : Double.parseDouble(f[3]),
                        Long.parseLong(f[4]), Long.parseLong(f[5]), Double.parseDouble(f[6]),
                        f[7].isEmpty() ? null : Double.valueOf(f[7]), f.length == 9 && "1".equals(f[8])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
