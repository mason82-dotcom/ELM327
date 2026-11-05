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

    // Skalen der M272/C350-Anzeigen. Drehzahl: Begrenzer ca. 6500 U/min (Hinweis, keine Herstellerangabe).
    public static final GaugeScale RPM = new GaugeScale(0, 7000, 5500, 6300);
    public static final GaugeScale COOLANT = new GaugeScale(-20, 130, 108, 115);
    public static final GaugeScale SPEED = new GaugeScale(0, 260, Double.NaN, Double.NaN);
}
