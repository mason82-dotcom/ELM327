package de.roman.mercedesobdmonitor;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withChild;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.roman.testsupport.ElmTest;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Monitor app against the ELM327 simulator – the same safety rules as the dashboard (no value without a fresh
 * answer, values gone right after disconnecting, DTCs only on request) plus the read-only terminal: blocked commands
 * (list from uitest.sh, argument "blocked") are refused in the app; uitest.sh checks the proxy's request log that none
 * of them reached the adapter and that connecting sent no Mode 03.
 * Arguments and simulator helpers: {@link ElmTest}.
 */
@RunWith(AndroidJUnit4.class)
public class MonitorUiTest {
    private static final String NONE = ObdSession.INVALID_TEXT;

    private ActivityScenario<MainActivity> scenario;

    @After
    public void tearDown() {
        // process-wide session (foreground service): always disconnect, then wait until the simulator is free again
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ObdSession.get().disconnect());
        if (scenario != null) scenario.close();
        ElmTest.awaitSimulatorFree();
    }

    @Test
    public void startsDisconnectedWithoutValues() {
        launch(ElmTest.simulatorPort());
        onView(withText(containsString("READ-ONLY"))).check(matches(isDisplayed()));
        onView(withText("Verbinden")).check(matches(isDisplayed()));
        for (ObdPid pid : ObdPid.defaultPids()) onView(pidValue(pid.label)).check(matches(withText(NONE)));
    }

    @Test
    public void connectShowsValuesAndDisconnectClearsThemAtOnce() {
        launch(ElmTest.simulatorPort());
        onView(withText("Verbinden")).perform(click());
        waitFor(allOf(pidValue("Steuergerät-Spannung"), not(withText(NONE))), 25_000);

        onView(withText("Trennen")).perform(click());
        // no stale value: within one short deadline every row shows "–" (not 2 s per row one after another)
        ElmTest.poll(() -> {
            for (ObdPid pid : ObdPid.defaultPids()) onView(pidValue(pid.label)).check(matches(withText(NONE)));
        }, 1_500);
    }

    @Test
    public void unreachableAdapterNeverShowsValues() {
        launch(ElmTest.deadPort());
        onView(withText("Verbinden")).perform(click());
        long end = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < end) {
            for (ObdPid pid : ObdPid.defaultPids()) onView(pidValue(pid.label)).check(matches(withText(NONE)));
            SystemClock.sleep(500);
        }
    }

    @Test
    public void storedCodesAreReadOnlyOnRequest() {
        launch(ElmTest.proxyPort());
        onView(withText("Verbinden")).perform(click());
        waitFor(allOf(pidValue("Steuergerät-Spannung"), not(withText(NONE))), 25_000);
        // connecting alone reads no fault memory: no result dialog, the activity keeps the focus …
        SystemClock.sleep(3_000);
        onView(withText("Verbinden")).check(matches(isDisplayed()));
        // … and no Mode 03 request: AT@1 (never sent by the apps themselves – unlike ATDPN, which the connection
        // setup sends) marks this moment in the proxy log; uitest.sh checks that no "03" lies between the
        // connection's ATZ and this mark.
        sendAnswered("AT@1");

        scrollPage(View.FOCUS_UP);   // the terminal is at the bottom, the DTC button near the top
        onView(withText("DTC")).perform(click());
        // proxy: Mode 03 answers P0171 and P0302, shown as a dialog
        ElmTest.poll(() -> onView(withText(containsString("P0171"))).inRoot(isDialog()).check(matches(isDisplayed())),
                45_000);
        onView(withText(containsString("P0302"))).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withText("OK")).inRoot(isDialog()).perform(click());
    }

    @Test
    public void terminalRefusesWritingCommandsAndSendsReadOnlyOnes() {
        launch(ElmTest.proxyPort());
        onView(withText("Verbinden")).perform(click());
        waitFor(allOf(pidValue("Steuergerät-Spannung"), not(withText(NONE))), 25_000);

        String[] blocked = ElmTest.blockedCommands();
        for (String cmd : blocked) {
            // While an exclusive request runs, the terminal answers only with a toast; resend until refused.
            ElmTest.poll(() -> {
                send(cmd);
                ElmTest.poll(() -> onView(withText(containsString("BLOCKIERT > " + cmd))).check(matches(anything())),
                        1_000);
            }, 10_000);
        }
        // the terminal itself works: an allowed request goes out. ATIGN is never sent by the app on its own, so
        // uitest.sh finding it in the proxy log proves that the log check covers the terminal.
        sendAnswered("ATIGN");
    }

    // --- helpers ---

    private void launch(int port) {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .clear()   // WLAN adapter, no auto-connect, no ADR-004 test switch
                .putString(AdapterChoice.KEY_HOST, ElmTest.host())
                .putString(AdapterChoice.KEY_PORT, String.valueOf(port))
                .putBoolean(MainActivity.PREF_NOTIFICATIONS_ASKED, true)
                .commit();
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    /** Sends an allowed terminal command and waits for the adapter's answer after its echo (it then reached the proxy). */
    private void sendAnswered(String cmd) {
        ElmTest.poll(() -> {
            send(cmd);
            ElmTest.poll(() -> onView(withText(answeredAfter("> " + cmd))).check(matches(anything())), 5_000);
        }, 15_000);
    }

    private void send(String cmd) {
        // The terminal is the last block of the long page. Espresso's scrollTo() scrolled but then found the field
        // "not displayed"; scrolling the page to its end shows the input row and the console reliably.
        scrollPage(View.FOCUS_DOWN);
        onView(allOf(isAssignableFrom(EditText.class), hasSibling(withText("Senden"))))
                .perform(replaceText(cmd), closeSoftKeyboard());
        onView(withText("Senden")).perform(click());
    }

    /** Scrolls the long page to its start ({@code View.FOCUS_UP}) or end ({@code View.FOCUS_DOWN}). */
    private void scrollPage(int direction) {
        scenario.onActivity(a -> {
            ScrollView page = findScrollView(a.findViewById(android.R.id.content));
            if (page == null) throw new AssertionError("no ScrollView in MainActivity");
            page.fullScroll(direction);
        });
    }

    private static ScrollView findScrollView(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScrollView(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    /** Console text in which {@code echo} is followed by a non-empty answer line. */
    private static Matcher<String> answeredAfter(String echo) {
        return new TypeSafeMatcher<String>() {
            @Override
            protected boolean matchesSafely(String text) {
                int i = text.lastIndexOf(echo);
                return i >= 0 && !text.substring(i + echo.length()).trim().isEmpty();
            }

            @Override
            public void describeTo(Description d) {
                d.appendText("console with an answer after \"" + echo + "\"");
            }
        };
    }

    /** Value text of a live row: the TextView next to the PID name. */
    private static Matcher<View> pidValue(String label) {
        return allOf(withParent(withChild(withText(label))), not(withText(label)));
    }

    private static void waitFor(Matcher<View> m, long timeoutMs) {
        // exists (rows and console may be scrolled out of view, so no isDisplayed())
        ElmTest.poll(() -> onView(m).check(matches(anything())), timeoutMs);
    }
}
