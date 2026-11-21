package de.roman.obddashboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withChild;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.roman.mercedesobdmonitor.AdapterChoice;
import de.roman.mercedesobdmonitor.ObdSession;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Dashboard against the ELM327 simulator (tools/elm327-emulator) – the safety rules seen by the driver:
 * no value without a fresh answer, values gone right after disconnecting, warnings from live values, DTCs read only
 * on request. Runs on the emulator via tools/android-emulator/uitest.sh, which also starts the fault proxy.
 *
 * Instrumentation arguments: elmHost (default 192.168.178.50), elmPort (35000), deadPort (35009, nothing listens),
 * proxyPort (proxy.py hot,dtc; proxy tests are skipped without it).
 */
@RunWith(AndroidJUnit4.class)
public class DashboardUiTest {
    private static final String NONE = ObdSession.INVALID_TEXT;
    private static final String[] TILES = {"STFT B1", "STFT B2", "LTFT B1", "LTFT B2", "ECU-Spannung"};

    private final Bundle args = InstrumentationRegistry.getArguments();
    private ActivityScenario<DashboardActivity> scenario;

    @After
    public void tearDown() {
        // The session is a process-wide singleton (foreground service): never leave it connected for the next test.
        // Always disconnect, also after a failed attempt: the next test then starts from the same state ("Getrennt").
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ObdSession.get().disconnect());
        if (scenario != null) scenario.close();
        // The simulator serves one client at a time, like a Wi-Fi ELM327: wait until it answers again before the
        // next test connects (otherwise that connection waits behind the old one and times out).
        long start = SystemClock.uptimeMillis();
        boolean free = simulatorAnswers(15_000);
        long ms = SystemClock.uptimeMillis() - start;
        Log.i("DashboardUiTest", "simulator " + (free ? "free" : "still busy") + " after " + ms + " ms");
        if (!free) throw new AssertionError("ELM327-Simulator nach " + ms + " ms noch belegt – eine Verbindung "
                + "(App oder proxy.py) wurde nach dem Trennen nicht freigegeben");
    }

    /** ATZ against the simulator until it answers with the prompt; plain socket, from the test thread. */
    private boolean simulatorAnswers(long timeoutMs) {
        String host = args.getString("elmHost", "192.168.178.50");
        int port = port("elmPort", 35000);
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2_000);
                s.setSoTimeout(2_000);
                s.getOutputStream().write("ATZ\r".getBytes(StandardCharsets.US_ASCII));
                InputStream in = s.getInputStream();
                StringBuilder b = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    b.append((char) c);
                    if (c == '>') return true;   // full answer read: closing now does not hit the emulator's echo
                }
                Log.i("DashboardUiTest", "simulator probe: closed before the prompt");
            } catch (IOException e) {
                Log.i("DashboardUiTest", "simulator probe: " + e);
            }
            SystemClock.sleep(300);   // also after an early close: no tight reconnect loop
        }
        return false;
    }

    @Test
    public void startsDisconnectedWithoutValues() {
        launch(port("elmPort", 35000));
        on(withText(containsString("READ-ONLY"))).check(matches(isDisplayed()));
        // fresh process "Nicht verbunden", after an earlier session in the same process "Getrennt"
        on(anyOf(withText("Nicht verbunden"), withText("Getrennt"))).check(matches(isDisplayed()));
        on(withText("Verbinden")).check(matches(isDisplayed()));
        for (String tile : TILES) on(tileValue(tile)).check(matches(withText(NONE)));
    }

    @Test
    public void connectShowsValuesAndDisconnectClearsThemAtOnce() {
        launch(port("elmPort", 35000));
        on(withText("Verbinden")).perform(click());
        waitFor(allOf(tileValue("ECU-Spannung"), not(withText(NONE))), 25_000);
        on(withText("Trennen")).check(matches(isDisplayed()));

        on(withText("Trennen")).perform(click());
        waitFor(withText("Verbinden"), 5_000);
        // no stale value: every tile shows "–" right after disconnecting
        for (String tile : TILES) waitFor(allOf(tileValue(tile), withText(NONE)), 2_000);
    }

    @Test
    public void unreachableAdapterNeverShowsValues() {
        launch(port("deadPort", 35009));
        on(withText("Verbinden")).perform(click());
        long end = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < end) {
            for (String tile : TILES) on(tileValue(tile)).check(matches(withText(NONE)));
            SystemClock.sleep(500);
        }
    }

    @Test
    public void overheatingFromLiveValueRaisesAlarmAndDisconnectClearsIt() {
        launch(proxyPort());
        on(withText("Verbinden")).perform(click());
        // proxy: coolant 120 °C → alarm after the hold time of the warning rule
        waitFor(withText(containsString("Motor überhitzt")), 30_000);
        on(withText("Trennen")).perform(click());
        waitFor(withText("Verbinden"), 5_000);
        waitGone(withText(containsString("Motor überhitzt")), 3_000);
    }

    @Test
    public void storedCodesAreReadOnlyOnRequest() {
        launch(proxyPort());
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

    // --- helpers ---

    private void launch(int port) {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences(DashboardActivity.PREFS, Context.MODE_PRIVATE).edit()
                .clear()   // WLAN adapter, no auto-connect, no ADR-004 test switch
                .putString(AdapterChoice.KEY_HOST, args.getString("elmHost", "192.168.178.50"))
                .putString(AdapterChoice.KEY_PORT, String.valueOf(port))
                .putBoolean("notifications_asked", true)
                .commit();
        scenario = ActivityScenario.launch(DashboardActivity.class);
        on(withText("LIVE")).perform(click());
    }

    private int port(String key, int fallback) {
        String v = args.getString(key);
        return v == null ? fallback : Integer.parseInt(v);
    }

    private int proxyPort() {
        String v = args.getString("proxyPort");
        Assume.assumeTrue("proxyPort not given (proxy.py hot,dtc)", v != null);
        return Integer.parseInt(v);
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
        poll(() -> on(m).check(matches(isDisplayed())), timeoutMs);
    }

    private static void waitGone(Matcher<View> m, long timeoutMs) {
        poll(() -> on(m).check(doesNotExist()), timeoutMs);
    }

    /** Repeats an Espresso check until it passes; after the timeout its last failure is thrown. */
    private static void poll(Runnable check, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError | RuntimeException e) {
                if (SystemClock.uptimeMillis() > end) throw e;
                SystemClock.sleep(250);
            }
        }
    }
}
