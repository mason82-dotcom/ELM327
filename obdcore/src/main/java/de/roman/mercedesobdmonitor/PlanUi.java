package de.roman.mercedesobdmonitor;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;

/** Gemeinsame Oberflächen-Hilfen beider Apps für den Prüfplan (vorher in jeder Activity kopiert). */
public final class PlanUi {
    private PlanUi() { }

    /** versionName der App oder „?“. */
    public static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * Prüfplan leeren mit Bestätigung, die die Anzahl nennt (nicht rückgängig zu machen).
     * @param theme Dialog-Theme oder 0 für das App-Theme
     */
    public static void confirmClear(Context c, int theme, ObdSession session, Runnable after) {
        int n = session.testPlanSize();
        if (n == 0) return;
        (theme == 0 ? new AlertDialog.Builder(c) : new AlertDialog.Builder(c, theme))
                .setTitle("Prüfplan leeren?")
                .setMessage(n + (n == 1 ? " gespeichertes Ergebnis wird" : " gespeicherte Ergebnisse werden")
                        + " gelöscht. Vorher teilen, wenn du sie noch brauchst.")
                .setPositiveButton("Leeren", (d, w) -> {
                    session.clearTestPlan();
                    after.run();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}
