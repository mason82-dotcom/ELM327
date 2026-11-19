package de.roman.mercedesobdmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.junit.Test;

/** Gemeinsame Live-Auswertung beider Apps (vorher nur in der Dashboard-Activity). */
public class LiveAnalysisTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    public void emptyReportShowsDashesNotGuesses() {
        LiveAnalysis a = new LiveAnalysis();
        String r = a.report(null, BERLIN);
        assertTrue(r, r.startsWith("VERBRAUCH (aus Luftmasse)\n  –"));
        assertTrue(r, r.contains("TRIM-KARTE"));
        assertFalse("ohne Verlauf kein Verlaufsabschnitt", r.contains("TRIM-VERLAUF"));
        assertTrue(a.report(new TrimHistory(), BERLIN).contains("TRIM-VERLAUF"));
    }

    @Test
    public void samplesInvalidationAndLambdaFallback() {
        LiveAnalysis a = new LiveAnalysis();
        assertFalse(a.onSample(0x0C, 700));
        a.onSample(0x34, 0.99);
        assertEquals(0.99, a.lambdaBank(1), 0);          // Stromtyp als Ersatz
        a.onSample(0x24, 1.01);
        assertEquals(1.01, a.lambdaBank(1), 0);          // Spannungstyp zuerst
        a.onSample(0x03, 2.0);
        assertEquals(Integer.valueOf(2), a.fuelStatus());
        a.onInvalid(0x0C);
        assertNull(a.sample(0x0C));
        a.clearSamples();
        assertNull(a.fuelStatus());
        assertTrue(a.onSample(0x08, 1.5));               // STFT B2 → Probe angeboten
    }

    /** Log 19.11. durch die Live-Auswertung: Trim-Karte und Bericht melden das Bank-1-Muster wie das Dashboard. */
    @Test
    public void realLogThroughLiveAnalysis() throws Exception {
        LiveAnalysis a = new LiveAnalysis();
        int offered = 0;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("c350_log_20261119.csv")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(";");
                if (a.onSample(Integer.parseInt(f[1], 16), Double.parseDouble(f[2]))) offered++;
            }
        }
        assertTrue(offered > 500);
        assertTrue(a.trimMap.verdict(), a.trimMap.verdict().startsWith("Bank 1 wird in Leerlauf und unter Last stärker angefettet"));
        String report = a.report(new TrimHistory(), BERLIN);
        assertTrue(report, report.contains("Bank 1 wird in Leerlauf und unter Last stärker angefettet"));
        a.resetConnection();
        assertTrue(a.trimMap.verdict().startsWith("sammelt"));
    }
}
