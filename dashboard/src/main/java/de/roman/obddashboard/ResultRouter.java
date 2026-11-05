package de.roman.obddashboard;

import de.roman.mercedesobdmonitor.ObdSession;

/**
 * Ordnet Diagnose-Meldungen der Sitzung einer Dashboard-Seite zu (Android-frei, testbar).
 * Ergebnisse (DTC, Mode 06, HU/AU, Monitortests) erscheinen auf ihrer Seite statt in einem Pop-up;
 * Anleitungen des Fuel-Trim-Tests und des Warmlauf-Checks bleiben Dialoge, weil sie eine Handlung
 * verlangen (z. B. Drehzahl anheben).
 */
public final class ResultRouter {
    public enum Target { DTC_DETAILS, MODE06, INSPECTION, MONITOR_TESTS, TRIM, DIALOG }

    /** Ergebnis einer Diagnose, die gerade lief, gilt höchstens so lange als „ihr“ Ergebnis. */
    static final long OP_RESULT_WINDOW_MS = 3000;

    private ObdSession.Op lastOp;
    private long lastOpEndedAt = Long.MIN_VALUE;
    private boolean opRunning;

    public void onBusy(ObdSession.Op op, boolean busy, long nowMs) {
        if (busy) {
            lastOp = op;
            opRunning = true;
        } else if (op == lastOp) {
            opRunning = false;
            lastOpEndedAt = nowMs;
        }
    }

    public Target route(String title, long nowMs) {
        String t = title == null ? "" : title;
        if (t.startsWith("OBD-II Fehlercodes")) return Target.DTC_DETAILS;
        if (t.startsWith("Fuel-Trim") || t.startsWith("Leerlaufmessung") || t.startsWith("Warmlauf")
                || t.startsWith("Motor noch nicht warm")) return Target.TRIM;
        boolean fromOp = lastOp != null && (opRunning || nowMs - lastOpEndedAt <= OP_RESULT_WINDOW_MS);
        if (fromOp) {
            switch (lastOp) {
                case DTC: return Target.DTC_DETAILS;
                case MISFIRE: return Target.MODE06;
                case INSPECTION: return Target.INSPECTION;
                case MONITOR_TESTS: return Target.MONITOR_TESTS;
                default: break;
            }
        }
        return Target.DIALOG;
    }
}
