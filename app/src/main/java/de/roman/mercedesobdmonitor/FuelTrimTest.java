package de.roman.mercedesobdmonitor;

import java.util.Locale;
import java.util.Map;

/**
 * Passive fuel-trim test. It never sends commands itself; it only evaluates
 * values already read by the normal Mode-01 polling loop.
 */
public final class FuelTrimTest {
    public enum Stage {
        IDLE, WAIT_IDLE, COLLECT_IDLE, WAIT_2500, COLLECT_2500, DONE, FAILED
    }

    public static final class Update {
        public final String status;
        public final boolean prompt2500;
        public final boolean completed;
        public final String report;
        /** true = Test wurde wegen Datenmangel abgebrochen (Stage FAILED). */
        public final boolean failed;

        Update(String status, boolean prompt2500, boolean completed, String report) {
            this(status, prompt2500, completed, report, false);
        }

        Update(String status, boolean prompt2500, boolean completed, String report, boolean failed) {
            this.status = status;
            this.prompt2500 = prompt2500;
            this.completed = completed;
            this.report = report;
            this.failed = failed;
        }
    }

    private static final long IDLE_STABLE_MS = 3000;
    private static final long IDLE_MEASURE_MS = 20000;
    private static final long HIGH_STABLE_MS = 3000;
    private static final long HIGH_MEASURE_MS = 15000;
    /** Mindestanzahl frischer Werte je Trim-PID und Phase. */
    static final int MIN_SAMPLES = 3;
    /** Maximale Verlängerung einer Messphase, wenn noch Werte fehlen. */
    static final long MAX_EXTRA_MS = 30000;

    private Stage stage = Stage.IDLE;
    private long stableSince;
    private long stageSince;
    private final Acc idle = new Acc();
    private final Acc high = new Acc();

    public synchronized void start() {
        idle.reset();
        high.reset();
        stage = Stage.WAIT_IDLE;
        stableSince = 0;
        stageSince = 0;
    }

    public synchronized void cancel() {
        stage = Stage.IDLE;
        stableSince = 0;
        stageSince = 0;
    }

    public synchronized boolean isRunning() {
        return stage != Stage.IDLE && stage != Stage.DONE && stage != Stage.FAILED;
    }

    public synchronized Stage getStage() {
        return stage;
    }

    public synchronized Update tick(Map<Integer, Double> values, Map<Integer, Long> sequences, long now) {
        double rpm = get(values, 0x0C);
        double speed = get(values, 0x0D);

        return switch (stage) {
            case IDLE -> new Update("Bereit", false, false, null);

            case WAIT_IDLE -> {
                boolean stable = inRange(rpm, 550, 1000) && (Double.isNaN(speed) || speed < 3);
                if (!stable) {
                    stableSince = 0;
                    yield new Update("Leerlauf herstellen: 550–1000 U/min, Fahrzeug steht", false, false, null);
                }
                if (stableSince == 0) stableSince = now;
                long left = Math.max(0, IDLE_STABLE_MS - (now - stableSince));
                if (left > 0) {
                    yield new Update("Leerlauf stabilisieren … " + secondsCeil(left) + " s", false, false, null);
                }
                idle.reset(sequences);
                stage = Stage.COLLECT_IDLE;
                stageSince = now;
                yield new Update("Leerlaufmessung gestartet", false, false, null);
            }

            case COLLECT_IDLE -> {
                if (!inRange(rpm, 500, 1100) || (!Double.isNaN(speed) && speed >= 3)) {
                    idle.reset(sequences);
                    stage = Stage.WAIT_IDLE;
                    stableSince = 0;
                    yield new Update("Leerlauf/Fahrzeugstand nicht stabil – Messung wird neu angesetzt", false, false, null);
                }
                idle.add(values, sequences);
                long elapsed = now - stageSince;
                if (elapsed >= IDLE_MEASURE_MS) {
                    if (!idle.enough()) {
                        if (elapsed >= IDLE_MEASURE_MS + MAX_EXTRA_MS) {
                            yield fail("Leerlauf", idle);
                        }
                        yield new Update("Leerlauf: warte auf frische Trim-Werte (" + idle.progress() + ") …", false, false, null);
                    }
                    stage = Stage.WAIT_2500;
                    stableSince = 0;
                    yield new Update("Leerlauf fertig · jetzt manuell ca. 2500 U/min halten", true, false, null);
                }
                yield new Update("Leerlauf messen … " + secondsCeil(IDLE_MEASURE_MS - elapsed) + " s", false, false, null);
            }

            case WAIT_2500 -> {
                boolean stable = inRange(rpm, 2200, 2800) && (Double.isNaN(speed) || speed < 3);
                if (!stable) {
                    stableSince = 0;
                    yield new Update("Drehzahl manuell auf 2300–2700 U/min bringen", false, false, null);
                }
                if (stableSince == 0) stableSince = now;
                long left = Math.max(0, HIGH_STABLE_MS - (now - stableSince));
                if (left > 0) {
                    yield new Update("2500 U/min stabilisieren … " + secondsCeil(left) + " s", false, false, null);
                }
                high.reset(sequences);
                stage = Stage.COLLECT_2500;
                stageSince = now;
                yield new Update("2500-U/min-Messung gestartet", false, false, null);
            }

            case COLLECT_2500 -> {
                if (!inRange(rpm, 2100, 2900) || (!Double.isNaN(speed) && speed >= 3)) {
                    high.reset(sequences);
                    stage = Stage.WAIT_2500;
                    stableSince = 0;
                    yield new Update("Drehzahl/Fahrzeugstand verlassen – 2500-U/min-Messung wird neu angesetzt", false, false, null);
                }
                high.add(values, sequences);
                long elapsed = now - stageSince;
                if (elapsed >= HIGH_MEASURE_MS) {
                    if (!high.enough()) {
                        if (elapsed >= HIGH_MEASURE_MS + MAX_EXTRA_MS) {
                            yield fail("2500 U/min", high);
                        }
                        yield new Update("2500 U/min: warte auf frische Trim-Werte (" + high.progress() + ") …", false, false, null);
                    }
                    stage = Stage.DONE;
                    String report = buildReport(idle, high);
                    yield new Update("Test abgeschlossen", false, true, report);
                }
                yield new Update("2500 U/min messen … " + secondsCeil(HIGH_MEASURE_MS - elapsed) + " s", false, false, null);
            }

            case DONE -> new Update("Test abgeschlossen", false, false, null);
            case FAILED -> new Update("abgebrochen · zu wenige frische Trim-Werte", false, false, null);
        };
    }

    private Update fail(String phase, Acc acc) {
        stage = Stage.FAILED;
        return new Update("abgebrochen · " + phase + ": zu wenige frische Trim-Werte (" + acc.progress()
                + "). Verbindung/Adapter zu langsam?", false, false, null, true);
    }

    private static String buildReport(Acc idle, Acc high) {
        double idle1 = idle.total1();
        double idle2 = idle.total2();
        double high1 = high.total1();
        double high2 = high.total2();

        StringBuilder sb = new StringBuilder();
        sb.append("Passiver Fuel-Trim-Test\n\n");
        sb.append(String.format(Locale.GERMANY,
                "Leerlauf:\nSTFT B1 %+.1f %% · LTFT B1 %+.1f %% · Gesamt B1 %+.1f %%\n" +
                "STFT B2 %+.1f %% · LTFT B2 %+.1f %% · Gesamt B2 %+.1f %%\n" +
                "MAF %s g/s · MAP %s kPa\n%s\n\n",
                idle.avgStft1(), idle.avgLtft1(), idle1,
                idle.avgStft2(), idle.avgLtft2(), idle2,
                fmt(idle.avgMaf(), "%.2f"), fmt(idle.avgMap(), "%.0f"), idle.sampleSummary()));

        sb.append(String.format(Locale.GERMANY,
                "≈2500 U/min:\nSTFT B1 %+.1f %% · LTFT B1 %+.1f %% · Gesamt B1 %+.1f %%\n" +
                "STFT B2 %+.1f %% · LTFT B2 %+.1f %% · Gesamt B2 %+.1f %%\n" +
                "MAF %s g/s · MAP %s kPa\n%s\n\n",
                high.avgStft1(), high.avgLtft1(), high1,
                high.avgStft2(), high.avgLtft2(), high2,
                fmt(high.avgMaf(), "%.2f"), fmt(high.avgMap(), "%.0f"), high.sampleSummary()));

        sb.append("Diagnosehinweise:\n");
        boolean hint = false;

        double idleDelta = Math.abs(idle1 - idle2);
        double highDelta = Math.abs(high1 - high2);
        if (idleDelta >= 5.0 || highDelta >= 5.0) {
            sb.append("• Deutlicher Unterschied zwischen Bank 1 und Bank 2 – bankspezifische Ursache mitprüfen.\n");
            hint = true;
        }

        boolean b1Drops = idle1 > 10.0 && high1 < idle1 - 5.0;
        boolean b2Drops = idle2 > 10.0 && high2 < idle2 - 5.0;
        if (b1Drops || b2Drops) {
            sb.append("• Positive Korrektur ist im Leerlauf deutlich höher und fällt bei 2500 U/min ab: Muster passt eher zu Falschluft/Ansaugundichtigkeit.\n");
            hint = true;
        }

        boolean b1HighBoth = idle1 > 10.0 && high1 > 10.0;
        boolean b2HighBoth = idle2 > 10.0 && high2 > 10.0;
        if (b1HighBoth || b2HighBoth) {
            sb.append("• Positive Korrektur bleibt auch bei 2500 U/min hoch: Kraftstoffversorgung, MAF und Lambdaregelung mitprüfen.\n");
            hint = true;
        }

        if (idle1 < -10.0 || idle2 < -10.0 || high1 < -10.0 || high2 < -10.0) {
            sb.append("• Deutlich negative Korrektur: mögliche Überfettung bzw. zu hoch erfasste Luft-/Kraftstoffmenge prüfen.\n");
            hint = true;
        }

        if (!hint) {
            sb.append("• In diesem Test ergibt sich kein eindeutiges Muster oberhalb der ±10-%-Orientierungsgrenze.\n");
        }

        sb.append("\nHinweis: Das ist eine Diagnosehilfe, keine herstellerspezifische Grenzwertprüfung.");
        return sb.toString();
    }

    private static boolean inRange(double value, double min, double max) {
        return !Double.isNaN(value) && value >= min && value <= max;
    }

    private static long secondsCeil(long ms) {
        return Math.max(1, (ms + 999) / 1000);
    }

    private static double get(Map<Integer, Double> values, int pid) {
        Double v = values.get(pid);
        return v == null ? Double.NaN : v;
    }

    private static String fmt(double v, String pattern) {
        return Double.isNaN(v) ? "–" : String.format(Locale.GERMANY, pattern, v);
    }

    /** Mittelwert eines PIDs, der nur frisch eingelesene Werte (neue Sequenznummer) zählt. */
    private static final class Channel {
        final int pid;
        double sum;
        int count;
        long lastSeq;

        Channel(int pid) { this.pid = pid; }

        void reset(Map<Integer, Long> sequences) {
            sum = 0.0;
            count = 0;
            Long s = sequences == null ? null : sequences.get(pid);
            lastSeq = s == null ? 0L : s;
        }

        void add(Map<Integer, Double> values, Map<Integer, Long> sequences) {
            Double v = values.get(pid);
            Long s = sequences.get(pid);
            if (v == null || s == null || s <= lastSeq || Double.isNaN(v)) return;
            sum += v;
            count++;
            lastSeq = s;
        }

        double avg() { return count == 0 ? Double.NaN : sum / count; }
    }

    /**
     * Mittelt jeden PID unabhängig. Schnelle STFT-Werte gehen damit vollständig ein,
     * auch wenn LTFT seltener aktualisiert wird; kein Wert wird doppelt gezählt.
     */
    private static final class Acc {
        private final Channel stft1 = new Channel(0x06);
        private final Channel ltft1 = new Channel(0x07);
        private final Channel stft2 = new Channel(0x08);
        private final Channel ltft2 = new Channel(0x09);
        private final Channel maf = new Channel(0x10);
        private final Channel map = new Channel(0x0B);
        private final Channel[] all = {stft1, ltft1, stft2, ltft2, maf, map};
        private final Channel[] trims = {stft1, ltft1, stft2, ltft2};

        void reset() { reset(null); }

        void reset(Map<Integer, Long> sequences) {
            for (Channel c : all) c.reset(sequences);
        }

        void add(Map<Integer, Double> values, Map<Integer, Long> sequences) {
            for (Channel c : all) c.add(values, sequences);
        }

        boolean enough() {
            for (Channel c : trims) if (c.count < MIN_SAMPLES) return false;
            return true;
        }

        String progress() {
            return String.format(Locale.GERMANY, "STFT %d/%d · LTFT %d/%d, min. %d",
                    stft1.count, stft2.count, ltft1.count, ltft2.count, MIN_SAMPLES);
        }

        String sampleSummary() {
            return String.format(Locale.GERMANY, "Werte: STFT B1/B2 %d/%d · LTFT B1/B2 %d/%d",
                    stft1.count, stft2.count, ltft1.count, ltft2.count);
        }

        int count(int pid) {
            for (Channel c : all) if (c.pid == pid) return c.count;
            return 0;
        }

        double avgStft1() { return stft1.avg(); }
        double avgLtft1() { return ltft1.avg(); }
        double avgStft2() { return stft2.avg(); }
        double avgLtft2() { return ltft2.avg(); }
        double avgMaf() { return maf.avg(); }
        double avgMap() { return map.avg(); }
        double total1() { return avgStft1() + avgLtft1(); }
        double total2() { return avgStft2() + avgLtft2(); }
    }

    /** Nur für Tests: Anzahl gezählter Werte je PID der Leerlaufphase. */
    synchronized int idleSampleCount(int pid) { return idle.count(pid); }

    /** Nur für Tests: Anzahl gezählter Werte je PID der 2500-U/min-Phase. */
    synchronized int highSampleCount(int pid) { return high.count(pid); }
}
