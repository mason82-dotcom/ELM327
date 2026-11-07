package de.roman.obddashboard;

import static org.junit.Assert.assertEquals;

import de.roman.mercedesobdmonitor.ObdSession;
import org.junit.Test;

public class ResultRouterTest {
    @Test
    public void resultsGoToThePageOfTheirOperation() {
        assertEquals(ResultRouter.Target.DTC_DETAILS, ResultRouter.route(ObdSession.Op.DTC, "OBD-II Fehlercodes"));
        assertEquals(ResultRouter.Target.MODE06, ResultRouter.route(ObdSession.Op.MISFIRE, "Aussetzer-Analyse"));
        assertEquals(ResultRouter.Target.INSPECTION, ResultRouter.route(ObdSession.Op.INSPECTION, "HU/AU-Vorab-Check"));
        assertEquals(ResultRouter.Target.MONITOR_TESTS, ResultRouter.route(ObdSession.Op.MONITOR_TESTS, "Monitortests (Mode 06)"));
    }

    @Test
    public void routingDoesNotDependOnTitlesOrTiming() {
        // A renamed result title or a result replayed after the activity was recreated still lands on its page.
        assertEquals(ResultRouter.Target.MODE06, ResultRouter.route(ObdSession.Op.MISFIRE, "irgendein Titel"));
        // A plain dialog is never taken for a stale diagnosis result.
        assertEquals(ResultRouter.Target.DIALOG, ResultRouter.route(null, "OBD-II Fehlercodes"));
        assertEquals(ResultRouter.Target.DIALOG, ResultRouter.route(null, "Hinweis"));
        assertEquals(ResultRouter.Target.DIALOG, ResultRouter.route(null, null));
    }

    @Test
    public void trimAndWarmupInstructionsGoToTrim() {
        for (String t : new String[]{"Fuel-Trim-Test gestartet", "Leerlaufmessung abgeschlossen", "Fuel-Trim-Auswertung",
                "Warmlauf-Check gestartet", "Motor noch nicht warm"})
            assertEquals(t, ResultRouter.Target.TRIM, ResultRouter.route(null, t));
    }
}
