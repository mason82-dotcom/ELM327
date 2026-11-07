package de.roman.obddashboard;

import de.roman.mercedesobdmonitor.ObdSession;

/**
 * Ordnet Meldungen der Sitzung einer Dashboard-Seite zu (Android-frei, testbar).
 * Diagnose-Ergebnisse kommen mit der Operation, die sie erzeugt hat ({@link ObdSession.Listener#onResult}),
 * und landen eindeutig auf ihrer Seite – ohne Zeitfenster oder Titelvergleich. Anleitungen des
 * Fuel-Trim-Tests und des Warmlauf-Checks bleiben Dialoge, weil sie eine Handlung verlangen.
 */
public final class ResultRouter {
    public enum Target { DTC_DETAILS, MODE06, INSPECTION, MONITOR_TESTS, TRIM, DIALOG }

    private ResultRouter() { }

    /** @param op Operation des Ergebnisses oder {@code null} für einen gewöhnlichen Dialog */
    public static Target route(ObdSession.Op op, String title) {
        if (op != null) {
            switch (op) {
                case DTC: return Target.DTC_DETAILS;
                case MISFIRE: return Target.MODE06;
                case INSPECTION: return Target.INSPECTION;
                case MONITOR_TESTS: return Target.MONITOR_TESTS;
                default: break;
            }
        }
        String t = title == null ? "" : title;
        if (t.startsWith("Fuel-Trim") || t.startsWith("Leerlaufmessung") || t.startsWith("Warmlauf")
                || t.startsWith("Motor noch nicht warm")) return Target.TRIM;
        return Target.DIALOG;
    }
}
