package de.roman.obddashboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withChild;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.roman.mercedesobdmonitor.AdapterChoice;
import de.roman.mercedesobdmonitor.ObdSession;
import de.roman.testsupport.ElmTest;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Dashboard against the ELM327 simulator (tools/elm327-emulator) – the safety rules seen by the driver:
 * no value without a fresh answer, values gone right after disconnecting, warnings from live values, DTCs read only
 * on request. Runs on the emulator via tools/android-emulator/uitest.sh, which also starts the fault proxy.
 *
 * Arguments and simulator helpers: {@link ElmTest}.
 */
@RunWith(AndroidJUnit4.class)
public class DashboardUiTest {
    private static final String NONE = ObdSession.INVALID_TEXT;
    private static final String[] TILES = {"STFT B1", "STFT B2", "LTFT B1", "LTFT B2", "ECU-Spannung", "ATF Getriebe"};

    private ActivityScenario<DashboardActivity> scenario;

    @After
    public void tearDown() {
        // The session is a process-wide singleton (foreground service): never leave it connected for the next test.
        // Always disconnect, also after a failed attempt: the next test then starts from the same state ("Getrennt").
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ObdSession.get().disconnect());
        if (scenario != null) scenario.close();
        ElmTest.awaitSimulatorFree();
    }

    @Test
    public void startsDisconnectedWithoutValues() {
        launch(ElmTest.simulatorPort());
        on(withText(containsString("READ-ONLY"))).check(matches(isDisplayed()));
        // fresh process "Nicht verbunden", after an earlier session in the same process "Getrennt"
        on(anyOf(withText("Nicht verbunden"), withText("Getrennt"))).check(matches(isDisplayed()));
        on(withText("Verbinden")).check(matches(isDisplayed()));
        for (String tile : TILES) on(tileValue(tile)).check(matches(withText(NONE)));
    }

    @Test
    public void connectShowsValuesAndDisconnectClearsThemAtOnce() {
        launch(ElmTest.simulatorPort());
        on(withText("Verbinden")).perform(click());
        waitFor(allOf(tileValue("ECU-Spannung"), not(withText(NONE))), 25_000);
        on(withText("Trennen")).check(matches(isDisplayed()));

        on(withText("Trennen")).perform(click());
        waitFor(withText("Verbinden"), 5_000);
        // no stale value: within one short deadline every tile shows "–" (not 2 s per tile one after another)
        ElmTest.poll(() -> {
            for (String tile : TILES) on(tileValue(tile)).check(matches(withText(NONE)));
        }, 1_500);
    }

    @Test
    public void unreachableAdapterNeverShowsValues() {
        launch(ElmTest.deadPort());
        on(withText("Verbinden")).perform(click());
        long end = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < end) {
            for (String tile : TILES) on(tileValue(tile)).check(matches(withText(NONE)));
            SystemClock.sleep(500);
        }
    }

    @Test
    public void overheatingFromLiveValueRaisesAlarmAndDisconnectClearsIt() {
        launch(ElmTest.proxyPort());
        on(withText("Verbinden")).perform(click());
        // proxy: coolant 120 °C → alarm after the hold time of the warning rule
        waitFor(withText(containsString("Motor überhitzt")), 30_000);
        on(withText("Trennen")).perform(click());
        waitFor(withText("Verbinden"), 5_000);
        waitGone(withText(containsString("Motor überhitzt")), 3_000);
    }

    @Test
    public void storedCodesAreReadOnlyOnRequest() {
        launch(ElmTest.proxyPort());
        on(withText("DTC")).perform(click());
        on(withText(containsString("Noch kein Scan"))).check(matches(isDisplayed()));
        on(withText("Verbinden")).perform(click());
        waitFor(withText("Trennen"), 10_000);
        SystemClock.sleep(3_000);
        // connecting alone reads no fault memory
        on(withText(containsString("Noch kein Scan"))).check(matches(isDisplayed()));
        waitFor(allOf(withText("LESEN"), isEnabled()), 10_000);
        on(withText("LESEN")).perform(click());
        // proxy: Mode 03 answers P0171 and P0302
        waitFor(withText(containsString("P0171")), 45_000);
        on(withText(containsString("P0302"))).check(matches(isDisplayed()));
    }

    @Test
    public void gearAndTransmissionOilAreLiveWithWarning() {
        launch(ElmTest.atfProxyPort());
        on(withText("Verbinden")).perform(click());
        // proxy.py atf: recorded answer of 21.11. (D, gear 3 → 2) with ATF 125 °C
        waitFor(allOf(tileValue("ATF Getriebe"), withText("125 °C")), 25_000);
        waitFor(withContentDescription(containsString("Gang D 3→2")), 5_000);
        // 125 °C ≥ 120 °C: warning after its hold time, gone at once after disconnecting
        waitFor(withText(containsString("Getriebeöl 125 °C")), 20_000);
        on(withText("Trennen")).perform(click());
        waitFor(withText("Verbinden"), 5_000);
        ElmTest.poll(() -> {
            on(tileValue("ATF Getriebe")).check(matches(withText(NONE)));
            on(withContentDescription(containsString("Gang unbekannt"))).check(matches(isDisplayed()));
        }, 1_500);
        waitGone(withText(containsString("Getriebeöl")), 3_000);
    }

    @Test
    public void transmissionLiveSwitchesOffWhenTheGearboxNeverAnswers() {
        // the simulator answers 21 30 with NO DATA: after three tries Getriebe live is off for this connection
        launch(ElmTest.simulatorPort());
        on(withText("Verbinden")).perform(click());
        waitFor(allOf(tileValue("ATF Getriebe"), withText("aus"),
                withContentDescription("ATF Getriebe: aus – Getriebe antwortet nicht")), 30_000);
        on(tileValue("ECU-Spannung")).check(matches(not(withText(NONE))));   // the other values keep coming
    }

    // --- helpers ---

    private void launch(int port) {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences(DashboardActivity.PREFS, Context.MODE_PRIVATE).edit()
                .clear()   // WLAN adapter, no auto-connect, no ADR-004 test switch
                .putString(AdapterChoice.KEY_HOST, ElmTest.host())
                .putString(AdapterChoice.KEY_PORT, String.valueOf(port))
                .putBoolean(DashboardActivity.PREF_NOTIFICATIONS_ASKED, true)
                .commit();
        scenario = ActivityScenario.launch(DashboardActivity.class);
        on(withText("LIVE")).perform(click());
    }

    /** Hidden pages hold the same texts (status, buttons): match only what is on screen. */
    private static androidx.test.espresso.ViewInteraction on(Matcher<View> m) {
        return onView(allOf(m, isDisplayed()));
    }

    /** Value text of a live tile: the TextView next to the tile name, on the visible page only. */
    private static Matcher<View> tileValue(String name) {
        return allOf(withParent(withChild(withText(name))), not(withText(name)), isDisplayed());
    }

    private static void waitFor(Matcher<View> m, long timeoutMs) {
        ElmTest.poll(() -> on(m).check(matches(isDisplayed())), timeoutMs);
    }

    private static void waitGone(Matcher<View> m, long timeoutMs) {
        ElmTest.poll(() -> on(m).check(doesNotExist()), timeoutMs);
    }
}
