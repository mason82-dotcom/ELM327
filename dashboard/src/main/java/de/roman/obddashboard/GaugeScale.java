package de.roman.obddashboard;

/**
 * Skala einer Rundanzeige (Android-frei, testbar): Wertebereich, Warn- und Alarmschwelle.
 * Werte außerhalb des Bereichs werden für die Zeigerstellung begrenzt, aber nie verändert angezeigt.
 */
public final class GaugeScale {
    public enum Zone { NORMAL, WARN, ALARM }

    public final double min;
    public final double max;
    /** Ab hier gelb; {@code Double.NaN} = keine Warnschwelle. */
    public final double warnFrom;
    /** Ab hier rot; {@code Double.NaN} = keine Alarmschwelle. */
    public final double alarmFrom;

    public GaugeScale(double min, double max, double warnFrom, double alarmFrom) {
        if (!(max > min)) throw new IllegalArgumentException("max must be > min");
        this.min = min;
        this.max = max;
        this.warnFrom = warnFrom;
        this.alarmFrom = alarmFrom;
    }

    /** Zeigerstellung 0…1. */
    public double fraction(double value) {
        if (Double.isNaN(value)) return 0;
        double f = (value - min) / (max - min);
        return Math.max(0, Math.min(1, f));
    }

    public Zone zone(double value) {
        if (Double.isNaN(value)) return Zone.NORMAL;
        if (!Double.isNaN(alarmFrom) && value >= alarmFrom) return Zone.ALARM;
        if (!Double.isNaN(warnFrom) && value >= warnFrom) return Zone.WARN;
        return Zone.NORMAL;
    }

    /** Drehzahlskala bis zur Profilgrenze; Warn-/Alarmbereich als Orientierung, keine Herstellerangabe. */
    public static GaugeScale rpm(int max) {
        return new GaugeScale(0, max, max * 0.785, max * 0.9);
    }

    // Skalen für das aktive Profil (C350 S204, M272): Drehzahl bis 7000 → gelb ab ca. 5500, rot ab 6300 U/min.
    public static final GaugeScale RPM = rpm(de.roman.mercedesobdmonitor.VehicleProfile.ACTIVE.chartMaxRpm);
    public static final GaugeScale COOLANT = new GaugeScale(-20, 130, 108, 115);
    public static final GaugeScale SPEED = new GaugeScale(0, 260, Double.NaN, Double.NaN);
}
