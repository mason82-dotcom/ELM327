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
        if (fuelStatus != null && fuelStatus == FUEL_STATUS_OPEN_LOAD && throttleRelPercent != null
                && throttleRelPercent < 1.0 && rpm != null && rpm > 1100) return 0.0;
        double lambda = lambdaCommanded == null || lambdaCommanded <= 0.5 || lambdaCommanded > 2.0 ? 1.0 : lambdaCommanded;
        double fuelGs = mafGs / (STOICH_AFR * lambda);
        return fuelGs * 3600.0 / FUEL_DENSITY_G_PER_L;
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
}
