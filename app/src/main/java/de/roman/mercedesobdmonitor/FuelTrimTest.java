package de.roman.mercedesobdmonitor;

import java.util.Locale;
import java.util.Map;

/**
 * Passive fuel-trim test. It never sends commands itself; it only evaluates
 * values already read by the normal Mode-01 polling loop.
 */
public final class FuelTrimTest {
    public enum Stage {
        IDLE, WAIT_IDLE, COLLECT_IDLE, WAIT_2500, COLLECT_2500, DONE
    }

    public static final class Update {
        public final String status;
        public final boolean prompt2500;
        public final boolean completed;
        public final String report;

        Update(String status, boolean prompt2500, boolean completed, String report) {
            this.status = status;
            this.prompt2500 = prompt2500;
            this.completed = completed;
            this.report = report;
        }
    }

    private static final long IDLE_STABLE_MS = 3000;
    private static final long IDLE_MEASURE_MS = 20000;
    private static final long HIGH_STABLE_MS = 3000;
    private static final long HIGH_MEASURE_MS = 15000;
    private static final long SAMPLE_INTERVAL_MS = 250;

    private Stage stage = Stage.IDLE;
    private long stableSince;
    private long stageSince;
    private long lastSample;
    private final Acc idle = new Acc();
    private final Acc high = new Acc();

    public synchronized void start() {
        idle.reset();
        high.reset();
        stage = Stage.WAIT_IDLE;
        stableSince = 0;
        stageSince = 0;
        lastSample = 0;
    }

    public synchronized void cancel() {
        stage = Stage.IDLE;
        stableSince = 0;
        stageSince = 0;
        lastSample = 0;
    }

    public synchronized boolean isRunning() {
        return stage != Stage.IDLE && stage != Stage.DONE;
    }

    public synchronized Stage getStage() {
        return stage;
    }

    public synchronized Update tick(Map<Integer, Double> values, long now) {
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
                idle.reset();
                stage = Stage.COLLECT_IDLE;
                stageSince = now;
                lastSample = 0;
                yield new Update("Leerlaufmessung gestartet", false, false, null);
            }

            case COLLECT_IDLE -> {
                if (!inRange(rpm, 500, 1100)) {
                    idle.reset();
                    stage = Stage.WAIT_IDLE;
                    stableSince = 0;
                    yield new Update("Leerlauf nicht stabil – Messung wird neu angesetzt", false, false, null);
                }
                if (now - lastSample >= SAMPLE_INTERVAL_MS) {
                    idle.add(values);
                    lastSample = now;
                }
                long elapsed = now - stageSince;
                if (elapsed >= IDLE_MEASURE_MS) {
                    stage = Stage.WAIT_2500;
                    stableSince = 0;
                    yield new Update("Leerlauf fertig · jetzt manuell ca. 2500 U/min halten", true, false, null);
                }
                yield new Update("Leerlauf messen … " + secondsCeil(IDLE_MEASURE_MS - elapsed) + " s", false, false, null);
            }

            case WAIT_2500 -> {
                boolean stable = inRange(rpm, 2200, 2800);
                if (!stable) {
                    stableSince = 0;
                    yield new Update("Drehzahl manuell auf 2300–2700 U/min bringen", false, false, null);
                }
                if (stableSince == 0) stableSince = now;
                long left = Math.max(0, HIGH_STABLE_MS - (now - stableSince));
                if (left > 0) {
                    yield new Update("2500 U/min stabilisieren … " + secondsCeil(left) + " s", false, false, null);
                }
                high.reset();
                stage = Stage.COLLECT_2500;
                stageSince = now;
                lastSample = 0;
                yield new Update("2500-U/min-Messung gestartet", false, false, null);
            }

            case COLLECT_2500 -> {
                if (!inRange(rpm, 2100, 2900)) {
                    high.reset();
                    stage = Stage.WAIT_2500;
                    stableSince = 0;
                    yield new Update("Drehzahl verlassen – 2500-U/min-Messung wird neu angesetzt", false, false, null);
                }
                if (now - lastSample >= SAMPLE_INTERVAL_MS) {
                    high.add(values);
                    lastSample = now;
                }
                long elapsed = now - stageSince;
                if (elapsed >= HIGH_MEASURE_MS) {
                    stage = Stage.DONE;
                    String report = buildReport(idle, high);
                    yield new Update("Test abgeschlossen", false, true, report);
                }
                yield new Update("2500 U/min messen … " + secondsCeil(HIGH_MEASURE_MS - elapsed) + " s", false, false, null);
            }

            case DONE -> new Update("Test abgeschlossen", false, false, null);
        };
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
                "MAF %.2f g/s · MAP %.0f kPa\n\n",
                idle.avgStft1(), idle.avgLtft1(), idle1,
                idle.avgStft2(), idle.avgLtft2(), idle2,
                idle.avgMaf(), idle.avgMap()));

        sb.append(String.format(Locale.GERMANY,
                "≈2500 U/min:\nSTFT B1 %+.1f %% · LTFT B1 %+.1f %% · Gesamt B1 %+.1f %%\n" +
                "STFT B2 %+.1f %% · LTFT B2 %+.1f %% · Gesamt B2 %+.1f %%\n" +
                "MAF %.2f g/s · MAP %.0f kPa\n\n",
                high.avgStft1(), high.avgLtft1(), high1,
                high.avgStft2(), high.avgLtft2(), high2,
                high.avgMaf(), high.avgMap()));

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

    private static final class Acc {
        private int count;
        private double stft1, ltft1, stft2, ltft2, maf, map;

        void reset() {
            count = 0;
            stft1 = ltft1 = stft2 = ltft2 = maf = map = 0.0;
        }

        void add(Map<Integer, Double> values) {
            Double a = values.get(0x06);
            Double b = values.get(0x07);
            Double c = values.get(0x08);
            Double d = values.get(0x09);
            Double e = values.get(0x10);
            Double f = values.get(0x0B);
            if (a == null || b == null || c == null || d == null) return;
            stft1 += a;
            ltft1 += b;
            stft2 += c;
            ltft2 += d;
            if (e != null) maf += e;
            if (f != null) map += f;
            count++;
        }

        double avgStft1() { return avg(stft1); }
        double avgLtft1() { return avg(ltft1); }
        double avgStft2() { return avg(stft2); }
        double avgLtft2() { return avg(ltft2); }
        double avgMaf() { return avg(maf); }
        double avgMap() { return avg(map); }
        double total1() { return avgStft1() + avgLtft1(); }
        double total2() { return avgStft2() + avgLtft2(); }

        private double avg(double sum) {
            return count == 0 ? Double.NaN : sum / count;
        }
    }
}
