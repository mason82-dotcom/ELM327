package de.roman.obddashboard;

import static org.junit.Assert.assertEquals;

import de.roman.mercedesobdmonitor.ObdSession;
import org.junit.Test;

public class ResultRouterTest {
    @Test
    public void dtcReportByTitle() {
        assertEquals(ResultRouter.Target.DTC_DETAILS, new ResultRouter().route("OBD-II Fehlercodes", 0));
    }

    @Test
    public void diagnosisResultGoesToThePageOfTheOperation() {
        ResultRouter r = new ResultRouter();
        r.onBusy(ObdSession.Op.MISFIRE, true, 1000);
        r.onBusy(ObdSession.Op.MISFIRE, false, 4000);
        assertEquals(ResultRouter.Target.MODE06, r.route("Mode 06 · Aussetzer", 4001));
        r.onBusy(ObdSession.Op.INSPECTION, true, 5000);
        assertEquals(ResultRouter.Target.INSPECTION, r.route("HU/AU-Vorcheck", 5500));
        r.onBusy(ObdSession.Op.INSPECTION, false, 6000);
        r.onBusy(ObdSession.Op.MONITOR_TESTS, true, 7000);
        r.onBusy(ObdSession.Op.MONITOR_TESTS, false, 7100);
        assertEquals(ResultRouter.Target.MONITOR_TESTS, r.route("Mode 06 · Monitortests", 7200));
    }

    @Test
    public void lateMessagesAreDialogsNotStaleResults() {
        ResultRouter r = new ResultRouter();
        r.onBusy(ObdSession.Op.MISFIRE, true, 0);
        r.onBusy(ObdSession.Op.MISFIRE, false, 1000);
        assertEquals(ResultRouter.Target.DIALOG, r.route("Hinweis", 1000 + ResultRouter.OP_RESULT_WINDOW_MS + 1));
    }

    @Test
    public void trimAndWarmupInstructionsGoToTrim() {
        ResultRouter r = new ResultRouter();
        for (String t : new String[]{"Fuel-Trim-Test gestartet", "Leerlaufmessung abgeschlossen", "Fuel-Trim-Auswertung",
                "Warmlauf-Check gestartet", "Motor noch nicht warm"})
            assertEquals(t, ResultRouter.Target.TRIM, r.route(t, 0));
        assertEquals(ResultRouter.Target.DIALOG, r.route(null, 0));
    }
}
