package de.roman.mercedesobdmonitor;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Auswertungen aus Standard-Live-Werten (Android-frei, testbar). Grundlage: Recherche „C350 M272
 * Diagnosefunktionen“ (07.11.2026). Formeln folgen SAE J1979 und der Physik; alle Schwellen sind
 * Richtwerte ohne Herstellerangabe und werden in der Oberfläche auch so bezeichnet. Ein fehlender
 * Eingangswert ergibt immer {@code null} – nie einen geschätzten Ersatzwert.
 */
public final class EngineAnalysis {
    private EngineAnalysis() { }

    /** Hubraum M272 (3,498 l) in m³. */
    public static final double DISPLACEMENT_M3 = 0.003498;
    /** Stöchiometrisches Luft-Kraftstoff-Verhältnis Benzin. */
    public static final double STOICH_AFR = 14.7;
    /** Dichte Superbenzin in g/l (Annahme, Kalibrierung über einen Tankzyklus möglich). */
    public static final double FUEL_DENSITY_G_PER_L = 745.0;
    /** Kraftstoffsystem-Status „offen wegen Last/Schub“ (01 03). */
    public static final int FUEL_STATUS_OPEN_LOAD = 4;

    // ------------------------------------------------------------------------------- Verbrauch

    /**
     * Momentanverbrauch in l/h aus der Luftmasse. λ_soll (01 44) korrigiert Anreicherung; fehlt er, gilt λ = 1.
     * Schubabschaltung (Status 4, Drosselklappe geschlossen, Drehzahl über Leerlauf) ergibt 0 l/h.
     */
    public static Double fuelRateLitersPerHour(Double mafGs, Double lambdaCommanded, Integer fuelStatus,
                                               Double throttleRelPercent, Double rpm) {
        if (mafGs == null || mafGs < 0) return null;
        if (isOverrun(lambdaCommanded, fuelStatus, throttleRelPercent, rpm)) return 0.0;
        double lambda = lambdaCommanded == null || lambdaCommanded <= 0.5 || lambdaCommanded >= LAMBDA_SATURATED ? 1.0 : lambdaCommanded;
        double fuelGs = mafGs / (STOICH_AFR * lambda);
        return fuelGs * 3600.0 / FUEL_DENSITY_G_PER_L;
    }

    /**
     * Rohwert FFFF eines λ-PIDs (01 44: 1,99997; Breitband 01 24–2B/34–3B: 1,99997): Messbereichsende. Am C350
     * (Log 07.11.2026) meldet das ME9.7 so beim Soll-Lambda die Schubabschaltung und die Breitbandsonden im Schub.
     */
    public static final double LAMBDA_SATURATED = 1.999;
    /**
     * Relative Drosselklappe (01 45), bis zu der sie als geschlossen gilt. Am C350 meldet die geschlossene Klappe
     * 0,8–1,6 % (Rohwert 02–04, Log 07.11.2026) – die frühere Schwelle 1 % erkannte den Schub fast nie.
     */
    public static final double THROTTLE_CLOSED_REL = 3.0;

    /** true bei Messbereichsende (Rohwert FFFF), siehe {@link #LAMBDA_SATURATED}. */
    public static boolean isLambdaSaturated(Double lambda) {
        return lambda != null && lambda >= LAMBDA_SATURATED;
    }

    /**
     * Schubabschaltung: Soll-Lambda am Messbereichsende (FFFF) oder Status „offen wegen Last/Schub“ bei
     * geschlossener Drosselklappe und Drehzahl über Leerlauf.
     */
    public static boolean isOverrun(Double lambdaCommanded, Integer fuelStatus, Double throttleRelPercent, Double rpm) {
        if (isLambdaSaturated(lambdaCommanded)) return true;
        return fuelStatus != null && fuelStatus == FUEL_STATUS_OPEN_LOAD && throttleRelPercent != null
                && throttleRelPercent < THROTTLE_CLOSED_REL && rpm != null && rpm > 1100;
    }

    /** l/100 km; erst ab 5 km/h sinnvoll, darunter null (Stand: l/h zählt). */
    public static Double fuelPer100Km(Double litersPerHour, Double speedKmh) {
        if (litersPerHour == null || speedKmh == null || speedKmh < 5) return null;
        return litersPerHour / speedKmh * 100.0;
    }

    // ----------------------------------------------------------------------- Liefergrad (HFM)

    /**
     * Liefergrad η = gemessene / theoretische Luftmasse (Speed-Density mit Saugrohrdruck und Ansaugluft).
     * Deutlich unter dem üblichen Band bei gleicher Last: Luftmassenmesser misst zu wenig oder Falschluft.
     */
    public static Double volumetricEfficiency(Double mafGs, Double mapKpa, Double intakeAirC, Double rpm) {
        if (mafGs == null || mapKpa == null || intakeAirC == null || rpm == null || rpm < 400 || mapKpa <= 0) return null;
        double rho = mapKpa * 1000.0 / (287.05 * (intakeAirC + 273.15));          // kg/m³
        double theoreticalGs = rho * DISPLACEMENT_M3 * rpm / 120.0 * 1000.0;      // 4-Takt: n/2 Ansaugtakte
        return theoreticalGs <= 0 ? null : mafGs / theoreticalGs;
    }

    /** Richtwert-Einordnung des Liefergrads (Saugmotor, betriebswarm). */
    public static String volumetricVerdict(Double eta) {
        if (eta == null) return "–";
        if (eta < 0.60) return "auffällig niedrig (HFM/Falschluft prüfen)";
        if (eta > 1.10) return "auffällig hoch (HFM-Kennlinie prüfen)";
        return "unauffällig";
    }

    // ------------------------------------------------------------------ Lambda-Regelgüte je Bank

    /** |λ_ist − λ_soll| einer Bank; nur im geregelten Betrieb (01 03 = 2) aussagekräftig. */
    public static Double lambdaDeviation(Double lambdaActual, Double lambdaCommanded, Integer fuelStatus) {
        if (lambdaActual == null || lambdaCommanded == null || fuelStatus == null || fuelStatus != 2) return null;
        return Math.abs(lambdaActual - lambdaCommanded);
    }

    public static String lambdaVerdict(Double deviationBank1, Double deviationBank2) {
        if (deviationBank1 == null && deviationBank2 == null) return "nur im geregelten Betrieb (Status „geregelt“)";
        StringBuilder sb = new StringBuilder();
        if (deviationBank1 != null && deviationBank1 > 0.02) sb.append("Bank 1 weicht ab; ");
        if (deviationBank2 != null && deviationBank2 > 0.02) sb.append("Bank 2 weicht ab; ");
        if (deviationBank1 != null && deviationBank2 != null && Math.abs(deviationBank1 - deviationBank2) > 0.02)
            sb.append("Bänke ungleich; ");
        return sb.length() == 0 ? "unauffällig (Abweichung ≤ 0,02)" : sb.substring(0, sb.length() - 2);
    }

    // ---------------------------------------------------------------------------- E-Gas

    /** Verhältnis der beiden Pedalgeber D/E; bei Bosch-Pedalen über den Pedalweg nahezu konstant. */
    public static Double pedalRatio(Double pedalD, Double pedalE) {
        if (pedalD == null || pedalE == null || pedalE < 5 || pedalD < 5) return null;
        return pedalD / pedalE;
    }

    /** Soll-/Ist-Drosselklappe stationär (Richtwert > 5 % Abweichung = Verschmutzung/Steller prüfen). */
    public static Double throttleTracking(Double throttleCommanded, Double throttleActualRel) {
        if (throttleCommanded == null || throttleActualRel == null) return null;
        return throttleActualRel - throttleCommanded;
    }

    // ---------------------------------------------------------------------------- Bordnetz

    /**
     * Einordnung der Steuergerätespannung (01 42). Laufender Motor: unter 13,0 V auffällig – das Mercedes-
     * Energiemanagement regelt zeitweise tiefer, daher nur Hinweis. Motor aus: unter 12,2 V Batterie schwach.
     */
    public static String voltageVerdict(Double volts, Double rpm) {
        if (volts == null) return "–";
        boolean running = rpm != null && rpm > 400;
        if (running) {
            if (volts < 13.0) return "niedrig für laufenden Motor (Lichtmaschine/Energiemanagement prüfen)";
            if (volts > 14.9) return "zu hoch (Laderegler prüfen)";
            return "Ladespannung unauffällig";
        }
        if (volts < 12.2) return "Batterie schwach geladen";
        return "Ruhespannung unauffällig";
    }

    // --------------------------------------------------------------------- Kat-Tendenz

    /**
     * Tendenz der Katalysator-Speicherfähigkeit aus der Sonde nach Kat (Sprungsonde, 01 15 / 01 19):
     * Ein intakter Kat glättet die Sauerstoffschwankung, die Nachkat-Spannung bleibt ruhig und eher fett
     * (etwa 0,6–0,8 V). Ein schwacher Kat lässt die Schwankung durch. Ausgewertet werden nur Proben im
     * geregelten, betriebswarmen Zustand. Ersetzt nicht den Kat-Monitor der ECU (Mode 06, MID 21/22).
     */
    public static final class CatTendency {
        public static final int WINDOW = 120;
        public static final int MIN_SAMPLES = 40;
        private final ArrayDeque<Double> post = new ArrayDeque<>();

        /** Probe aufnehmen; nur geregelt (Status 2) und Kühlmittel ≥ 70 °C. */
        public void add(Double postCatVolts, Integer fuelStatus, Double coolantC) {
            if (postCatVolts == null || fuelStatus == null || fuelStatus != 2 || coolantC == null || coolantC < 70) return;
            post.addLast(postCatVolts);
            while (post.size() > WINDOW) post.removeFirst();
        }

        public void reset() {
            post.clear();
        }

        public int samples() {
            return post.size();
        }

        public Double mean() {
            if (post.isEmpty()) return null;
            double s = 0;
            for (double v : post) s += v;
            return s / post.size();
        }

        public Double stdDev() {
            if (post.size() < 2) return null;
            double m = mean(), s = 0;
            for (double v : post) s += (v - m) * (v - m);
            return Math.sqrt(s / (post.size() - 1));
        }

        public String verdict() {
            if (post.size() < MIN_SAMPLES) {
                return String.format(Locale.GERMANY, "sammelt Werte (%d/%d, nur geregelt und warm)", post.size(), MIN_SAMPLES);
            }
            double sd = stdDev(), m = mean();
            String base = String.format(Locale.GERMANY, "Ø %.2f V, Schwankung %.3f V – ", m, sd);
            if (sd > 0.20) return base + "Kat glättet kaum (Richtwert; Mode 06 Kat-Monitor prüfen)";
            if (sd > 0.10) return base + "Kat-Speicher nimmt ab (Richtwert)";
            return base + "Kat glättet gut";
        }
    }

    // ------------------------------------------------------------------ Trim-Karte je Bank

    /**
     * Passive Trim-Karte: Gesamtkorrektur (STFT + LTFT) je Bank, getrennt nach Leerlauf, Teillast und hoher
     * Last – nur im geregelten, betriebswarmen Betrieb. Das Muster über die Last unterscheidet Falschluft
     * (Korrektur im Leerlauf hoch, fällt mit Last) von einer bankspezifischen oder lastunabhängigen Ursache.
     * Schwellen wie im Fuel-Trim-Test (Bankdifferenz ≥ 5 %, Orientierungsgrenze ±10 %). Anlass: Log C350
     * 07.11.2026 – Bank 1 durchgehend etwa +11 %, Bank 2 etwa +4 %, über alle Lastbereiche.
     */
    public static final class TrimMap {
        public enum Band { IDLE, PART, HIGH }
        public static final int MIN_SAMPLES = 20;
        /** Berechnete Last (01 04), ab der „hohe Last“ gilt. */
        public static final double HIGH_LOAD = 50.0;
        private final double[] sum1 = new double[3], sum2 = new double[3];
        private final int[] count = new int[3];

        /** Band einer Probe; null, wenn nicht eindeutig (z. B. fehlende Werte). */
        public static Band band(Double rpm, Double speed, Double load) {
            if (rpm == null || load == null) return null;
            if (speed != null && speed < 3 && rpm < 1000) return Band.IDLE;
            if (speed == null) return null;
            return load >= HIGH_LOAD ? Band.HIGH : Band.PART;
        }

        /** Probe aufnehmen; nur geregelt (Status 2), Kühlmittel ≥ 70 °C, alle vier Trims vorhanden. */
        public void add(Double stft1, Double ltft1, Double stft2, Double ltft2, Integer fuelStatus, Double coolantC,
                        Double rpm, Double speed, Double load) {
            if (stft1 == null || ltft1 == null || stft2 == null || ltft2 == null) return;
            if (fuelStatus == null || fuelStatus != 2 || coolantC == null || coolantC < 70) return;
            Band b = band(rpm, speed, load);
            if (b == null) return;
            sum1[b.ordinal()] += stft1 + ltft1;
            sum2[b.ordinal()] += stft2 + ltft2;
            count[b.ordinal()]++;
        }

        public void reset() {
            java.util.Arrays.fill(sum1, 0);
            java.util.Arrays.fill(sum2, 0);
            java.util.Arrays.fill(count, 0);
        }

        public int samples(Band b) {
            return count[b.ordinal()];
        }

        /** Mittlere Gesamtkorrektur der Bank (1/2) im Band; null unter {@link #MIN_SAMPLES} Proben. */
        public Double mean(int bank, Band b) {
            int n = count[b.ordinal()];
            if (n < MIN_SAMPLES) return null;
            return (bank == 1 ? sum1 : sum2)[b.ordinal()] / n;
        }

        /** Tabelle je Band, z. B. „Leerlauf  B1 +10,2 % · B2 +3,1 % (n 160)“. */
        public String table() {
            StringBuilder sb = new StringBuilder();
            String[] names = {"Leerlauf", "Teillast", "hohe Last"};
            for (Band b : Band.values()) {
                Double m1 = mean(1, b), m2 = mean(2, b);
                sb.append(names[b.ordinal()]).append(": ");
                if (m1 == null) {
                    sb.append(String.format(Locale.GERMANY, "sammelt (%d/%d)", count[b.ordinal()], MIN_SAMPLES));
                } else {
                    sb.append(String.format(Locale.GERMANY, "B1 %+.1f %% · B2 %+.1f %% (n %d)", m1, m2, count[b.ordinal()]));
                }
                if (b != Band.HIGH) sb.append('\n');
            }
            return sb.toString();
        }

        public String verdict() {
            int bands = 0;
            for (Band b : Band.values()) if (mean(1, b) != null) bands++;
            if (bands == 0) return "sammelt Werte (nur geregelt und warm)";
            StringBuilder sb = new StringBuilder();
            Double i1 = mean(1, Band.IDLE), i2 = mean(2, Band.IDLE);
            Double loaded1 = loaded(1), loaded2 = loaded(2);
            // Bankdifferenz: in jedem verfügbaren Band ≥ 5 % mit gleichem Vorzeichen → bankspezifisch
            int sign = 0;
            boolean consistent = true;
            double maxDiff = 0;
            for (Band b : Band.values()) {
                Double m1 = mean(1, b), m2 = mean(2, b);
                if (m1 == null) continue;
                double d = m1 - m2;
                int s = Math.abs(d) >= 5.0 ? (int) Math.signum(d) : 0;
                if (s == 0 || (sign != 0 && s != sign)) consistent = false;
                sign = s == 0 ? sign : s;
                maxDiff = Math.max(maxDiff, Math.abs(d));
            }
            if (consistent && sign != 0) {
                int lean = sign > 0 ? 1 : 2;
                sb.append(String.format(Locale.GERMANY,
                        "Bank %d wird in allen Lastbereichen stärker angefettet (bis %.1f %% mehr) – bankspezifisch: "
                                + "Abgasundichtigkeit vor der Vorkat-Sonde, Einspritzventile oder Vorkat-Sonde Bank %d prüfen; ", lean, maxDiff, lean));
            } else if (maxDiff >= 5.0) {
                sb.append("Bänke unterscheiden sich nur in einzelnen Lastbereichen; ");
            }
            if ((i1 != null && loaded1 != null && i1 > 10 && loaded1 < i1 - 5)
                    || (i2 != null && loaded2 != null && i2 > 10 && loaded2 < i2 - 5)) {
                sb.append("Korrektur im Leerlauf hoch und mit Last fallend – passt zu Falschluft; ");
            }
            if ((i1 != null && i1 > 10 && loaded1 != null && loaded1 > 10) || (i2 != null && i2 > 10 && loaded2 != null && loaded2 > 10)) {
                sb.append("auch unter Last über +10 % – Kraftstoffversorgung/HFM mitprüfen; ");
            }
            for (int bank = 1; bank <= 2; bank++) {
                for (Band b : Band.values()) {
                    Double m = mean(bank, b);
                    if (m != null && m < -10) {
                        sb.append("Bank ").append(bank).append(" deutlich negativ – Überfettung prüfen; ");
                        break;
                    }
                }
            }
            if (sb.length() == 0) return "unauffällig (innerhalb ±10 %, Bänke gleichlaufend)";
            return sb.substring(0, sb.length() - 2) + " (Richtwert, keine Diagnose)";
        }

        /** Mittel der Lastbänder Teillast/hohe Last einer Bank (gewichtet), null ohne Daten. */
        private Double loaded(int bank) {
            double s = 0;
            int n = 0;
            for (Band b : new Band[]{Band.PART, Band.HIGH}) {
                if (count[b.ordinal()] < MIN_SAMPLES) continue;
                s += (bank == 1 ? sum1 : sum2)[b.ordinal()];
                n += count[b.ordinal()];
            }
            return n == 0 ? null : s / n;
        }
    }
}
